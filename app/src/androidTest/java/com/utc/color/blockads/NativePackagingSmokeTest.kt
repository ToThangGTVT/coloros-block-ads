package com.utc.color.blockads
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utc.color.blockads.core.enginebridge.EngineBridge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class NativePackagingSmokeTest {
    @Test fun kotlinAndNativeUseTheStablePhaseFourApi() { assertEquals("0.4.0-phase4", EngineBridge().version()) }
}
