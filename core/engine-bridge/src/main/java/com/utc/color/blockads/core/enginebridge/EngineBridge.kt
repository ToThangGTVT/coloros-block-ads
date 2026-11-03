package com.utc.color.blockads.core.enginebridge

class EngineBridge {
    fun configurePolicy(json: String): Boolean = nativeConfigurePolicy(json)

    private external fun nativeConfigurePolicy(json: String): Boolean

    fun replaceRules(system: String, custom: String, apply: Boolean = true): String =
        nativeReplaceRules(system, custom, apply)

    fun configureDns(json: String): Boolean = nativeConfigureDns(json)

    fun setDnsEventSink(sink: DnsEventSink?) = nativeSetDnsEventSink(sink)

    private external fun nativeReplaceRules(system: String, custom: String, apply: Boolean): String

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
