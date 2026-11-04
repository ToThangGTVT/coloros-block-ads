package com.utc.color.blockads

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.utc.color.blockads.core.certificate.CertificateRepository
import com.utc.color.blockads.core.enginebridge.EngineBridge
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeFalse

/** Backend-only: never installs the CA into the user store, so MITM must stay off. */
class PhaseFiveHttpsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun deletingCaRemovesPrivateKeyFilesAndDisablesHttpsAndAllowsNewCa() = runBlocking {
        lateinit var model: MainViewModel
        compose.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
        }
        val certificates = model.certificates
        certificates.awaitReady()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val file = File(compose.activity.filesDir, "certificate/root-ca.der")
        val temporary = File(file.parentFile, "root-ca.tmp")
        // This test must never remove a CA that already belongs to the user of the device.
        assumeFalse("Existing CA must be preserved", keys.containsAlias(CertificateRepository.Alias) || file.exists() || temporary.exists())
        val original = model.settings.https.first()
        try {
            certificates.create()
            val previous = certificates.state.value.fingerprint
            val oldKey = keys.getKey(CertificateRepository.Alias, null) as PrivateKey
            temporary.writeText("interrupted certificate write")
            model.settings.setHttps(original.copy(enabled = true))

            certificates.delete()
            assertFalse(keys.containsAlias(CertificateRepository.Alias))
            assertFalse(file.exists())
            assertFalse(temporary.exists())
            assertFalse(model.settings.https.first().enabled)
            assertFalse(certificates.state.value.created)
            assertFalse(certificates.state.value.installed)
            assertNull(certificates.state.value.fingerprint)
            assertTrue(runCatching { certificates.exportCertificate() }.isFailure)
            assertTrue("Deleted private key still signs", runCatching {
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(oldKey)
                    update(byteArrayOf(1, 2, 3))
                    sign()
                }
            }.isFailure)
            certificates.refresh()
            assertFalse(certificates.state.value.created)
            // Repeating deletion, including the no-key/no-file case, is harmless.
            certificates.delete()
            certificates.create()
            assertTrue(certificates.state.value.created)
            assertNotEquals(previous, certificates.state.value.fingerprint)
            assertFalse(model.settings.https.first().enabled)
        } finally {
            certificates.delete()
            model.settings.setHttps(original)
        }
    }

    @Test
    fun keystoreCaIsNonExportableAndHttpsStaysOffUntilInstalled() = runBlocking {
        lateinit var model: MainViewModel
        compose.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
        }
        val certificates = model.certificates
        certificates.awaitReady()
        assertTrue("native HTTPS API missing", EngineBridge().supportsHttps())
        val original = model.settings.https.first()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val file = File(compose.activity.filesDir, "certificate/root-ca.der")
        val existed = keys.containsAlias(CertificateRepository.Alias) || file.exists()
        try {
            model.settings.setHttps(original.copy(enabled = false))
            certificates.create()
            val state = certificates.state.first { it.created || it.error != null }
            assertNull(state.error)
            assertTrue(state.created)
            assertFalse("CA unexpectedly installed in user store", state.installed)
            assertEquals(95, state.fingerprint!!.length)

            val privateKey = keys.getKey(CertificateRepository.Alias, null) as PrivateKey
            assertNull("Keystore private key must not export", privateKey.encoded)
            val der = certificates.exportCertificate()
            assertArrayEquals(file.readBytes(), der)
            val root =
                CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
                    as X509Certificate
            root.checkValidity()
            assertEquals(0, root.basicConstraints)
            assertTrue(root.subjectX500Principal.name.contains("BlockAds Local CA"))
            assertEquals(root.subjectX500Principal, root.issuerX500Principal)
            root.verify(keys.getCertificate(CertificateRepository.Alias).publicKey)
            val years = (root.notAfter.time - System.currentTimeMillis()) / (365L * 86400000)
            assertTrue(years in 4..5)

            // Not installed → enabling is refused and native config stays disabled.
            assertTrue(runCatching { certificates.setEnabled(true) }.isFailure)
            assertFalse(model.settings.https.first().enabled)
            // Creating twice keeps the same root (no silent re-key).
            certificates.create()
            assertEquals(state.fingerprint, certificates.state.value.fingerprint)
        } finally {
            model.settings.setHttps(original)
            if (!existed) {
                keys.deleteEntry(CertificateRepository.Alias)
                file.delete()
                certificates.refresh()
                assertFalse(certificates.state.value.created)
            }
        }
    }
}
