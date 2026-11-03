package com.utc.color.blockads.core.enginebridge

class EngineBridge {
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
}
