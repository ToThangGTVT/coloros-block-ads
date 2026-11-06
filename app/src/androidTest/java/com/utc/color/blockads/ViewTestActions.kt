package com.utc.color.blockads

import android.os.SystemClock
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

internal val instrumentation get() = InstrumentationRegistry.getInstrumentation()
internal val device get() = UiDevice.getInstance(instrumentation)
internal val targetContext get() = instrumentation.targetContext

internal fun res(id: String): BySelector = By.res(targetContext.packageName, id)

internal fun visible(selector: BySelector, timeout: Long = 10_000): UiObject2 =
    checkNotNull(device.wait(Until.findObject(selector), timeout)) { "View did not appear: $selector" }

internal fun text(value: String): UiObject2 = visible(By.text(value))

internal fun clickText(value: String) = text(value).click()

internal fun openTab(id: String) = visible(res(id)).click()

internal fun enterText(value: String) {
    visible(By.clazz(EditText::class.java)).text = value
}

internal fun scrollToText(list: String, value: String) = scrollTo(list, By.text(value))

internal fun scrollTo(list: String, target: BySelector): UiObject2 {
    val container = visible(res(list))
    container.findObject(target)?.let { return it }
    for (direction in listOf(Direction.DOWN, Direction.UP)) {
        for (attempt in 1..12) {
            val canContinue = container.scroll(direction, 0.75f)
            container.findObject(target)?.let { return it }
            if (!canContinue) break
        }
    }
    error("Could not find $target in $list")
}

internal fun await(timeout: Long = 10_000, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + timeout
    do {
        if (condition()) return
        SystemClock.sleep(50)
    } while (SystemClock.uptimeMillis() < deadline)
    check(condition()) { "Condition did not become true within ${timeout}ms" }
}
