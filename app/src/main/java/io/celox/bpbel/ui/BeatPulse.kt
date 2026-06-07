package io.celox.bpbel.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import io.celox.bpbel.ui.theme.Surface0
import kotlin.math.min
import kotlinx.coroutines.launch
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

private const val RIPPLE_POOL = 6

/**
 * The animated centerpiece — a glowing **orb with a dark core** so the
 * BPM numeral on top stays readable, wrapped in premium motion:
 *
 *  - **Sonar rings**: each detected beat spawns an expanding, fading
 *    ring (independent coroutines from a small pool, so overlapping
 *    pulses never cut each other off).
 *  - **Beat punch**: a low-damping spring scales the orb with a lively
 *    overshoot and flares its rim + morphs circle→star on the beat.
 *  - **Liquid sweep**: a multi-hue sweep-gradient slowly rotates for a
 *    living, metallic surface.
 *  - **Orbiting arc**: a bright comet-arc continuously circles the orb.
 *  - **Ambient breathing** when idle so it's never static.
 *
 * Pure Compose graphics; the [Morph] and reusable [android.graphics.Path]
 * are remembered so the hot path doesn't allocate.
 */
@Composable
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
            innerRadius = 0.82f,
            rounding = CornerRounding(0.24f),
        )
        Morph(circle, star)
    }
    val reusablePath = remember { android.graphics.Path() }

    // Beat punch (scale overshoot) + rim/morph flare.
    val pulse = remember { Animatable(1f) }
    val flare = remember { Animatable(0f) }
    LaunchedEffect(beatTick) {
        if (beatTick == 0L) return@LaunchedEffect
        pulse.snapTo(1.20f)
        pulse.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = Spring.StiffnessMedium))
    }
    LaunchedEffect(beatTick) {
        if (beatTick == 0L) return@LaunchedEffect
        flare.snapTo(1f)
        flare.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow))
    }

    // Sonar ripples — independent coroutines so a new beat doesn't
    // cancel the still-expanding previous ring.
    val scope = rememberCoroutineScope()
    val ripples = remember { List(RIPPLE_POOL) { Animatable(1f) } }
    val rippleIdx = remember { mutableIntStateOf(0) }
    LaunchedEffect(beatTick) {
        if (beatTick == 0L) return@LaunchedEffect
        val i = rippleIdx.intValue
        rippleIdx.intValue = (i + 1) % RIPPLE_POOL
        val ring = ripples[i]
        scope.launch {
            ring.snapTo(0f)
            ring.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
        }
    }

    // Continuous ambient motion.
    val infinite = rememberInfiniteTransition(label = "ambient")
    val spin by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val arcSpin by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "arc",
    )
    val breathe by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)
        val baseR = min(size.width, size.height) / 2f * 0.58f
        val idleSwell = 1f + breathe * 0.03f
        val r = baseR * pulse.value * (1f + loudness * 0.10f) * idleSwell

        // 1) Sonar rings (behind everything).
        for (ring in ripples) {
            val p = ring.value
            if (p >= 1f) continue
            val ringR = r * (0.92f + p * 1.35f)
            val a = (1f - p) * (0.45f + confidence * 0.3f)
            drawCircle(
                color = tertiary.copy(alpha = a),
                radius = ringR,
                center = center,
                style = Stroke(width = (1f - p) * 6f + 0.5f),
                blendMode = BlendMode.Plus,
            )
        }

        // 2) Outer bloom — breathing glow, brighter with confidence/beat.
        val bloomR = r * (1.9f + breathe * 0.15f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = 0.16f + confidence * 0.18f + flare.value * 0.15f),
                    tertiary.copy(alpha = 0.06f),
                    Color.Transparent,
                ),
                center = center,
                radius = bloomR,
            ),
            radius = bloomR,
            center = center,
            blendMode = BlendMode.Plus,
        )

        // 3) Orbiting comet-arc.
        rotate(arcSpin, pivot = center) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        Color.Transparent,
                        tertiary.copy(alpha = 0.0f),
                        tertiary.copy(alpha = 0.9f),
                        Color.White.copy(alpha = 0.7f),
                        Color.Transparent,
                    ),
                    center = center,
                ),
                radius = r * 1.16f,
                center = center,
                style = Stroke(width = 3f),
                blendMode = BlendMode.Plus,
            )
        }

        // Build the morph path once for this frame, scaled+centered.
        val progress = (0.05f + flare.value * 0.55f).coerceIn(0f, 1f)
        morph.toPath(progress, reusablePath)
        reusablePath.transform(
            android.graphics.Matrix().apply {
                setScale(r, r); postTranslate(cx, cy)
            },
        )
        val orb = reusablePath.asComposePath()

        rotate(spin, pivot = center) {
            // 4) Liquid multi-hue surface.
            drawPath(
                path = orb,
                brush = Brush.sweepGradient(
                    colors = listOf(primary, tertiary, secondary, primary),
                    center = center,
                ),
                style = Fill,
            )
        }

        // 5) Dark core — keeps the rim vivid but darkens the middle so
        //    the BPM numeral reads cleanly on top. Independent of the
        //    rotation so it stays put under the text.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Surface0.copy(alpha = 0.94f),
                    Surface0.copy(alpha = 0.88f),
                    Surface0.copy(alpha = 0.0f),
                ),
                center = center,
                radius = r * 0.92f,
            ),
            radius = r,
            center = center,
        )

        // 6) Bright rim that flares on the beat.
        drawPath(
            path = orb,
            color = Color.White.copy(alpha = 0.14f + flare.value * 0.55f),
            style = Stroke(width = 2f + flare.value * 5f),
            blendMode = BlendMode.Plus,
        )

        // 7) Subtle inner gradient ring to give the core depth.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    primary.copy(alpha = 0.10f + flare.value * 0.20f),
                    Color.Transparent,
                ),
                center = center,
                radius = r * 0.9f,
            ),
            radius = r * 0.9f,
            center = center,
            blendMode = BlendMode.Plus,
        )
    }
}
