package com.utc.color.blockads.core.designsystem

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.Switch
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import com.utc.color.blockads.core.designsystem.databinding.PreferenceContentBinding

enum class PreferenceIcon(@DrawableRes val drawable: Int) {
    Arrow(R.drawable.ic_chevron_right),
    Dropdown(R.drawable.ic_choice_dropdown),
    Check(R.drawable.ic_choice_check),
    Info(R.drawable.ic_help),
}

class PreferenceRow(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val views = PreferenceContentBinding.inflate(LayoutInflater.from(context), this)
    private var checked: Boolean? = null

    init {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(52)
        setPadding(context.dp(12))
        val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        foreground = attributes.getDrawable(0)
        attributes.recycle()
        views.toggle.setOnClickListener {
            val willCheck = checked != true
            if (performClick()) {
                val feedback = if (Build.VERSION.SDK_INT >= 34) {
                    if (willCheck) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
                } else HapticFeedbackConstants.CLOCK_TICK
                it.performHapticFeedback(feedback)
            }
        }
    }

    fun bind(
        title: CharSequence,
        summary: CharSequence? = null,
        trailingIcon: PreferenceIcon? = null,
        checked: Boolean? = null,
        enabled: Boolean = true,
        action: (() -> Unit)? = null,
    ) {
        views.title.text = title
        views.summary.text = summary
        views.summary.isVisible = !summary.isNullOrBlank()
        views.trailingIcon.isVisible = trailingIcon != null
        views.trailingIcon.setImageResource(trailingIcon?.drawable ?: 0)
        if (trailingIcon != null) {
            val tint = if (trailingIcon == PreferenceIcon.Check) androidx.appcompat.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOnSurfaceVariant
            views.trailingIcon.imageTintList = ColorStateList.valueOf(context.color(tint))
        }
        views.toggle.isVisible = checked != null
        views.toggle.render(checked == true, enabled)
        views.toggle.isClickable = checked != null && enabled && action != null
        this.checked = checked
        isEnabled = enabled
        alpha = if (enabled || checked != null) 1f else 0.5f
        val labelAlpha = if (!enabled && checked != null) 0.5f else 1f
        views.title.alpha = labelAlpha
        views.summary.alpha = labelAlpha
        views.icon.alpha = labelAlpha
        setOnClickListener(if (action == null) null else OnClickListener { action() })
        isClickable = action != null
        isFocusable = action != null
        isScreenReaderFocusable = action != null
    }

    fun showIcon(bitmap: Bitmap?) {
        views.icon.setImageBitmap(bitmap)
        views.icon.isVisible = bitmap != null
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        checked?.let {
            info.className = Switch::class.java.name
            info.isCheckable = true
            info.isChecked = it
        }
    }
}
