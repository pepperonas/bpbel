package io.celox.bpbel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Horizontal segmented loudness meter (dBFS).
 *
 * The live level is held in a plain mutable float and **manually lerped**
 * on every display frame rather than routed through `animateFloatAsState`
 * — the raw audio signal updates ~43×/s and an animation spec would lag
 * it. We smooth asymmetrically: fast attack (follow transients up) + slow
 * release (graceful decay), the classic VU-meter feel. A separate
 * peak-hold tick falls back slowly.
 *
 * The loop runs on `withFrameNanos` with **time-based** coefficients, so
 * the decay always plays out — even when the incoming level pins to a
 * constant (e.g. exactly 0 in a quiet room), which would stall a
 * smoothing step keyed on level *changes*.
 *
 * @param level 0..1 loudness fraction (see [io.celox.bpbel.audio.AudioUiState.loudnessFraction]),
 *   as a lambda: it is only invoked inside the per-frame smoothing loop,
 *   so the ~43 Hz audio updates never recompose this composable.
 */
@Composable
fun DecibelMeter(
    level: () -> Float,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(level) {
        // Per-second one-pole rates matching the previous per-emission
        // constants at 43 fps (attack 0.55/frame ≈ 34/s, release
        // 0.12/frame ≈ 5.5/s, peak fall 0.012/frame ≈ 0.5/s).
        val attackPerS = 34f
        val releasePerS = 5.5f
        val peakFallPerS = 0.5f
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) {
                    1f / 60f
                } else {
                    ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
                }
                lastNanos = now
                val t = level()
                val rate = if (t > shown) attackPerS else releasePerS
                val k = 1f - exp(-rate * dt)
                val newShown = shown + (t - shown) * k
                val newPeak = if (t >= peak) t else max(t, peak - peakFallPerS * dt)
                // Only write when visibly different, so a fully-settled
                // meter stops invalidating the canvas every frame.
                if (abs(newShown - shown) > 1e-4f) shown = newShown
                if (abs(newPeak - peak) > 1e-4f) peak = newPeak
            }
        }
    }

    // Hoisted out of the draw loop — theme-static. The default
    // horizontal-gradient bounds (0 → width) resolve at draw time, so
    // this is identical to building it per frame with explicit endX.
    val fill = remember(primary, secondary, tertiary) {
        Brush.horizontalGradient(colors = listOf(tertiary, primary, secondary))
    }

    Box(modifier = modifier.fillMaxWidth().height(34.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
            val segGap = 4f
            val segCount = 28
            val segW = (size.width - segGap * (segCount - 1)) / segCount
            val activeCount = (shown * segCount).toInt()
            val peakIndex = (peak * segCount).toInt().coerceIn(0, segCount - 1)

            for (i in 0 until segCount) {
                val x = i * (segW + segGap)
                val on = i < activeCount
                val isPeak = i == peakIndex
                drawRoundRectSeg(
                    x = x,
                    width = segW,
                    height = size.height,
                    on = on || isPeak,
                    fill = fill,
                    track = track,
                    emphasizePeak = isPeak,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectSeg(
    x: Float,
    width: Float,
    height: Float,
    on: Boolean,
    fill: Brush,
    track: Color,
    emphasizePeak: Boolean,
) {
    val corner = androidx.compose.ui.geometry.CornerRadius(width / 2.5f, width / 2.5f)
    if (on) {
        drawRoundRect(
            brush = fill,
            topLeft = Offset(x, 0f),
            size = Size(width, height),
            cornerRadius = corner,
        )
        if (emphasizePeak) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.6f),
                topLeft = Offset(x, 0f),
                size = Size(width, height),
                cornerRadius = corner,
            )
        }
    } else {
        drawRoundRect(
            color = track,
            topLeft = Offset(x, height * 0.32f),
            size = Size(width, height * 0.36f),
            cornerRadius = corner,
        )
    }
}

/** Format a dBFS value for display, e.g. "-18 dB" or "-∞". */
fun formatDbfs(dbfs: Double, floor: Double): String =
    if (abs(dbfs - floor) < 0.5) "-∞ dB" else "${dbfs.toInt()} dB"
