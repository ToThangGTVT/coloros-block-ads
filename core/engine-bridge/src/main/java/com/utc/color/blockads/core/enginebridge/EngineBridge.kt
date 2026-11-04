package com.utc.color.blockads.core.enginebridge

import com.utc.color.blockads.core.common.R as L10n
import com.utc.color.blockads.core.common.localizedError
class EngineBridge {
    fun supportsHttps(): Boolean =
        try {
            nativeHttpsApiVersion() == 1
        } catch (_: UnsatisfiedLinkError) {
            false
        }

    private external fun nativeHttpsApiVersion(): Int

    fun generateCa(signer: RootCaSigner, publicPoint: ByteArray): ByteArray =
        nativeGenerateCa(signer, publicPoint) ?: error(localizedError(L10n.string.ca_create_failed))

    fun setCa(
        signer: RootCaSigner?,
        publicPoint: ByteArray = byteArrayOf(),
        certificate: ByteArray = byteArrayOf(),
    ): Boolean = nativeSetCa(signer, publicPoint, certificate)

    fun configureHttps(json: String): Boolean = nativeConfigureHttps(json)

    private external fun nativeGenerateCa(signer: RootCaSigner, publicPoint: ByteArray): ByteArray?

    private external fun nativeSetCa(
        signer: RootCaSigner?,
        publicPoint: ByteArray,
        certificate: ByteArray,
    ): Boolean

    private external fun nativeConfigureHttps(json: String): Boolean

    fun configurePolicy(json: String): Boolean = nativeConfigurePolicy(json)

    private external fun nativeConfigurePolicy(json: String): Boolean

    /** DNS lists feed only the domain matcher; browser lists only decrypted HTTPS. */
    fun replaceRules(dns: String, browser: String, custom: String, apply: Boolean = true): String =
        nativeReplaceRules(dns, browser, custom, apply)

    fun configureDns(json: String): Boolean = nativeConfigureDns(json)

    fun setDnsEventSink(sink: DnsEventSink?) = nativeSetDnsEventSink(sink)

    private external fun nativeReplaceRules(
        dns: String,
        browser: String,
        custom: String,
        apply: Boolean,
    ): String

    /**
     * Installs AdGuard Scriptlets `scriptlets.corelibs.json` / `redirects.json` (either may be
     * empty). Returns `{"scriptlets":n,"redirects":n,"skipped":n}` or `{"error":"..."}`; on error
     * the previous set stays active.
     */
    fun setResources(scriptlets: String, redirects: String): String =
        nativeSetResources(scriptlets, redirects) ?: error(localizedError(L10n.string.resources_load_failed))

    private external fun nativeSetResources(scriptlets: String, redirects: String): String?

    private external fun nativeConfigureDns(json: String): Boolean

    private external fun nativeSetDnsEventSink(sink: DnsEventSink?)

    fun version(): String = nativeVersion()

    /** Consumes a detached TUN fd, even when startup returns false. */
    fun start(fd: Int, protector: SocketProtector, dns: String): Boolean =
        nativeStart(fd, protector, dns)

    fun stop() = nativeStop()

    fun updateNetwork(dns: String): Boolean = nativeUpdateNetwork(dns)

    fun isRunning(): Boolean = nativeIsRunning()

    fun counters(): LongArray = nativeCounters()

    private external fun nativeVersion(): String

    private external fun nativeStart(fd: Int, protector: SocketProtector, dns: String): Boolean

    private external fun nativeStop()

    private external fun nativeUpdateNetwork(dns: String): Boolean

    private external fun nativeIsRunning(): Boolean

    private external fun nativeCounters(): LongArray

    private companion object {
        init {
            System.loadLibrary("blockads_core")
        }
    }
}

/** Called once per outbound socket, before connect/send; failure closes the socket. */
fun interface SocketProtector {
    fun protect(fd: Int): Boolean

    fun connectionOwner(
        protocol: Int,
        source: String,
        sourcePort: Int,
        destination: String,
        destinationPort: Int,
    ): Int = -1
}

fun interface DnsEventSink {
    fun onDnsEvents(events: String)
}

fun interface RootCaSigner {
    fun sign(data: ByteArray): ByteArray
}
