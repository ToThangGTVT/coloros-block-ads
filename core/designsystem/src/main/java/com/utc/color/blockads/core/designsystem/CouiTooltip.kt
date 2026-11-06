// Copyright 2026, compose-coui-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Android View port of the COUI 1.1.0 RichTooltip: caret toward the anchor, 16dp corners,
// 10dp/10% shadow, list-popup fade and scale springs. Outside tap or Back dismisses it.
package com.utc.color.blockads.core.designsystem

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.max
import kotlin.math.roundToInt

class CouiTooltip private constructor(private val anchor: View, text: CharSequence) {
    private val context = anchor.context
    private val bubble = TooltipBubble(context, text)
    private val popup = AnimatedPopup()
    private var leaving = false
    private var dismissedAt = 0L

    init {
        popup.setBackgroundDrawable(null)
        popup.isOutsideTouchable = true
        popup.animationStyle = 0
        popup.isClippingEnabled = false
    }

    val isShowing get() = popup.isShowing && !leaving

    fun show() {
        if (!anchor.isAttachedToWindow) return
        leaving = false
        val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val shadow = bubble.shadowMargin
        // COUI clamps to the window edge; keep the page's 16dp gutter so the bubble never touches it.
        val edge = context.dp(16)
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(max(0, frame.width() - 2 * edge) + 2 * shadow, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val width = bubble.measuredWidth - 2 * shadow
        val height = bubble.measuredHeight - 2 * shadow
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val anchorLeft = location[0]
        val anchorTop = location[1]
        val spacing = context.dp(8)
        val below = anchorTop + anchor.height + spacing
        val above = anchorTop - height - spacing
        val placeBelow = below + height <= frame.bottom || above < frame.top
        val minX = frame.left + edge
        val x = (anchorLeft + (anchor.width - width) / 2).coerceIn(minX, max(minX, frame.right - edge - width))
        val y = (if (placeBelow) below else above).coerceIn(frame.top, max(frame.top, frame.bottom - height))
        bubble.caretOnTop = placeBelow
        bubble.caretCenter = (anchorLeft + anchor.width / 2f - x)
        bubble.pivotX = shadow + width / 2f
        bubble.pivotY = shadow + height / 2f
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x - shadow, y - shadow)
        bubble.alpha = 0f
        bubble.scaleX = 0.9f
        bubble.scaleY = 0.9f
        // COUI ListPopupDefaults enter: alpha folme(0.8, 0.35s), scale spring(0.8, 322.27).
        spring(DynamicAnimation.ALPHA, 1f, folmeSpring(1f, 0.8f, 0.35f), 1f / 256f)
        spring(DynamicAnimation.SCALE_X, 1f, SpringForce(1f).setDampingRatio(0.8f).setStiffness(322.27f), 0.0001f)
        spring(DynamicAnimation.SCALE_Y, 1f, SpringForce(1f).setDampingRatio(0.8f).setStiffness(322.27f), 0.0001f)
    }

    fun dismiss() = popup.dismiss()

    fun dismissNow() {
        if (!leaving) dismissedAt = SystemClock.uptimeMillis()
        leaving = true
        bubble.cancelSprings()
        if (popup.isShowing) dismissPopup()
    }

    private fun animateOut() {
        if (leaving || !popup.isShowing) return
        leaving = true
        dismissedAt = SystemClock.uptimeMillis()
        bubble.cancelSprings()
        // Exit: alpha folme(1.0, 0.25s) drives removal; scale settles on its own no-bounce spring.
        spring(DynamicAnimation.SCALE_X, 0.9f, SpringForce(0.9f).setDampingRatio(1f).setStiffness(SpringForce.STIFFNESS_MEDIUM), 0.0001f)
        spring(DynamicAnimation.SCALE_Y, 0.9f, SpringForce(0.9f).setDampingRatio(1f).setStiffness(SpringForce.STIFFNESS_MEDIUM), 0.0001f)
        spring(DynamicAnimation.ALPHA, 0f, folmeSpring(0f, 1f, 0.25f), 1f / 256f) { if (leaving) dismissPopup() }
    }

    private fun dismissPopup() {
        if (popup.isShowing) popup.dismissImmediately()
    }

    /** Outside taps and Back call [dismiss]; the window goes away after the exit springs. */
    private inner class AnimatedPopup :
        PopupWindow(bubble, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true) {
        override fun dismiss() = animateOut()

        fun dismissImmediately() = super.dismiss()
    }

    private fun spring(
        property: DynamicAnimation.ViewProperty, target: Float, force: SpringForce, minimumChange: Float, end: () -> Unit = {},
    ) {
        val animation = SpringAnimation(bubble, property).setSpring(force.setFinalPosition(target)).apply {
            setMinimumVisibleChange(minimumChange)
            addEndListener { _, canceled, _, _ -> if (!canceled) end() }
        }
        bubble.springs += animation
        animation.start()
    }

    companion object {
        /** Toggles the help tooltip of [anchor]; a tap that just closed it does not reopen it. */
        fun toggle(anchor: View, text: CharSequence): CouiTooltip? {
            val current = anchor.getTag(R.id.coui_tooltip) as? CouiTooltip
            if (current != null) {
                if (current.isShowing) {
                    current.dismiss()
                    return null
                }
                if (SystemClock.uptimeMillis() - current.dismissedAt < 300) return null
            }
            return CouiTooltip(anchor, text).also {
                anchor.setTag(R.id.coui_tooltip, it)
                it.show()
            }
        }
    }
}

private class TooltipBubble(context: Context, text: CharSequence) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    val shadowMargin = context.dp(10)
    private val caretWidth = 16 * density
    private val caretHeight = 8 * density
    private val radius = 16 * density
    private val container = context.getColor(R.color.coui_tooltip_container)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = container }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = container
        setShadowLayer(10 * density, 0f, 0f, ColorUtils.setAlphaComponent(Color.BLACK, 26))
    }
    private val caret = Path()
    private val body = RectF()
    val springs = mutableListOf<SpringAnimation>()
    var caretOnTop = true
        set(value) {
            field = value
            updatePadding()
        }
    var caretCenter = 0f

    init {
        setWillNotDraw(false)
        val label = TextView(context).apply {
            this.text = text
            setTextColor(context.getColor(R.color.coui_tooltip_content))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f * 1.2245f, resources.displayMetrics).roundToInt()
            maxWidth = context.dp(280 - 32)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        accessibilityPaneTitle = "Tooltip"
        updatePadding()
    }

    private fun updatePadding() {
        val inside = context.dp(16)
        val caret = caretHeight.roundToInt()
        setPadding(
            shadowMargin + inside,
            shadowMargin + inside + if (caretOnTop) caret else 0,
            shadowMargin + inside,
            shadowMargin + inside + if (caretOnTop) 0 else caret,
        )
    }

    fun cancelSprings() {
        springs.forEach { it.cancel() }
        springs.clear()
    }

    override fun onDraw(canvas: Canvas) {
        val left = shadowMargin.toFloat()
        val right = width - shadowMargin.toFloat()
        val top = shadowMargin + if (caretOnTop) caretHeight else 0f
        val bottom = height - shadowMargin - if (caretOnTop) 0f else caretHeight
        body.set(left, top, right, bottom)
        val half = caretWidth / 2f
        val center = (caretCenter + left).coerceIn(left + radius + half, right - radius - half)
        caret.reset()
        if (caretOnTop) {
            caret.moveTo(center, top - caretHeight)
            caret.lineTo(center - half, top)
            caret.lineTo(center + half, top)
        } else {
            caret.moveTo(center, bottom + caretHeight)
            caret.lineTo(center - half, bottom)
            caret.lineTo(center + half, bottom)
        }
        caret.close()
        canvas.drawPath(caret, fill)
        canvas.drawRoundRect(body, radius, radius, shadow)
    }
}
