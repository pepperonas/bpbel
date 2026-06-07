package io.celox.bpbel.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.min
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/**
 * The animated heart of the app: a morphing blob that **pulses on every
 * detected beat** via a slightly-overshooting spring (the expressive
 * "punch"), morphs circle → star toward the beat, drifts continuously
 * when idle, and glows brighter with detection confidence.
 *
 * Rendering is pure Compose graphics. Per-frame work is just a path
 * rebuild + a few draws; the [Morph] and the reusable [android.graphics.Path]
 * are remembered so we don't allocate on the hot path.
 *
 * @param beatTick monotonic beat counter; a change triggers the pulse.
 * @param confidence 0..1 detection confidence; scales glow + saturation.
 * @param loudness 0..1 live loudness; adds a subtle baseline swell.
 */
@androidx.compose.runtime.Composable
fun BeatPulse(
    beatTick: Long,
    confidence: Float,
    loudness: Float,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    modifier: Modifier = Modifier,
) {
    val morph = remember {
        val circle = RoundedPolygon.circle(numVertices = 12, radius = 1f)
        val star = RoundedPolygon.star(
            numVerticesPerRadius = 8,
            radius = 1f,
            innerRadius = 0.78f,
            rounding = CornerRounding(0.22f),
        )
        Morph(circle, star)
    }
    val reusablePath = remember { android.graphics.Path() }

    // Spring-driven pulse. On each new beat: kick to a peak, then settle
    // to 1 with a low-damping spring → a lively overshoot ("expressive"
    // motion). Idle loudness adds a gentle baseline swell.
    val pulse = remember { Animatable(1f) }
    val morphAmount = remember { Animatable(0f) }
    LaunchedEffect(beatTick) {
        if (beatTick == 0L) return@LaunchedEffect
        pulse.snapTo(1.22f)
        pulse.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.34f,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }
    LaunchedEffect(beatTick) {
        if (beatTick == 0L) return@LaunchedEffect
        morphAmount.snapTo(1f)
        morphAmount.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = 0.6f,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    // Continuous ambient drift so the shape is never fully static.
    val infinite = rememberInfiniteTransition(label = "ambient")
    val rotation by infinite.animateRotation()
    val shimmer by infinite.animateShimmer()

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseR = min(size.width, size.height) / 2f * 0.62f
        val swell = 1f + loudness * 0.10f
        val r = baseR * pulse.value * swell

        // Outer glow rings — additive, fading with distance. Brighter
        // with confidence, breathing with loudness + shimmer.
        val glowAlpha = 0.10f + confidence * 0.30f + loudness * 0.12f
        for (i in 3 downTo 1) {
            val ringR = r * (1f + i * 0.16f * (0.85f + shimmer * 0.3f))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        tertiary.copy(alpha = glowAlpha / i),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = ringR,
                ),
                radius = ringR,
                center = Offset(cx, cy),
                blendMode = BlendMode.Plus,
            )
        }

        // The morphing blob itself. Morph progress = a small idle base
        // plus the per-beat burst, so it breathes circle↔star.
        val progress = (0.06f + morphAmount.value * 0.55f).coerceIn(0f, 1f)
        morph.toPath(progress, reusablePath)
        val matrix = android.graphics.Matrix().apply {
            setScale(r, r)
            postTranslate(cx, cy)
        }
        reusablePath.transform(matrix)
        val composePath = reusablePath.asComposePath()

        rotate(degrees = rotation, pivot = Offset(cx, cy)) {
            // Vibrant gradient fill.
            drawPath(
                path = composePath,
                brush = Brush.linearGradient(
                    colors = listOf(primary, secondary, tertiary),
                    start = Offset(cx - r, cy - r),
                    end = Offset(cx + r, cy + r),
                ),
                style = Fill,
            )
            // Bright rim that intensifies on the beat.
            drawPath(
                path = composePath,
                color = Color.White.copy(alpha = 0.18f + morphAmount.value * 0.5f),
                style = Stroke(width = 2.5f + morphAmount.value * 4f),
                blendMode = BlendMode.Plus,
            )
        }

        // Inner core highlight.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(cx - r * 0.18f, cy - r * 0.18f),
                radius = r * 0.7f,
            ),
            radius = r * 0.7f,
            center = Offset(cx, cy),
            blendMode = BlendMode.Plus,
        )
    }
}

@androidx.compose.runtime.Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateRotation() =
    animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

@androidx.compose.runtime.Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateShimmer() =
    animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer",
    )
