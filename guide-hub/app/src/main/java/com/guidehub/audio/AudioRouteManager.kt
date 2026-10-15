package com.guidehub.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.guidehub.core.AudioRoutePref
import com.guidehub.core.Clock
import com.guidehub.core.Constants
import com.guidehub.core.EventBus
import com.guidehub.core.LinkId
import com.guidehub.core.LinkState
import com.guidehub.core.LinkStatus
import com.guidehub.core.LogEvent
import com.guidehub.core.MicRouting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Earbud vs phone audio routing (FR-2, PRD 4.1). Output follows Android's normal routing (A2DP to
 * the earbuds when connected). Input: the earbud mic is opened explicitly only for the push-to-talk
 * window (setCommunicationDevice on API 31+, SCO on API 29-30), the active route is verified, and
 * the route returns to A2DP afterwards. A settle delay keeps earcons clear of the HFP switch.
 */
class AudioRouteManager(
    private val context: Context,
    private val bus: EventBus,
    private val clock: Clock,
    private val pref: () -> AudioRoutePref,
) : MicRouting {
    private val am = context.getSystemService(AudioManager::class.java)
    private val _earbuds = MutableStateFlow(false)
    /** Bluetooth earbuds available for output (stereo hazard cues). */
    val earbudsConnected: StateFlow<Boolean> = _earbuds.asStateFlow()
    private val _activeMic = MutableStateFlow("none")
    val activeMic: StateFlow<String> = _activeMic.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun stop() = am.unregisterAudioDeviceCallback(callback)

    private fun isBtOutput(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        (Build.VERSION.SDK_INT >= 31 && (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))

    private fun refresh() {
        val connected = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any(::isBtOutput)
        if (connected != _earbuds.value) {
            _earbuds.value = connected
            bus.emit(LinkStatus(LinkId.EARBUDS, if (connected) LinkState.CONNECTED else LinkState.DISCONNECTED, "", clock.now()))
        }
    }

    override suspend fun openMic(): String {
        val route = if (pref() == AudioRoutePref.EARBUDS_PREFERRED && _earbuds.value) openEarbudMic() else null
        val name = route ?: "phone"
        _activeMic.value = name
        bus.emit(LogEvent("audio", "Mic route: $name", clock.now()))
        delay(Constants.MIC_ROUTE_SETTLE_MS)
        return name
    }

    private suspend fun openEarbudMic(): String? {
        if (Build.VERSION.SDK_INT >= 31) {
            val dev = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: return null
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!am.setCommunicationDevice(dev)) return null
            // Verify rather than assume the route.
            val active = am.communicationDevice
            return if (active?.id == dev.id) "earbuds (${if (dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET) "LE Audio" else "HFP"})" else null
        } else {
            val connected = CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> connected.complete(true)
                        AudioManager.SCO_AUDIO_STATE_ERROR -> connected.complete(false)
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                val ok = withTimeoutOrNull(3_000) { connected.await() } ?: false
                @Suppress("DEPRECATION")
                return if (ok && am.isBluetoothScoOn) "earbuds (SCO)" else null
            } finally {
                context.unregisterReceiver(receiver)
            }
        }
    }

    override suspend fun closeMic() {
        if (_activeMic.value.startsWith("earbuds")) {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
            am.mode = AudioManager.MODE_NORMAL
            delay(Constants.MIC_ROUTE_SETTLE_MS) // let earbuds return to A2DP before speech/earcons
        }
        _activeMic.value = "none"
    }
}
