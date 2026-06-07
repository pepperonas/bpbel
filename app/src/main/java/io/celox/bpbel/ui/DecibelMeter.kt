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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/**
 * Horizontal segmented loudness meter (dBFS).
 *
 * The live level is held in a plain mutable float and **manually lerped**
 * each frame rather than routed through `animateFloatAsState` — the raw
 * audio signal updates ~43×/s and an animation spec would lag it. We
 * smooth asymmetrically: fast attack (follow transients up) + slow
 * release (graceful decay), the classic VU-meter feel. A separate
 * peak-hold tick falls back slowly.
 *
 * @param level 0..1 loudness fraction (see [io.celox.bpbel.audio.AudioUiState.loudnessFraction]).
 * @param dbfsLabel formatted dB text shown at the end.
 */
@Composable
fun DecibelMeter(
    level: Float,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }

    // Drive the smoothing off the incoming level. Re-launches whenever
    // `level` changes (i.e. every audio frame), advancing one smoothing
    // step toward the target.
    LaunchedEffect(level) {
        val target = level
        // Asymmetric one-pole smoothing.
        val attack = 0.55f
        val release = 0.12f
        shown += (target - shown) * if (target > shown) attack else release
        peak = if (target >= peak) target else max(target, peak - 0.012f)
    }

    Box(modifier = modifier.fillMaxWidth().height(34.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
            val segGap = 4f
            val segCount = 28
            val segW = (size.width - segGap * (segCount - 1)) / segCount
            val activeCount = (shown * segCount).toInt()
            val peakIndex = (peak * segCount).toInt().coerceIn(0, segCount - 1)

            val fill = Brush.horizontalGradient(
                colors = listOf(tertiary, primary, secondary),
                startX = 0f,
                endX = size.width,
            )

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
