package com.utc.color.blockads.core.designsystem

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.max
import kotlin.math.roundToInt

private const val StartScale = 0.82f
private const val ExpandMillis = 320L
private const val FadeMillis = 200L

/**
 * Slot for a row prepended to a live list, like a new console line: the slot opens and pushes older
 * rows down while the card zooms in at its center. Keep the row spacing inside the slot (as the
 * child's margin) so the gap opens together with the card instead of jumping.
 */
class ListEntranceLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private var open = 1f
    private var expand: ValueAnimator? = null
    private var fade: ValueAnimator? = null
    private var zoom: SpringAnimation? = null
    private val card get() = getChildAt(0)
    private val startZoom = Runnable { zoom?.start() }

    init {
        clipChildren = true
    }

    /** Plays the entrance after [delayMillis]; rows of one burst pass increasing delays. */
    fun play(delayMillis: Long = 0) {
        val card = card ?: return
        reset()
        setOpen(0f)
        card.alpha = 0f
        card.scaleX = StartScale
        card.scaleY = StartScale
        expand = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ExpandMillis
            startDelay = delayMillis
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { setOpen(it.animatedValue as Float) }
            start()
        }
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FadeMillis
            startDelay = delayMillis
            interpolator = PathInterpolator(0f, 0f, 0.2f, 1f)
            addUpdateListener { card.alpha = it.animatedValue as Float }
            start()
        }
        val spring = SpringAnimation(card, DynamicAnimation.SCALE_X).apply {
            spring = SpringForce(1f).setDampingRatio(0.68f).setStiffness(400f)
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
            addUpdateListener { _, value, _ -> card.scaleY = value }
            addEndListener { _, _, _, _ -> card.scaleY = card.scaleX }
        }
        zoom = spring
        if (delayMillis > 0) postDelayed(startZoom, delayMillis) else spring.start()
    }

    /** Ends any entrance and shows the row fully open, e.g. when the holder is rebound or recycled. */
    fun reset() {
        removeCallbacks(startZoom)
        expand?.cancel()
        fade?.cancel()
        zoom?.cancel()
        expand = null
        fade = null
        zoom = null
        card?.apply {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }
        setOpen(1f)
    }

    private fun setOpen(value: Float) {
        if (open == value) return
        open = value
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (open < 1f) {
            // RecyclerView treats a zero-height first row as scrolled past; keep one pixel.
            setMeasuredDimension(measuredWidth, max(1, (measuredHeight * open).roundToInt()))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val card = card ?: return
        val params = card.layoutParams as LayoutParams
        val full = card.measuredHeight + params.topMargin + params.bottomMargin + paddingTop + paddingBottom
        // Center the full-size row in the opening slot; clipChildren keeps it inside.
        val y = (bottom - top - full) / 2 + paddingTop + params.topMargin
        val x = paddingLeft + params.leftMargin
        card.layout(x, y, x + card.measuredWidth, y + card.measuredHeight)
    }

    override fun onDetachedFromWindow() {
        reset()
        super.onDetachedFromWindow()
    }
}
