package io.celox.bpbel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.celox.bpbel.audio.AudioEngine
import io.celox.bpbel.ui.BpmScreen
import io.celox.bpbel.ui.theme.BpbelTheme

class MainActivity : ComponentActivity() {

    private val engine = AudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            BpbelTheme {
                val context = LocalContext.current
                val activity = this

                val state by engine.state.collectAsStateWithLifecycle()

                fun hasMicPermission() = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED

                var granted by remember { mutableStateOf(hasMicPermission()) }
                var permanentlyDenied by remember { mutableStateOf(false) }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { ok ->
                    granted = ok
                    permanentlyDenied = !ok && !ActivityCompat
                        .shouldShowRequestPermissionRationale(
                            activity, Manifest.permission.RECORD_AUDIO,
                        )
                }

                // Start/stop the audio engine with the visible lifecycle,
                // gated on permission. Re-runs when `granted` flips.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, granted) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> {
                                // Re-check on every return to the foreground:
                                // the user may have granted (or revoked) the
                                // mic permission in the system settings, and
                                // the launcher callback never sees that.
                                val now = hasMicPermission()
                                if (now != granted) {
                                    if (now) permanentlyDenied = false
                                    granted = now // re-keys this effect
                                } else if (granted) {
                                    engine.start()
                                }
                            }
                            Lifecycle.Event.ON_STOP -> engine.stop()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    if (granted &&
                        lifecycleOwner.lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                    ) {
                        engine.start()
                    }
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        engine.stop()
                    }
                }

                BpmScreen(
                    state = state,
                    permissionGranted = granted,
                    permissionPermanentlyDenied = permanentlyDenied,
                    onRequestPermission = {
                        if (permanentlyDenied) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null),
                                ),
                            )
                        } else {
                            launcher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
    }
}
