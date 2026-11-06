// Copyright 2025, compose-coui-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Android View port of the COUI 1.1.0 switch geometry and state animation.
package com.utc.color.blockads.core.designsystem

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.appcompat.R as AppCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

class CouiSwitch(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackBounds = RectF()
    private val thumbBounds = RectF()
    private val trackClip = Path()
    private val easing = PathInterpolator(0.3f, 0f, 0.1f, 1f)
    private val density = resources.displayMetrics.density
    private val thumbSize = context.dp(18)
    private val thumbMargin = context.dp(3)
    private val checkedTrack = context.color(AppCompat.attr.colorPrimary)
    private val checkedThumb = context.color(com.google.android.material.R.attr.colorOnPrimary)
    private val uncheckedTrack = context.getColor(R.color.coui_switch_track_off)
    private val uncheckedThumb = context.color(R.attr.couiSwitchThumbOff)
    private val disabledUncheckedTrack = context.getColor(R.color.coui_switch_disabled_track_off)
    private val disabledUncheckedThumb = context.getColor(R.color.coui_switch_disabled_thumb_off)
    private val disabledCheckedThumb = context.color(R.attr.couiSwitchDisabledThumbOn)
    private val feedbackTone = context.getColor(R.color.coui_switch_feedback)
    private var checked = false
    private var progress = 0f
    private var stretch = 1f
    private var trackColor = uncheckedTrack
    private var thumbColor = uncheckedThumb
    private var feedbackAlpha = 0f
    private var toggleAnimator: ValueAnimator? = null
    private var feedbackAnimator: ValueAnimator? = null

    fun render(value: Boolean, enabled: Boolean) {
        val stateChanged = value != checked
        val enabledChanged = enabled != isEnabled
        if (!stateChanged && !enabledChanged) return
        checked = value
        isEnabled = enabled
        toggleAnimator?.cancel()

        val endTrack = when {
            checked && enabled -> checkedTrack
            checked -> ColorUtils.setAlphaComponent(checkedTrack, 77)
            enabled -> uncheckedTrack
            else -> disabledUncheckedTrack
        }
        val endThumb = when {
            checked && enabled -> checkedThumb
            checked -> disabledCheckedThumb
            enabled -> uncheckedThumb
            else -> disabledUncheckedThumb
        }
        val endPosition = if (checked) 1f else 0f
        if (!stateChanged || !isAttachedToWindow || !isShown) {
            progress = endPosition
            stretch = 1f
            trackColor = endTrack
            thumbColor = endThumb
            invalidate()
            return
        }

        val startTrack = trackColor
        val startThumb = thumbColor
        val startPosition = 1f - endPosition
        toggleAnimator = ValueAnimator.ofFloat(0f, 450f).apply {
            duration = 450
            interpolator = LinearInterpolator()
            addUpdateListener {
                val elapsed = it.animatedValue as Float
                val slide = eased(elapsed / 383f)
                progress = startPosition + (endPosition - startPosition) * slide
                // The thumb stretches backwards as it starts moving, then settles into a circle.
                stretch = 1f + 0.3f * if (elapsed <= 133f) eased(elapsed / 133f)
                    else 1f - eased((elapsed - 133f) / 250f)
                trackColor = ColorUtils.blendARGB(startTrack, endTrack, eased(elapsed / 450f))
                thumbColor = ColorUtils.blendARGB(startThumb, endThumb, slide)
                invalidate()
            }
            start()
        }
    }

    private fun eased(fraction: Float) = easing.getInterpolation(fraction.coerceIn(0f, 1f))

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        trackBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = height / 2f
        trackClip.reset()
        trackClip.addRoundRect(trackBounds, radius, radius, Path.Direction.CW)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val endAlpha = when {
            !isEnabled -> 0f
            isPressed -> 0.12f
            isHovered -> 0.078f
            else -> 0f
        }
        feedbackAnimator?.cancel()
        if (endAlpha == feedbackAlpha) return
        feedbackAnimator = ValueAnimator.ofFloat(feedbackAlpha, endAlpha).apply {
            duration = 120
            interpolator = easing
            addUpdateListener {
                feedbackAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        paint.color = trackColor
        canvas.drawRoundRect(trackBounds, radius, radius, paint)
        if (feedbackAlpha > 0f) {
            paint.color = ColorUtils.setAlphaComponent(feedbackTone, (feedbackAlpha * 255f).roundToInt())
            canvas.drawRoundRect(trackBounds, radius, radius, paint)
        }

        val stretched = (thumbSize * stretch).roundToInt()
        val resting = thumbMargin + ((width - thumbSize - 2 * thumbMargin) * progress).roundToInt()
        val left = if (checked) resting + thumbSize - stretched else resting
        val top = (height - thumbSize) / 2f
        thumbBounds.set(left.toFloat(), top, (left + stretched).toFloat(), top + thumbSize)

        val saved = canvas.save()
        canvas.clipPath(trackClip)
        if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, width / 2f, 0f)
        paint.color = thumbColor
        if (isEnabled) paint.setShadowLayer(2.29f * density, 0f, 1.14f * density, 0x19000000)
        canvas.drawRoundRect(thumbBounds, thumbSize / 2f, thumbSize / 2f, paint)
        paint.clearShadowLayer()
        canvas.restoreToCount(saved)
    }

    override fun onDetachedFromWindow() {
        toggleAnimator?.end()
        feedbackAnimator?.cancel()
        feedbackAlpha = 0f
        super.onDetachedFromWindow()
    }
}
