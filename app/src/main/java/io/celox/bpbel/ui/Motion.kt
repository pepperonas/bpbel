package io.celox.bpbel.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * `true` when the user has asked the system to minimise motion — the
 * "Remove animations" accessibility setting / a developer animator
 * duration scale of 0. We read the global animator duration scale: `0`
 * means "no animations".
 *
 * Decorative, looping and overshooting motion (the orb's ambient spin,
 * sonar rings, beat bounce, breathing backdrop) is suppressed when this
 * is on, while the *information* — the BPM numeral and the dB meter —
 * keeps updating. Motion that communicates live data is not "animation"
 * in the reduce-motion sense, so it stays.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
