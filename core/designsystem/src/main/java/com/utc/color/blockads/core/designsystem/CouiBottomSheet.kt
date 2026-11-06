// Copyright 2025, compose-coui-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Android View port of the COUI 1.1.0 OverlayBottomSheet: panel slide spring, window dim,
// drag handle press feedback, drag/fling/nested-scroll dismissal and predictive back.
package com.utc.color.blockads.core.designsystem

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.ColorUtils
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Modal COUI panel. [dismiss] plays the exit slide before the window goes away, so dismiss
 * listeners run after the animation; [dismissNow] skips it for teardown.
 */
class CouiBottomSheet(context: Context, title: CharSequence?) :
    ComponentDialog(context, R.style.BlockAds_CouiSheetDialog) {

    private val layout = LayoutInflater.from(getContext()).inflate(R.layout.coui_bottom_sheet, FrameLayout(getContext()), false) as CouiSheetLayout
    private val content: FrameLayout = layout.findViewById(R.id.sheet_content)
    private var closing = false

    /** False keeps the sheet open on drag, outside tap and Back (drags are damped instead). */
    var allowDismiss: Boolean
        get() = layout.allowDismiss
        set(value) {
            layout.allowDismiss = value
        }

    init {
        layout.findViewById<TextView>(R.id.title).apply {
            text = title
            isVisible = !title.isNullOrEmpty()
        }
        setTitle(title)
        layout.onDismissRequest = { if (allowDismiss) dismiss() }
        super.setContentView(layout)
        window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val owner = context.activity()?.window
            val dark = getContext().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = owner?.let {
                    WindowInsetsControllerCompat(it, it.decorView).isAppearanceLightStatusBars
                } ?: !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) = layout.stopSettling()
            override fun handleOnBackProgressed(backEvent: BackEventCompat) = layout.backProgress(backEvent.progress)
            override fun handleOnBackCancelled() = layout.resetGesture()
            override fun handleOnBackPressed() {
                if (allowDismiss) layout.slideOffScreen(0f) else layout.resetGesture()
            }
        })
    }

    override fun setContentView(view: View) {
        content.removeAllViews()
        content.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun show() {
        closing = false
        layout.prepareEnter()
        super.show()
    }

    override fun dismiss() {
        if (!isShowing) return super.dismiss()
        if (closing) return
        closing = true
        layout.exit(::dismissNow)
    }

    fun dismissNow() {
        closing = true
        layout.stopAll()
        super.dismiss()
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}

/** Root of [CouiBottomSheet]: draws the dim and the overscroll fill, and moves the panel. */
class CouiSheetLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs), NestedScrollingParent3 {
    private val density = resources.displayMetrics.density
    private val dimColor = context.getColor(R.color.coui_window_dimming)
    private val surfacePaint = Paint().apply { color = context.color(com.google.android.material.R.attr.colorSurface) }
    private val parentHelper = NestedScrollingParentHelper(this)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val peekPx = 82 * density
    private val dismissDistancePx = 150 * density
    private val dismissVelocityPx = 800 * density
    private val sheet: LinearLayout by lazy { findViewById(R.id.sheet) }

    internal var onDismissRequest: () -> Unit = {}
    internal var allowDismiss = true

    private var progress = 0f
    private var dragOffset = 0f
    private var dimAlpha = 1f
    private var sheetHeight = 0
    private var pendingEnter = false
    private var topInset = 0
    private var startInset = 0
    private var endInset = 0
    private var imeLift = 0
    private var navigationBottom = 0
    private var imeAnimating = false

    private var progressSpring: SpringAnimation? = null
    private var offsetSpring: SpringAnimation? = null
    private var resetAnimator: ValueAnimator? = null
    private var settling = false
    private var outsideDown = false
    private var downX = 0f
    private var downY = 0f

    init {
        setWillNotDraw(false)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val top = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.captionBar() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            val sides = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            topInset = top.top
            startInset = sides.left
            endInset = sides.right
            // Old app padding: the panel content stays clear of the navigation bar plus one gap.
            sheet.updatePadding(bottom = navigationBottom + context.dp(8))
            if (!imeAnimating) imeLift = imeLift(insets)
            requestLayout()
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            this,
            object : WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimating = true
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    if (imeAnimating) {
                        imeLift = imeLift(insets)
                        requestLayout()
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        imeAnimating = false
                        ViewCompat.requestApplyInsets(this@CouiSheetLayout)
                    }
                }
            },
        )
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        attachDragHandle(findViewById(R.id.drag_handle), findViewById(R.id.drag_handle_press))
    }

    private fun imeLift(insets: WindowInsetsCompat) =
        max(0, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom - navigationBottom)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val available = (width - startInset - endInset).coerceAtLeast(0)
        val sheetWidth = min(available, panelMaxWidth(width / density, height / density))
        val maxHeight = (height - topInset - imeLift).coerceAtLeast(0)
        sheet.measure(
            MeasureSpec.makeMeasureSpec(sheetWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val sheetLeft = startInset + (width - startInset - endInset - sheet.measuredWidth) / 2
        val sheetBottom = height - imeLift
        sheet.layout(sheetLeft, sheetBottom - sheet.measuredHeight, sheetLeft + sheet.measuredWidth, sheetBottom)
        // Like COUI, keep the natural height while the IME is closed so dim and travel do not jump.
        if (imeLift == 0) sheetHeight = sheet.measuredHeight
        render()
        if (pendingEnter && sheetHeight > 0) {
            pendingEnter = false
            animateProgress(1f, entering = true)
        }
    }

    /** COUI grid: full width below 600dp, otherwise a 6-column span of the 8 or 12 column grid. */
    private fun panelMaxWidth(widthDp: Float, heightDp: Float): Int {
        if (widthDp < 600f) return Int.MAX_VALUE
        val eight = widthDp < 840f || heightDp < 480f || (heightDp >= 900f && heightDp > widthDp && widthDp < 960f)
        val dp = if (eight) (widthDp - 2f * 24f - 7f * 8f) / 8f * 6f + 5f * 8f
        else (widthDp - 2f * 40f - 11f * 12f) / 12f * 6f + 5f * 12f
        return (dp * density).roundToInt()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val alpha = (android.graphics.Color.alpha(dimColor) * dimAlpha * progress).roundToInt().coerceIn(0, 255)
        if (alpha > 0) canvas.drawColor(ColorUtils.setAlphaComponent(dimColor, alpha))
        if (dragOffset < 0f) {
            // Elastic fill that follows the panel up when it is pulled past its content.
            val bottom = sheet.bottom.toFloat()
            canvas.drawRect(sheet.left.toFloat(), bottom + sheet.translationY - 1, sheet.right.toFloat(), bottom, surfacePaint)
        }
        super.dispatchDraw(canvas)
    }

    private fun render() {
        val travel = if (sheetHeight > 0) sheetHeight.toFloat() else height.toFloat()
        sheet.translationY = travel * (1f - progress) + dragOffset
        invalidate()
    }

    internal fun prepareEnter() {
        stopAll()
        progress = 0f
        dragOffset = 0f
        dimAlpha = 1f
        pendingEnter = true
        if (isLaidOut && sheetHeight > 0) {
            pendingEnter = false
            render()
            animateProgress(1f, entering = true)
        }
    }

    internal fun exit(onEnd: () -> Unit) {
        pendingEnter = false
        stopSettling()
        if (dragOffset > 0f || !isLaidOut) {
            // Already dragged away by the user: finish without a second slide.
            progress = 0f
            onEnd()
        } else animateProgress(0f, entering = false, onEnd)
    }

    internal fun stopAll() {
        progressSpring?.cancel()
        progressSpring = null
        stopSettling()
    }

    internal fun stopSettling() {
        offsetSpring?.cancel()
        offsetSpring = null
        resetAnimator?.cancel()
        resetAnimator = null
        settling = false
    }

    private fun animateProgress(target: Float, entering: Boolean, onEnd: () -> Unit = {}) {
        progressSpring?.cancel()
        val travel = if (sheetHeight > 0) sheetHeight.toFloat() else height.toFloat()
        progressSpring = SpringAnimation(FloatValueHolder(progress)).apply {
            spring = slideSpring(target, entering, travel)
            setMinimumVisibleChange(0.0001f)
            addUpdateListener { _, value, _ ->
                progress = value
                render()
            }
            addEndListener { _, canceled, _, _ ->
                if (!canceled) {
                    progress = target
                    render()
                    onEnd()
                }
            }
            start()
        }
    }

    /** COUI panel spring: critically damped, response 0.25s..0.45s (0.4s exit) by travel fraction. */
    private fun slideSpring(target: Float, entering: Boolean, travelPx: Float): SpringForce {
        val base = if (entering) 0.45f else 0.4f
        val container = height.toFloat() - peekPx
        val fraction = if (container > 0f) ((travelPx - peekPx) / container).coerceIn(0f, 1f) else 1f
        return folmeSpring(target, damping = 1f, response = 0.25f + (base - 0.25f) * fraction)
    }

    private fun dimThreshold() = if (sheetHeight > 0) sheetHeight.toFloat() else 500f

    private fun updateDimAlpha(offset: Float) {
        dimAlpha = if (offset >= 0f && allowDismiss) 1f - (offset / dimThreshold()).coerceIn(0f, 1f) else 1f
    }

    private fun dampedOffset(current: Float, delta: Float): Float {
        val next = current + delta
        return when {
            next < 0f -> (current + delta * 0.1f).coerceAtMost(0f)
            !allowDismiss -> (current + if (delta > 0f) delta * 0.1f else delta).coerceAtLeast(0f)
            else -> next
        }
    }

    private fun dragTo(offset: Float) {
        dragOffset = offset
        updateDimAlpha(offset)
        render()
    }

    private fun settle(velocity: Float) {
        stopSettling()
        settling = true
        val dismiss = allowDismiss &&
            (velocity > dismissVelocityPx || (dragOffset > dismissDistancePx && velocity > -dismissVelocityPx))
        if (dismiss) {
            if (dragOffset >= height) {
                settling = false
                onDismissRequest()
            } else slideOffScreen(velocity)
            return
        }
        offsetSpring = SpringAnimation(FloatValueHolder(dragOffset)).apply {
            spring = folmeSpring(0f, damping = 0.85f, response = 0.4f)
            setStartVelocity(if (!allowDismiss && velocity > 0f) 0f else velocity)
            setMinimumVisibleChange(0.5f)
            addUpdateListener { _, value, _ -> dragTo(value) }
            addEndListener { _, canceled, _, _ ->
                if (!canceled) {
                    dragOffset = 0f
                    dimAlpha = 1f
                    render()
                }
                settling = false
            }
            start()
        }
    }

    /** Slides the panel down and dismisses as soon as it has left the viewport. */
    internal fun slideOffScreen(velocity: Float) {
        stopSettling()
        settling = true
        val travel = if (sheetHeight > 0) sheetHeight.toFloat() else height.toFloat()
        var left = false
        fun leave() {
            if (left) return
            left = true
            onDismissRequest()
        }
        offsetSpring = SpringAnimation(FloatValueHolder(dragOffset)).apply {
            spring = slideSpring(height.toFloat(), entering = false, travel)
            setStartVelocity(velocity)
            setMinimumVisibleChange(0.5f)
            addUpdateListener { animation, value, _ ->
                dragOffset = value
                dimAlpha = 1f - (value / dimThreshold()).coerceIn(0f, 1f)
                render()
                if (sheetHeight > 0 && value >= sheetHeight) {
                    animation.cancel()
                    leave()
                }
            }
            addEndListener { _, _, _, _ ->
                settling = false
                leave()
            }
            start()
        }
    }

    internal fun backProgress(fraction: Float) {
        stopSettling()
        val offset = fraction * dimThreshold()
        dragOffset = if (allowDismiss) offset else offset * 0.1f
        if (allowDismiss) dimAlpha = 1f - fraction
        render()
    }

    internal fun resetGesture() {
        stopSettling()
        val fromOffset = dragOffset
        val fromDim = dimAlpha
        resetAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                dragOffset = fromOffset * (1f - t)
                dimAlpha = fromDim + (1f - fromDim) * t
                render()
            }
            start()
        }
    }

    private fun attachDragHandle(handle: View, press: View) {
        val ease = PathInterpolator(0.33f, 0f, 0.67f, 1f)
        fun pressed(down: Boolean) =
            press.animate().alpha(if (down) 0.12f else 0f).setDuration(200).setInterpolator(ease).start()
        var tracker: VelocityTracker? = null
        var lastY = 0f
        var startY = 0f
        var dragging = false
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopSettling()
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    startY = event.rawY
                    lastY = event.rawY
                    dragging = false
                    pressed(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    if (!dragging && abs(event.rawY - startY) > touchSlop) {
                        dragging = true
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        lastY = event.rawY
                    }
                    if (dragging) dragTo(dampedOffset(dragOffset, event.rawY - lastY))
                    lastY = event.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val velocity = if (event.actionMasked == MotionEvent.ACTION_UP) tracker?.yVelocity ?: 0f else 0f
                    tracker?.recycle()
                    tracker = null
                    pressed(false)
                    if (dragging) settle(velocity)
                    else if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    dragging = false
                }
            }
            true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only touches outside the panel get here: the panel itself is clickable.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                outsideDown = true
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) outsideDown = false
            MotionEvent.ACTION_UP -> {
                if (outsideDown) performClick()
                outsideDown = false
            }
            MotionEvent.ACTION_CANCEL -> outsideDown = false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (allowDismiss) onDismissRequest()
        return true
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int) =
        axes and ViewCompat.SCROLL_AXIS_VERTICAL != 0

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        parentHelper.onNestedScrollAccepted(child, target, axes, type)
        if (type == ViewCompat.TYPE_TOUCH && settling) stopSettling()
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        // Content scrolling up first restores a panel that was pulled down.
        if (type != ViewCompat.TYPE_TOUCH || dy <= 0 || dragOffset <= 0f) return
        val next = dampedOffset(dragOffset, -dy.toFloat()).coerceAtLeast(0f)
        val used = (dragOffset - next).roundToInt()
        if (used != 0) {
            dragTo(next)
            consumed[1] = used
        }
    }

    override fun onNestedScroll(
        target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, type: Int, consumed: IntArray,
    ) {
        // Content already at its top: the rest of a downward drag moves the panel.
        if (type != ViewCompat.TYPE_TOUCH || dyUnconsumed >= 0 || !allowDismiss) return
        dragTo(dampedOffset(dragOffset, -dyUnconsumed.toFloat()))
        consumed[1] = dyUnconsumed
        if (dragOffset > height) settle(0f)
    }

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, type: Int) =
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, IntArray(2))

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
        if (dragOffset <= 0f || settling) return false
        settle(-velocityY)
        return true
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        parentHelper.onStopNestedScroll(target, type)
        if (type == ViewCompat.TYPE_TOUCH && !settling && dragOffset != 0f) settle(0f)
    }

    override fun getNestedScrollAxes() = parentHelper.nestedScrollAxes
}

/** COUI Folme spring: stiffness from the response period, as in `folmeSpring`. */
internal fun folmeSpring(target: Float, damping: Float, response: Float): SpringForce =
    SpringForce(target).setDampingRatio(damping).setStiffness(((2.0 * PI / response) * (2.0 * PI / response)).toFloat())
