package com.utc.color.blockads.core.certificate

import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.common.R as L10n
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.data.UserCaTrust
import com.utc.color.blockads.core.enginebridge.EngineBridge
import com.utc.color.blockads.core.enginebridge.RootCaSigner
import java.io.File
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** The root private key is an Android Keystore handle; only public certificates can be exported. */
class CertificateRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val engine: EngineBridge,
) {
    private val nativeSupported = engine.supportsHttps()
    private val file = File(context.filesDir, "certificate/root-ca.der")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val _state = MutableStateFlow(CertificateState())
    val state = _state.asStateFlow()

    init {
        scope.launch {
            refresh()
            if (!ready.isCompleted) ready.complete(Unit)
        }
        scope.launch {
            combine(settings.https, state) { _, _ -> Unit }
                .collect {
                    // Serialize native updates with create/refresh/delete. Read the latest values
                    // inside the lock so a queued emission cannot restore a deleted CA's config.
                    lock.withLock {
                        val config = settings.https.first()
                        val state = _state.value
                        val json =
                            JSONObject()
                                .put("enabled", config.enabled && state.installed && state.created)
                                .put("apps", JSONArray(filterable(config.apps)))
                        // Never crash the process on a rejected config: fall back to MITM off.
                        if (nativeSupported && !engine.configureHttps(json.toString())) {
                            engine.configureHttps("{}")
                            _state.update {
                                it.copy(error = context.getString(L10n.string.https_config_invalid))
                            }
                        }
                    }
                }
        }
    }

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    suspend fun awaitReady() = ready.await()

    suspend fun create() =
        withContext(Dispatchers.IO) {
            require(nativeSupported) {
                context.getString(L10n.string.https_api_unsupported)
            }
            lock.withLock {
                val keys = store()
                if (!keys.containsAlias(Alias)) {
                    val generator =
                        KeyPairGenerator.getInstance(
                            KeyProperties.KEY_ALGORITHM_EC,
                            "AndroidKeyStore",
                        )
                    generator.initialize(
                        KeyGenParameterSpec.Builder(
                                Alias,
                                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                            )
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setUserAuthenticationRequired(false)
                            .setCertificateSubject(X500Principal("CN=BlockAds Keystore Key"))
                            .setCertificateSerialNumber(java.math.BigInteger.ONE)
                            .setCertificateNotBefore(
                                java.util.Date(System.currentTimeMillis() - 86400000L)
                            )
                            .setCertificateNotAfter(
                                java.util.Date(System.currentTimeMillis() + 5L * 365 * 86400000)
                            )
                            .build()
                    )
                    generator.generateKeyPair()
                }
                if (!file.exists()) {
                    val (signer, point) = signer(keys)
                    val der = engine.generateCa(signer, point)
                    val certificate = parse(der)
                    require(certificate.basicConstraints >= 0) { context.getString(L10n.string.ca_basic_constraints) }
                    certificate.verify(keys.getCertificate(Alias).publicKey)
                    file.parentFile!!.mkdirs()
                    val temp = File(file.parentFile, "root-ca.tmp")
                    temp.writeBytes(der)
                    check(temp.renameTo(file)) { context.getString(L10n.string.certificate_save_failed) }
                }
            }
            refresh()
        }

    private fun parse(der: ByteArray) =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
            as X509Certificate

    private fun signer(keys: KeyStore): Pair<RootCaSigner, ByteArray> {
        val key = keys.getKey(Alias, null) as PrivateKey
        val public = keys.getCertificate(Alias).publicKey as ECPublicKey
        fun coordinate(value: java.math.BigInteger): ByteArray {
            val source = value.toByteArray()
            val output = ByteArray(32)
            val offset = (source.size - 32).coerceAtLeast(0)
            val count = source.size - offset
            System.arraycopy(source, offset, output, output.size - count, count)
            return output
        }
        val point = byteArrayOf(4) + coordinate(public.w.affineX) + coordinate(public.w.affineY)
        return RootCaSigner { data ->
            require(data.size <= 8192)
            Signature.getInstance("SHA256withECDSA").run {
                initSign(key)
                update(data)
                sign()
            }
        } to point
    }

    suspend fun refresh() =
        withContext(Dispatchers.IO) {
            if (!nativeSupported) {
                _state.value =
                    CertificateState(
                        error =
                            context.getString(L10n.string.https_api_mismatch)
                    )
                return@withContext
            }
            lock.withLock {
                runCatching {
                    val keys = store()
                    if (!file.exists() || !keys.containsAlias(Alias)) {
                        check(engine.setCa(null))
                        _state.value = CertificateState()
                        return@withLock
                    }
                    require(file.length() <= 8192) { context.getString(L10n.string.certificate_invalid) }
                    val der = file.readBytes()
                    val certificate = parse(der)
                    certificate.checkValidity()
                    require(certificate.basicConstraints >= 0)
                    certificate.verify(keys.getCertificate(Alias).publicKey)
                    val digest = MessageDigest.getInstance("SHA-256").digest(der)
                    val trusted = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
                    val installed =
                        trusted.aliases().asSequence().any { alias ->
                            alias.startsWith("user:") &&
                                runCatching {
                                        MessageDigest.isEqual(
                                            digest,
                                            MessageDigest.getInstance("SHA-256")
                                                .digest(trusted.getCertificate(alias).encoded),
                                        )
                                    }
                                    .getOrDefault(false)
                        }
                    val (signer, point) = signer(keys)
                    val fingerprint = digest.joinToString(":") { "%02X".format(it) }
                    val previous = state.value
                    if (previous.fingerprint != fingerprint || previous.installed != installed)
                        check(
                            if (installed) engine.setCa(signer, point, der) else engine.setCa(null)
                        )
                    val privateKey = keys.getKey(Alias, null) as PrivateKey
                    val info =
                        KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore")
                            .getKeySpec(privateKey, KeyInfo::class.java)
                    val hardware =
                        info.securityLevel in
                            listOf(
                                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                                KeyProperties.SECURITY_LEVEL_STRONGBOX,
                            )
                    _state.value =
                        CertificateState(
                            true,
                            installed,
                            digest.joinToString(":") { "%02X".format(it) },
                            hardware,
                        )
                }
                    .onFailure { error ->
                        runCatching { engine.setCa(null) }
                        _state.value =
                            CertificateState(error = error.userMessage(context.resources) ?: context.getString(L10n.string.ca_read_failed))
                    }
            }
        }

    suspend fun exportCertificate(): ByteArray =
        withContext(Dispatchers.IO) {
            lock.withLock {
                require(file.exists())
                file.readBytes()
            }
        }

    /** Deletes the non-exportable private key and app-owned files, not Android's user CA store. */
    suspend fun delete() =
        withContext(Dispatchers.IO + NonCancellable) {
            lock.withLock {
                settings.setHttps(settings.https.first().copy(enabled = false))
                if (nativeSupported) {
                    check(engine.configureHttps("{}")) { context.getString(L10n.string.https_disable_failed) }
                    // Drops the native signer and leaf cache; also closes current HTTPS proxies.
                    check(engine.setCa(null)) { context.getString(L10n.string.ca_unload_failed) }
                }
                val keys = store()
                keys.deleteEntry(Alias)
                check(!keys.containsAlias(Alias)) { context.getString(L10n.string.ca_key_delete_failed) }
                _state.value = CertificateState()
                for (path in listOf(file, File(file.parentFile, "root-ca.tmp"))) {
                    check(!path.exists() || path.delete()) { context.getString(L10n.string.file_delete_failed, path.name) }
                }
            }
        }

    suspend fun setEnabled(enabled: Boolean) {
        refresh()
        lock.withLock {
            if (enabled) {
                require(state.value.created && state.value.installed) {
                    context.getString(L10n.string.ca_install_first)
                }
                require(filterable(settings.https.first().apps).isNotEmpty()) {
                    context.getString(L10n.string.https_select_app)
                }
            }
            settings.setHttps(settings.https.first().copy(enabled = enabled))
        }
    }

    /** Selected apps that would reject our CA are never intercepted, even if saved earlier. */
    private suspend fun filterable(apps: List<String>) =
        withContext(Dispatchers.IO) { apps.filter { UserCaTrust.trusts(context, it) } }

    companion object {
        const val Alias = "blockads-local-ca-p256-v1"
    }
}
