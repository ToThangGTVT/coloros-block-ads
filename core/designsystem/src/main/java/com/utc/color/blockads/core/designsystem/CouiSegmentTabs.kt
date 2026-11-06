// Copyright 2025, compose-coui-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Android View port of the COUI 1.1.0 segment indicator and press feedback.
package com.utc.color.blockads.core.designsystem

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.utc.color.blockads.core.designsystem.databinding.SegmentTabsContentBinding
import kotlin.math.ceil
import kotlin.math.min

class CouiSegmentTabs(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val views = SegmentTabsContentBinding.inflate(LayoutInflater.from(context), this)
    private val labels = listOf(views.first, views.second)
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mediumTypeface }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorBounds = RectF()
    private val density = resources.displayMetrics.density
    private val selectedColor = context.getColor(R.color.brand_lime)
    private val unselectedColor = context.getColor(R.color.coui_segment_label)
    private val indicatorColor = context.getColor(R.color.brand_forest)
    private val shadowColor = context.getColor(R.color.coui_segment_shadow)
    private val textEasing = PathInterpolator(0.3f, 0f, 0.1f, 1f)
    private val pressEasing = PathInterpolator(0f, 0f, 0.1f, 1f)
    private val center = FloatValueHolder()
    private val indicatorWidth = FloatValueHolder()
    private val indicatorScale = FloatValueHolder(1f)
    private val centerSpring = spring(center) { invalidate() }
    private val widthSpring = spring(indicatorWidth) { invalidate() }
    private val indicatorScaleSpring = spring(indicatorScale) { invalidate() }
    private val labelSprings = labels.map { label ->
        val scale = FloatValueHolder(1f)
        spring(scale) { label.scaleX = scale.value; label.scaleY = scale.value }
    }
    private val textAnimators = arrayOfNulls<ValueAnimator>(2)
    private var selected = 0
    private var previousWidth = -1
    private var previousDirection = -1

    init {
        setWillNotDraw(false)
    }

    fun bind(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        require(titles.size == labels.size && selected in labels.indices)
        this.selected = selected
        labels.forEachIndexed { index, label ->
            label.text = titles[index]
            label.onPressedChange = { pressed ->
                val scale = if (pressed) pressedScale(label.width, label.height) else 1f
                labelSprings[index].animateToFinalPosition(scale)
                if (index == this.selected) {
                    val indicatorHeight = height - paddingTop - paddingBottom
                    indicatorScaleSpring.animateToFinalPosition(
                        if (pressed) pressedScale(label.width, indicatorHeight) else 1f,
                    )
                }
            }
            label.setOnClickListener {
                if (index != this.selected) {
                    this.selected = index
                    animateIndicator(label)
                    renderLabels(animate = true)
                }
                onSelect(index)
            }
        }
        renderLabels(animate = false)
        previousWidth = -1
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd).coerceAtLeast(0)
        val naturalWidths = labels.map { label ->
            measurePaint.textSize = label.textSize
            ceil(measurePaint.measureText(label.text.toString())).toInt() + label.paddingStart + label.paddingEnd
        }
        val first = naturalWidths[0]
        val second = naturalWidths[1]
        val half = available / 2
        // With two segments, the COUI width distribution only changes when one label needs more room.
        val firstWidth = when {
            first <= half && second > half -> available - min(second, available - first)
            second <= half && first > half -> min(first, available - second)
            else -> half
        }
        val maxWidth = context.dp(1000)
        labels[0].layoutParams.width = firstWidth.coerceAtMost(maxWidth)
        labels[1].layoutParams.width = (available - firstWidth).coerceAtMost(maxWidth)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (previousWidth != width || previousDirection != layoutDirection) {
            previousWidth = width
            previousDirection = layoutDirection
            val label = labels[selected]
            centerSpring.cancel()
            widthSpring.cancel()
            center.value = label.left + label.width / 2f
            indicatorWidth.value = label.width.toFloat()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val halfWidth = indicatorWidth.value * indicatorScale.value / 2f
        val halfHeight = (height - paddingTop - paddingBottom) * indicatorScale.value / 2f
        indicatorBounds.set(
            center.value - halfWidth, height / 2f - halfHeight,
            center.value + halfWidth, height / 2f + halfHeight,
        )
        paint.color = indicatorColor
        paint.setShadowLayer(density, 0f, 2f * density, shadowColor)
        canvas.drawRoundRect(indicatorBounds, halfHeight, halfHeight, paint)
        paint.clearShadowLayer()
    }

    private fun animateIndicator(label: TextView) {
        centerSpring.animateToFinalPosition(label.left + label.width / 2f)
        widthSpring.animateToFinalPosition(label.width.toFloat())
        indicatorScaleSpring.animateToFinalPosition(1f)
    }

    private fun renderLabels(animate: Boolean) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            label.isSelected = active
            label.typeface = if (active) mediumTypeface else Typeface.DEFAULT
            val color = if (active) selectedColor else unselectedColor
            textAnimators[index]?.cancel()
            if (animate) {
                textAnimators[index] = ValueAnimator.ofArgb(label.currentTextColor, color).apply {
                    duration = 300
                    interpolator = textEasing
                    addUpdateListener { label.setTextColor(it.animatedValue as Int) }
                    start()
                }
            } else {
                label.setTextColor(color)
            }
        }
    }

    private fun pressedScale(width: Int, height: Int): Float {
        val area = width.toFloat() * height / (density * density)
        val fraction = ((area - 48f * 48f) / (328f * 220f - 48f * 48f)).coerceIn(0f, 1f)
        return 0.92f + 0.06f * pressEasing.getInterpolation(fraction)
    }

    override fun onDetachedFromWindow() {
        centerSpring.cancel()
        widthSpring.cancel()
        indicatorScaleSpring.cancel()
        labelSprings.forEach { it.cancel() }
        textAnimators.forEach { it?.cancel() }
        super.onDetachedFromWindow()
    }

    private fun spring(value: FloatValueHolder, update: () -> Unit) =
        SpringAnimation(value).apply {
            spring = SpringForce().setDampingRatio(1f).setStiffness(438.65f)
            setMinimumVisibleChange(0.001f)
            addUpdateListener { _, _, _ -> update() }
        }
}

class CouiSegmentLabel(context: Context, attrs: AttributeSet?) : TextView(context, attrs) {
    var onPressedChange: ((Boolean) -> Unit)? = null

    override fun setPressed(pressed: Boolean) {
        if (isPressed == pressed) return
        super.setPressed(pressed)
        onPressedChange?.invoke(pressed)
    }
}
