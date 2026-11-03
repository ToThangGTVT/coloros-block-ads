package com.utc.color.blockads.core.enginebridge

class EngineBridge {
    fun version(): String = nativeVersion()

    private external fun nativeVersion(): String

    private companion object {
        init {
            System.loadLibrary("blockads_core")
        }
    }
}
