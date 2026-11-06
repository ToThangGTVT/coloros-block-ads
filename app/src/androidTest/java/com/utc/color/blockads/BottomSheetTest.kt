package com.utc.color.blockads

import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.uiautomator.By
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.utc.color.blockads.core.designsystem.FormSheet
import com.utc.color.blockads.core.designsystem.button
import com.utc.color.blockads.core.designsystem.choiceSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.utc.color.blockads.core.common.R as Strings
import com.utc.color.blockads.core.designsystem.R as Design

class BottomSheetTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun thousandChoicesAreLazyAndTheLastChoiceCanBeSelected() {
        var selected = -1
        lateinit var list: RecyclerView
        activity.scenario.onActivity {
            val context = it.findViewById<BottomNavigationView>(R.id.navigation).context
            val labels = List(1000) { index -> "Lựa chọn $index" }
            val dialog = choiceSheet(context, "Danh sách dài", labels, 0) { index -> selected = index }
            dialog.show()
            list = checkNotNull(dialog.findViewById(Design.id.choice_list))
        }
        text("Lựa chọn 0")
        assertFalse(device.hasObject(By.text("Lựa chọn 999")))
        activity.scenario.onActivity {
            assertTrue(list.childCount < 1000)
            list.scrollToPosition(999)
        }
        clickText("Lựa chọn 999")
        activity.scenario.onActivity { assertEquals(999, selected) }
    }

    @Test
    fun sheetBottomButtonReceivesRealTouchAboveBottomNavigation() {
        var pressed = false
        activity.scenario.onActivity {
            val sheet = FormSheet(it.findViewById<BottomNavigationView>(R.id.navigation).context, "Chi tiết")
            sheet.content.button("Nút cuối sheet") {
                pressed = true
                sheet.dialog.dismiss()
            }
            sheet.dialog.show()
        }
        clickText("Nút cuối sheet")
        activity.scenario.onActivity {
            assertTrue("Bottom nav intercepted the sheet button", pressed)
            assertEquals(R.id.home, it.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId)
        }
    }

    @Test
    fun preferenceSheetChoiceAndCancelDoNotNavigateToAnUnderlyingTab() {
        var selected = -1
        fun showChoices() {
            activity.scenario.onActivity {
                val context = it.findViewById<BottomNavigationView>(R.id.navigation).context
                choiceSheet(context, "Chọn thử", listOf("Một", "Hai", "Ba"), 0) { index ->
                    selected = index
                }.show()
            }
        }
        showChoices()
        clickText("Ba")
        activity.scenario.onActivity {
            assertEquals(2, selected)
            assertEquals(R.id.home, it.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId)
        }
        showChoices()
        clickText(appString(Strings.string.cancel))
        activity.scenario.onActivity {
            assertEquals(2, selected)
            assertEquals(R.id.home, it.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId)
        }
    }
}
