package com.utc.color.blockads.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val EntranceStartScale = 0.82f
private const val EntranceExpandMillis = 320
private const val EntranceFadeMillis = 200

/** Animation for a row that is prepended to a live list, like a new console line. */
@Stable
class AppListEntrance internal constructor(private val delayMillis: Int) {
    internal val height = Animatable(0f)
    internal val scale = Animatable(EntranceStartScale)
    internal val alpha = Animatable(0f)

    internal suspend fun play() = coroutineScope {
        if (delayMillis > 0) delay(delayMillis.toLong())
        // The slot opens and pushes older rows down while the card zooms into it.
        launch { height.animateTo(1f, tween(EntranceExpandMillis, easing = FastOutSlowInEasing)) }
        launch { alpha.animateTo(1f, tween(EntranceFadeMillis, easing = LinearOutSlowInEasing)) }
        scale.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow))
    }
}

/**
 * Returns an entrance for a newly arrived row, or null when [enabled] is false. Rows created in one
 * batch can pass increasing [delayMillis] so the oldest arrival opens first.
 */
@Composable
fun rememberAppListEntrance(key: Any, enabled: Boolean, delayMillis: Int = 0): AppListEntrance? {
    val entrance = remember(key) { if (enabled) AppListEntrance(delayMillis) else null }
    if (entrance != null) LaunchedEffect(entrance) { entrance.play() }
    return entrance
}

/**
 * Grows the row's slot from zero height and zooms the content in at its center. Apply it outside
 * the row spacing so the gap opens together with the card instead of jumping.
 */
fun Modifier.appListEntrance(entrance: AppListEntrance?): Modifier =
    if (entrance == null) this
    else this
        .graphicsLayer {
            val scale = entrance.scale.value
            scaleX = scale
            scaleY = scale
            alpha = entrance.alpha.value
            transformOrigin = TransformOrigin.Center
            // Keep the zooming card inside its opening slot.
            clip = true
            shape = RectangleShape
        }
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            // Lazy lists treat a zero-height first row as scrolled past and re-anchor to the row
            // below it, so keep at least one pixel while the slot is closed.
            val height = (placeable.height * entrance.height.value).roundToInt().coerceAtLeast(1)
            layout(placeable.width, height) { placeable.placeRelative(0, (height - placeable.height) / 2) }
        }
