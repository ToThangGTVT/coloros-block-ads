package com.utc.color.blockads.core.certificate

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.data.SettingsRepository
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
    context: Context,
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
            combine(settings.https, state) { config, state -> config to state }
                .collect { (config, state) ->
                    val json =
                        JSONObject()
                            .put("enabled", config.enabled && state.installed && state.created)
                            .put("apps", JSONArray(config.apps))
                            .put("domains", JSONArray(config.domains))
                            .put("excluded", JSONArray(config.excluded))
                    // Never crash the process on a rejected config: fall back to MITM off.
                    if (nativeSupported && !engine.configureHttps(json.toString())) {
                        engine.configureHttps("{}")
                        _state.update {
                            it.copy(error = "Cấu hình HTTPS không hợp lệ; đã tắt xét HTTPS")
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
                "Lõi Rust chưa hỗ trợ API HTTPS của bản app này; hãy build/cài lại đúng bản"
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
                    require(certificate.basicConstraints >= 0) { "CA không có BasicConstraints" }
                    certificate.verify(keys.getCertificate(Alias).publicKey)
                    file.parentFile!!.mkdirs()
                    val temp = File(file.parentFile, "root-ca.tmp")
                    temp.writeBytes(der)
                    check(temp.renameTo(file)) { "Không lưu được chứng chỉ" }
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
                            "Lõi Rust và API HTTPS chưa đồng bộ. DNS/SNI vẫn hoạt động; cần build lại native trước khi dùng HTTPS."
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
                    require(file.length() <= 8192) { "Chứng chỉ không hợp lệ" }
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
                            CertificateState(error = error.message ?: "Không đọc được CA")
                    }
            }
        }

    suspend fun exportCertificate(): ByteArray =
        withContext(Dispatchers.IO) {
            require(file.exists())
            file.readBytes()
        }

    suspend fun setEnabled(enabled: Boolean) {
        refresh()
        if (enabled) {
            require(state.value.created && state.value.installed) {
                "Hãy tạo và cài CA trong Settings hệ thống trước"
            }
            require(settings.https.first().domains.isNotEmpty()) {
                "Chọn ít nhất một domain cho phép HTTPS"
            }
        }
        settings.setHttps(settings.https.first().copy(enabled = enabled))
    }

    companion object {
        const val Alias = "blockads-local-ca-p256-v1"
    }
}
