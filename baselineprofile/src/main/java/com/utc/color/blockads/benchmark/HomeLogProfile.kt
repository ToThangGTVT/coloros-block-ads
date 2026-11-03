package com.utc.color.blockads.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeLogProfile {
    @get:Rule val profile = BaselineProfileRule()

    @Test
    fun homeAndPagedQueryLog() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("am start -W -n com.utc.color.blockads/.ProfileSeedActivity")
        check(device.wait(Until.hasObject(By.text("PROFILE_READY")), 15000))
        device.pressBack()
        profile.collect(
            packageName = "com.utc.color.blockads",
            maxIterations = 5,
            stableIterations = 2,
            filterPredicate = { rule ->
                listOf(
                        "Lcom/utc/color/blockads/",
                        "Lio/github/suqi8/coui/",
                        "Lcom/github/mikephil/charting/",
                        "Landroidx/navigation3/",
                    )
                    .any { it in rule }
            },
        ) {
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.desc("Nhật ký")), 10000))
            device.findObject(By.desc("Nhật ký")).click()
            check(device.wait(Until.hasObject(By.textContains("profile.test")), 10000))
            repeat(4) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 4,
                    device.displayWidth / 2,
                    device.displayHeight / 3,
                    20,
                )
            }
            device.findObject(By.desc("Trang chủ")).click()
        }
    }
}
