package io.celox.bpbel.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 */
@Composable
fun BpmScreen(
    state: AudioUiState,
    permissionGranted: Boolean,
    permissionPermanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        AnimatedBackdrop()
        AnimatedContent(
            targetState = permissionGranted,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
            label = "phase",
        ) { granted ->
            if (granted) {
                ListeningContent(state)
            } else {
                PermissionContent(permissionPermanentlyDenied, onRequestPermission)
            }
        }
    }
}

@Composable
private fun ListeningContent(state: AudioUiState) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
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
                beatTick = state.beatTick,
                confidence = state.confidence.toFloat(),
                loudness = state.loudnessFraction,
                primary = scheme.primary,
                secondary = scheme.secondary,
                tertiary = scheme.tertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            BpmReadout(bpm = state.bpm)
        }

        ConfidenceBar(confidence = state.confidence.toFloat())

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
private fun BpmReadout(bpm: Double) {
    val shown = if (bpm <= 0.0) "—" else bpm.roundToInt().toString()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = shown,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            label = "bpm",
        ) { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = "BPM",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfidenceBar(confidence: Float) {
    Column(
        modifier = Modifier.fillMaxWidth(0.6f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            progress = { confidence },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Konfidenz ${(confidence * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LoudnessSection(state: AudioUiState) {
    val scheme = MaterialTheme.colorScheme
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
                    text = formatDbfs(state.displayDbfs, AudioUiState.DB_FLOOR),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.tertiary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(14.dp))
            DecibelMeter(
                level = state.loudnessFraction,
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

/** Slow, breathing radial gradient behind everything. */
@Composable
private fun AnimatedBackdrop() {
    val infinite = rememberInfiniteTransition(label = "backdrop")
    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "t",
    )
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        scheme.primary.copy(alpha = 0.10f + t * 0.06f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}
