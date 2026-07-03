@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.celox.bpbel.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.celox.bpbel.audio.AudioUiState
import kotlin.math.roundToInt

/**
 * Single-screen UI. Two states:
 *  - **permission needed** → an invitation card with a request button.
 *  - **listening** → the live BPM + dB visualizer.
 *
 * Takes the audio state as [State] (not as a value): the flow emits
 * ~43×/s, and reading the object here would recompose the whole screen
 * on every audio frame. Instead the continuous values (loudness, level,
 * confidence) are read lazily in the children's *draw* lambdas, and the
 * few composition-affecting values (beat tick, display texts) are
 * derived+quantised so composition only runs when something visible
 * actually changes (~beat/second rate).
 */
@Composable
fun BpmScreen(
    state: State<AudioUiState>,
    permissionGranted: Boolean,
    permissionPermanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
) {
    // Phase cross-fade is a pure opacity change → Effects spec (highly
    // damped, no overshoot), per the M3 motion token rules.
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val reduceMotion = rememberReduceMotion()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        AnimatedBackdrop(reduceMotion)
        AnimatedContent(
            targetState = permissionGranted,
            transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
            label = "phase",
        ) { granted ->
            if (granted) {
                ListeningContent(state, reduceMotion)
            } else {
                PermissionContent(permissionPermanentlyDenied, onRequestPermission)
            }
        }
    }
}

@Composable
private fun ListeningContent(state: State<AudioUiState>, reduceMotion: Boolean) {
    val scheme = MaterialTheme.colorScheme

    // Composition-affecting reads, quantised: beatTick changes per beat
    // (~2/s), the BPM count-up target only per 0.1 BPM — everything else
    // in this tree reads the state lazily in the draw phase.
    val beatTick by remember { derivedStateOf { state.value.beatTick } }
    val bpm by remember {
        derivedStateOf { (state.value.bpm * 10).roundToInt() / 10.0 }
    }

    // Measurement tool: keep the display awake while actively listening.
    // View.keepScreenOn scopes itself to this composable's lifetime.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Edge-to-edge is enabled, so keep the header out of the status
            // bar / camera cutout and the card clear of the gesture bar.
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header()

        Spacer(Modifier.height(8.dp))

        // Pulse visualizer with the BPM numeral centered on top.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            BeatPulse(
                beatTick = beatTick,
                confidence = { state.value.confidence.toFloat() },
                loudness = { state.value.loudnessFraction },
                primary = scheme.primary,
                secondary = scheme.secondary,
                tertiary = scheme.tertiary,
                reduceMotion = reduceMotion,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            BpmReadout(bpm = bpm, beatTick = beatTick, reduceMotion = reduceMotion)
        }

        ConfidenceBar(state)

        Spacer(Modifier.height(28.dp))

        LoudnessSection(state)
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "bpbel",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun BpmReadout(bpm: Double, beatTick: Long, reduceMotion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // Count-up toward the target tempo. A settling *number* must not
    // overshoot (an overshoot would momentarily display a wrong BPM), so
    // it deliberately takes the highly-damped Effects timing rather than a
    // bouncy Spatial spec — Effects is M3's no-overshoot settle character.
    val animated by animateFloatAsState(
        targetValue = bpm.toFloat(),
        animationSpec = motion.defaultEffectsSpec(),
        label = "bpmCount",
    )
    val shown = if (bpm <= 0.0) "—" else animated.roundToInt().toString()

    // Per-beat scale bounce — a small element changing size → fast Spatial
    // spec (lively overshoot is desired here). Suppressed under reduce-motion.
    val bounce = remember { Animatable(1f) }
    val bounceSpec = motion.fastSpatialSpec<Float>()
    LaunchedEffect(beatTick) {
        if (beatTick == 0L || reduceMotion) return@LaunchedEffect
        bounce.snapTo(1.07f)
        bounce.animateTo(1f, bounceSpec)
    }

    val label = if (bpm <= 0.0) "Kein Tempo erkannt" else "Tempo ${animated.roundToInt()} BPM"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer {
                scaleX = bounce.value
                scaleY = bounce.value
            }
            // One spoken label for the whole readout instead of "120" + "BPM".
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        // Bright white numeral with a neon glow — reads cleanly on the
        // orb's dark core, and the glow gives it premium depth.
        Text(
            text = shown,
            style = MaterialTheme.typography.displayLarge.copy(
                color = Color.White,
                shadow = Shadow(
                    color = scheme.primary.copy(alpha = 0.85f),
                    offset = Offset.Zero,
                    blurRadius = 38f,
                ),
            ),
            textAlign = TextAlign.Center,
        )
        Text(
            text = "BPM",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.tertiary,
        )
    }
}

@Composable
private fun ConfidenceBar(state: State<AudioUiState>) {
    // The bar itself reads in the draw phase (progress lambda); only the
    // rounded percent text touches composition, at ~1 %-step granularity.
    val percent by remember {
        derivedStateOf { (state.value.confidence * 100).roundToInt() }
    }
    Column(
        modifier = Modifier.fillMaxWidth(0.6f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            progress = { state.value.confidence.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Konfidenz $percent%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LoudnessSection(state: State<AudioUiState>) {
    val scheme = MaterialTheme.colorScheme
    // The numeric readout is heavily smoothed and displayed as a whole
    // dB — the derived string changes ~1×/s, not 43×/s.
    val dbText by remember {
        derivedStateOf { formatDbfs(state.value.displayDbfs, AudioUiState.DB_FLOOR) }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "Lautstärke",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
                Text(
                    text = dbText,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.tertiary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(14.dp))
            DecibelMeter(
                level = { state.value.loudnessFraction },
                primary = scheme.primary,
                secondary = scheme.secondary,
                tertiary = scheme.tertiary,
                track = scheme.surfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionContent(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "bpbel",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (permanentlyDenied) {
                "Mikrofon-Zugriff ist deaktiviert. Aktiviere ihn in den " +
                    "System-Einstellungen, um BPM und Dezibel zu messen."
            } else {
                "bpbel hört die Musik in deiner Umgebung, erkennt das Tempo " +
                    "(BPM) und zeigt die aktuelle Lautstärke in Dezibel."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRequestPermission) {
            Text(if (permanentlyDenied) "Einstellungen öffnen" else "Mikrofon erlauben")
        }
    }
}

/** Slow, breathing radial gradient behind everything. An infinite ambient
 *  loop, so it legitimately stays duration-based (springs can't drive an
 *  infinite animation). Frozen to a mid value under reduce-motion.
 *
 *  The animated value is read inside [drawBehind], so each animation
 *  frame only re-draws — this composable composes exactly once. */
@Composable
private fun AnimatedBackdrop(reduceMotion: Boolean) {
    val infinite = rememberInfiniteTransition(label = "backdrop")
    val tAnim = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "t",
    )
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val t = if (reduceMotion) 0.5f else tAnim.value
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(
                            primary.copy(alpha = 0.10f + t * 0.06f),
                            Color.Transparent,
                        ),
                    ),
                )
            },
    )
}
