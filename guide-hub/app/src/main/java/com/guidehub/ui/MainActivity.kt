package com.guidehub.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.guidehub.GuideHubApp
import com.guidehub.audio.Priority
import com.guidehub.auth.BiometricGate
import com.guidehub.core.GuideService
import com.guidehub.core.Permissions

/**
 * Single activity. Everything except [LockScreen] is behind the auth gate in [Root]; there are no
 * deep links and the notification only opens this activity, so nothing is reachable while locked.
 */
class MainActivity : FragmentActivity() {
    private val container get() = (application as GuideHubApp).container
    private lateinit var gate: BiometricGate
    private val authMessage = mutableStateOf<String?>(null)
    private val micGranted = mutableStateOf(false)
    private var promptedThisResume = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        micGranted.value = Permissions.hasMic(this)
        GuideService.start(this)
    }

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted.value = granted
        // Android stops showing the dialog after repeated denials: send the user to app settings.
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    /** Tap to Talk / "Allow microphone" when RECORD_AUDIO is not granted. */
    private fun requestMic() = micLauncher.launch(Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots and the recents thumbnail (FR-1).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        container.start()
        gate = BiometricGate(this, container.auth)

        setContent {
            GuideHubTheme {
                Root(
                    container = container, authMessage = authMessage.value, onUnlock = ::unlock,
                    micGranted = micGranted.value, onRequestMic = ::requestMic,
                )
            }
        }

        val missing = Permissions.missing(this)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else GuideService.start(this)
        if (container.auth.locked.value) container.audio.speak("Authentication required.", Priority.INFO)
    }

    override fun onResume() {
        super.onResume()
        micGranted.value = Permissions.hasMic(this) // may have changed in system settings
        if (container.auth.locked.value && !promptedThisResume) {
            promptedThisResume = true
            unlock()
        }
    }

    override fun onPause() {
        super.onPause()
        promptedThisResume = false
    }

    private fun unlock() {
        gate.unavailableReason()?.let { reason ->
            authMessage.value = reason
            container.audio.speak(reason, Priority.INFO)
            return
        }
        authMessage.value = null
        gate.prompt { err ->
            authMessage.value = err
            container.audio.speak(err, Priority.INFO)
        }
    }

    /** Volume-down = push-to-talk while the app is in the foreground and unlocked (configurable). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.repeatCount == 0 &&
            container.settings.settings.value.volumeKeyPtt && !container.auth.locked.value
        ) {
            container.orchestrator.pushToTalk()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
