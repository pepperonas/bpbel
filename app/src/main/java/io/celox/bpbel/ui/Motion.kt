package io.celox.bpbel.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * `true` when the user has asked the system to minimise motion — the
 * "Remove animations" accessibility setting / a developer animator
 * duration scale of 0. We read the global animator duration scale: `0`
 * means "no animations". A [ContentObserver] keeps the value live, so
 * toggling the setting takes effect without restarting the app.
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
    var reduce by remember { mutableStateOf(readReduceMotion(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = readReduceMotion(context)
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduce
}

private fun readReduceMotion(context: Context): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
