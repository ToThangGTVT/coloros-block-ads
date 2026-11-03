package com.utc.color.blockads

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.utc.color.blockads.core.certificate.CertificateRepository
import com.utc.color.blockads.core.enginebridge.EngineBridge
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Backend-only: never installs the CA into the user store, so MITM must stay off. */
class PhaseFiveHttpsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
            model.settings.setHttps(original.copy(enabled = false, domains = listOf("example.com")))
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
