@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.celox.bpbel.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    /** 0..1 — read lazily inside the draw phase, so the ~43 Hz audio
     *  updates invalidate only the canvas, never this composition. */
    confidence: () -> Float,
    /** 0..1 loudness fraction — draw-phase read, same reasoning. */
    loudness: () -> Float,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    // Themed spring specs: the beat punch is a scale/shape change → Spatial
    // (overshoot wanted); the rim/glow flare is primarily an opacity flash →
    // Effects (no overshoot); the sonar ring is an expanding radius → slow
    // Spatial.
    val motion = MaterialTheme.motionScheme
    val pulseSpec = motion.defaultSpatialSpec<Float>()
    val flareSpec = motion.defaultEffectsSpec<Float>()
    val rippleSpec = motion.slowSpatialSpec<Float>()

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
    val reusableMatrix = remember { android.graphics.Matrix() }

    // Beat punch (scale overshoot) + rim/morph flare. Both suppressed under
    // reduce-motion (the orb then reflects level/confidence statically).
    val pulse = remember { Animatable(1f) }
    val flare = remember { Animatable(0f) }
    LaunchedEffect(beatTick) {
        if (beatTick == 0L || reduceMotion) return@LaunchedEffect
        pulse.snapTo(1.20f)
        pulse.animateTo(1f, pulseSpec)
    }
    LaunchedEffect(beatTick) {
        if (beatTick == 0L || reduceMotion) return@LaunchedEffect
        flare.snapTo(1f)
        flare.animateTo(0f, flareSpec)
    }

    // Sonar ripples — independent coroutines so a new beat doesn't
    // cancel the still-expanding previous ring.
    val scope = rememberCoroutineScope()
    val ripples = remember { List(RIPPLE_POOL) { Animatable(1f) } }
    val rippleIdx = remember { mutableIntStateOf(0) }
    LaunchedEffect(beatTick) {
        if (beatTick == 0L || reduceMotion) return@LaunchedEffect
        val i = rippleIdx.intValue
        rippleIdx.intValue = (i + 1) % RIPPLE_POOL
        val ring = ripples[i]
        scope.launch {
            ring.snapTo(0f)
            ring.animateTo(1f, rippleSpec)
        }
    }

    // Continuous ambient motion. Kept as State<Float> (no `by` delegate):
    // the values are read *inside* the Canvas draw lambda, so each 60 fps
    // animation frame invalidates only the draw phase — this composable
    // never recomposes for ambient motion.
    val infinite = rememberInfiniteTransition(label = "ambient")
    val spin = infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val arcSpin = infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "arc",
    )
    val breathe = infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    // Theme-static gradient color lists, hoisted out of the 60 fps draw
    // loop (the per-frame alpha-modulated ones must stay inside).
    val arcColors = remember(tertiary) {
        listOf(
            Color.Transparent,
            Color.Transparent,
            Color.Transparent,
            tertiary.copy(alpha = 0.0f),
            tertiary.copy(alpha = 0.9f),
            Color.White.copy(alpha = 0.7f),
            Color.Transparent,
        )
    }
    val orbColors = remember(primary, secondary, tertiary) {
        listOf(primary, tertiary, secondary, primary)
    }
    val coreColors = remember {
        listOf(
            Surface0.copy(alpha = 0.94f),
            Surface0.copy(alpha = 0.88f),
            Surface0.copy(alpha = 0.0f),
        )
    }

    Canvas(modifier = modifier) {
        // Ambient loops are infinite, so they stay duration-based (springs
        // can't loop). Under reduce-motion they freeze to a still frame.
        // Read here — in the draw phase — not in composition.
        val spinDeg = if (reduceMotion) 0f else spin.value
        val arcDeg = if (reduceMotion) 0f else arcSpin.value
        val breatheV = if (reduceMotion) 0f else breathe.value
        val conf = confidence()
        val loud = loudness()

        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)
        val baseR = min(size.width, size.height) / 2f * 0.58f
        val idleSwell = 1f + breatheV * 0.03f
        val r = baseR * pulse.value * (1f + loud * 0.10f) * idleSwell

        // 1) Sonar rings (behind everything).
        for (ring in ripples) {
            val p = ring.value
            if (p >= 1f) continue
            val ringR = r * (0.92f + p * 1.35f)
            val a = (1f - p) * (0.45f + conf * 0.3f)
            drawCircle(
                color = tertiary.copy(alpha = a),
                radius = ringR,
                center = center,
                style = Stroke(width = (1f - p) * 6f + 0.5f),
                blendMode = BlendMode.Plus,
            )
        }

        // 2) Outer bloom — breathing glow, brighter with confidence/beat.
        val bloomR = r * (1.9f + breatheV * 0.15f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = 0.16f + conf * 0.18f + flare.value * 0.15f),
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

        // 3) Orbiting comet-arc — decorative, so skipped under reduce-motion.
        if (!reduceMotion) {
            rotate(arcDeg, pivot = center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = arcColors,
                        center = center,
                    ),
                    radius = r * 1.16f,
                    center = center,
                    style = Stroke(width = 3f),
                    blendMode = BlendMode.Plus,
                )
            }
        }

        // Build the morph path once for this frame, scaled+centered.
        val progress = (0.05f + flare.value * 0.55f).coerceIn(0f, 1f)
        morph.toPath(progress, reusablePath)
        // setScale overwrites the whole matrix, so the remembered instance
        // needs no reset between frames.
        reusableMatrix.setScale(r, r)
        reusableMatrix.postTranslate(cx, cy)
        reusablePath.transform(reusableMatrix)
        val orb = reusablePath.asComposePath()

        rotate(spinDeg, pivot = center) {
            // 4) Liquid multi-hue surface.
            drawPath(
                path = orb,
                brush = Brush.sweepGradient(
                    colors = orbColors,
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
                colors = coreColors,
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
