package com.guidehub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guidehub.audio.TtsVoiceStatus
import com.guidehub.core.AppContainer
import com.guidehub.core.AppSettings
import com.guidehub.core.AudioRoutePref
import com.guidehub.core.Constants
import com.guidehub.core.PttButton
import com.guidehub.core.SttBackend
import kotlin.math.roundToInt

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
private fun metres(v: Float) = "%.1f m".format(v)

@Composable
fun SettingsScreen(container: AppContainer, micGranted: Boolean, onRequestMic: () -> Unit) {
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val voice by container.audio.voiceStatus.collectAsStateWithLifecycle()
    SettingsContent(
        s, voice, container.sttDescription(), container.availableMaps(), micGranted,
        SettingsActions(
            update = { container.settings.update(it) },
            requestMic = onRequestMic,
            loadMap = { container.loadMap(it) },
            restartVision = { container.restartVision() },
            lock = { container.auth.lock() },
        ),
    )
}

class SettingsActions(
    val update: ((AppSettings) -> AppSettings) -> Unit,
    val requestMic: () -> Unit,
    val loadMap: (String) -> Unit,
    val restartVision: () -> Unit,
    val lock: () -> Unit,
)

/** Stateless settings body; [sttInUse] describes the recogniser Tap to Talk will use. */
@Composable
fun SettingsContent(s: AppSettings, voice: TtsVoiceStatus, sttInUse: String, maps: List<String>, micGranted: Boolean, a: SettingsActions) {
    fun update(t: (AppSettings) -> AppSettings) = a.update(t)
    val onRequestMic = a.requestMic

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        // ---------------- 1. Audio & Speech ----------------
        SectionCard(title = "Audio & Speech", subtitle = "How Guide Hub sounds and where audio goes.") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Spoken voice", style = MaterialTheme.typography.bodyLarge, color = Palette.Ink, modifier = Modifier.weight(1f))
                    when {
                        !voice.ready -> StatusPill("Starting", Tone.Neutral)
                        voice.requestedActive -> StatusPill("${voice.requested} active", Tone.Success)
                        voice.requested.isNotBlank() -> StatusPill("Fallback voice", Tone.Warning)
                        else -> StatusPill("Local voice", Tone.Neutral)
                    }
                }
                Text(voice.note, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
            }
            TextSetting(
                "Preferred voice name", "Used only if an on-device voice or engine with this name is installed. Leave blank for the best local voice.",
                s.preferredVoice, placeholder = Constants.PREFERRED_VOICE,
            ) { v -> update { it.copy(preferredVoice = v.trim()) } }
            SliderSetting(
                "Speech rate", "How fast instructions are spoken. 1.00× is the engine's normal pace.",
                s.ttsRate, Constants.TTS_RATE_MIN..Constants.TTS_RATE_MAX, { "%.2f×".format(it) }, steps = 11,
            ) { v -> update { it.copy(ttsRate = (v * 20).roundToInt() / 20f) } }
            SliderSetting("Speech volume", "Loudness of spoken instructions, relative to the accessibility volume.", s.ttsVolume, 0.1f..1.0f, ::pct, steps = 8) { v ->
                update { it.copy(ttsVolume = (v * 10).roundToInt() / 10f) }
            }
            SliderSetting(
                "Hazard beep volume", "Loudness of obstacle beeps. Kept moderate so surrounding sounds stay audible.",
                s.hazardVolume, 0.1f..0.8f, ::pct, steps = 6,
            ) { v -> update { it.copy(hazardVolume = (v * 10).roundToInt() / 10f) } }
            ChoiceSetting(
                "Audio route", "Bluetooth earbuds give stereo hazard direction; the phone speaker is the fallback.",
                listOf(AudioRoutePref.EARBUDS_PREFERRED to "Earbuds when connected", AudioRoutePref.PHONE_ONLY to "Phone speaker & mic only"),
                s.audioRoute,
            ) { v -> update { it.copy(audioRoute = v) } }
            HorizontalDivider(color = Palette.Outline)
            ChoiceSetting(
                "Speech recognition", "Android uses the phone's recogniser (offline preferred). Vosk needs a model copied to the phone (docs/STT.md).",
                listOf(SttBackend.ANDROID to "Android recogniser", SttBackend.VOSK to "Vosk (offline model)"),
                s.sttBackend,
            ) { v -> update { it.copy(sttBackend = v) } }
            InfoRow("In use", sttInUse)
            InfoRow("Microphone", if (micGranted) "Allowed" else "Not allowed", if (micGranted) Palette.Green else Palette.Red)
            if (!micGranted) Button(onClick = onRequestMic, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow microphone") }
        }

        // ---------------- 2. Voice & Controls ----------------
        SectionCard(title = "Voice & Controls", subtitle = "How you start talking and how the app answers.") {
            ChoiceSetting(
                "Earbud talk button", "Which earbud press starts listening. Earbuds map taps differently; try each if one does nothing.",
                listOf(
                    PttButton.PLAY_PAUSE to "Play/pause (single tap)",
                    PttButton.HEADSET_HOOK to "Call button",
                    PttButton.NEXT to "Next track (often double tap)",
                    PttButton.PREVIOUS to "Previous track (often triple tap)",
                ),
                s.pttButton,
            ) { v -> update { it.copy(pttButton = v) } }
            SwitchSetting("Volume-down key starts talking", "Only while Guide Hub is open and unlocked.", s.volumeKeyPtt) { v -> update { it.copy(volumeKeyPtt = v) } }
            SwitchSetting("Listen again after a question", "After “Did you mean…?” the mic reopens so you can answer without pressing.", s.autoRelisten) { v -> update { it.copy(autoRelisten = v) } }
            SwitchSetting("Confirm before starting", "Ask “Lab 204, about 26 metres. Say yes to start” before guiding.", s.confirmDestination) { v -> update { it.copy(confirmDestination = v) } }
        }

        // ---------------- 3. Navigation ----------------
        SectionCard(title = "Navigation", subtitle = "Building map and walking settings.") {
            ChoiceSetting("Building map", "Maps are JSON files in the app's assets.", maps.map { it to it.removeSuffix(".json") }, s.mapAsset) { v ->
                update { it.copy(mapAsset = v) }; a.loadMap(v)
            }
            SliderSetting(
                "Step length", "Reserved for speaking distances in steps (not used yet). Never used for positioning.",
                s.stepLengthM.toFloat(), 0.4f..1.0f, { "%.2f m".format(it) }, steps = 11,
            ) { v -> update { it.copy(stepLengthM = ((v * 20).roundToInt() / 20.0)) } }
        }

        // ---------------- 4. Hazard Alerts ----------------
        SectionCard(title = "Hazard Alerts", subtitle = "Distances at which obstacles trigger each warning level. Obstacles at head or chest height are raised one level.") {
            SliderSetting("Critical (urgent beeps + voice)", "Closer than this: fastest beeps and a spoken warning that interrupts other speech.", s.criticalM.toFloat(), 0.5f..1.5f, ::metres, steps = 9) { v ->
                val c = (v * 10).roundToInt() / 10.0
                update { it.copy(criticalM = c, warningM = maxOf(it.warningM, c + 0.2), infoM = maxOf(it.infoM, c + 0.4)) }
            }
            SliderSetting("Warning", "Closer than this: steady beeps and a short spoken warning for recognised objects.", s.warningM.toFloat(), 1.0f..3.0f, ::metres, steps = 19) { v ->
                val w = maxOf((v * 10).roundToInt() / 10.0, s.criticalM + 0.2)
                update { it.copy(warningM = w, infoM = maxOf(it.infoM, w + 0.2)) }
            }
            SliderSetting("Notice (slow beeps)", "Closer than this: slow beeps only, no speech.", s.infoM.toFloat(), 2.0f..4.0f, ::metres, steps = 19) { v ->
                update { it.copy(infoM = maxOf((v * 10).roundToInt() / 10.0, s.warningM + 0.2)) }
            }
            SliderSetting(
                "Repeat spoken warning after", "A hazard that is still there is not repeated unless it gets closer; a new one waits this long.",
                s.hazardCooldownMs / 1000f, 1f..10f, { "${it.roundToInt()} s" }, steps = 8,
            ) { v -> update { it.copy(hazardCooldownMs = v.roundToInt() * 1000L) } }
        }

        // ---------------- 5. Hardware & Simulation ----------------
        SectionCard(title = "Hardware & Simulation", subtitle = "Use the in-app simulator, or connect the real headband and compass.") {
            SwitchSetting(
                "Simulator mode",
                "Replaces the headband and compass with simulated devices. Everything they report is labelled SIMULATED.",
                s.simulatorMode,
            ) { v -> update { it.copy(simulatorMode = v) } }
        }
        ExpandableCard("Device addresses", "Bluetooth addresses for the real compass and headband (leave blank to scan).") {
            TextSetting("Compass BLE address", "Blank = scan for the compass service.", s.compassAddress.orEmpty(), "AA:BB:CC:DD:EE:FF") { v -> update { it.copy(compassAddress = v.ifBlank { null }) } }
            TextSetting("Headband BLE address", "Blank = scan for the headband service.", s.headbandAddress.orEmpty(), "AA:BB:CC:DD:EE:FF") { v -> update { it.copy(headbandAddress = v.ifBlank { null }) } }
        }
        ExpandableCard("Headband camera", "Marker type, frame rate and the frame server the headband connects to.") {
            ChoiceSetting(
                "Marker type", "Must match the printed markers.",
                listOf("DICT_APRILTAG_36h11" to "AprilTag 36h11", "DICT_4X4_50" to "ArUco 4x4", "DICT_APRILTAG_25h9" to "AprilTag 25h9"),
                s.markerDictionary,
            ) { v -> update { it.copy(markerDictionary = v) } }
            NumberSetting("Printed marker size", "Black square edge length; used to measure distance.", (s.markerSizeM * 100).roundToInt(), "cm", 3..50) { v -> update { it.copy(markerSizeM = v / 100.0) } }
            NumberSetting("Camera frame rate", "Frames per second requested from the headband.", s.frameRate, "fps", 1..15) { v -> update { it.copy(frameRate = v) } }
            NumberSetting("Object detection rate", "How often frames are checked for people and objects.", s.objectDetectorFps, "fps", 1..10) { v -> update { it.copy(objectDetectorFps = v) } }
            TextSetting("Frame server address", "Interface the phone listens on. 0.0.0.0 = all.", s.wsHost) { v -> update { it.copy(wsHost = v) } }
            NumberSetting("Frame server port", null, s.wsPort, "port", 1024..65535) { v -> update { it.copy(wsPort = v) } }
            OutlinedButton(onClick = a.restartVision, modifier = Modifier.heightIn(min = 48.dp)) { Text("Restart frame server") }
        }

        // ---------------- 6. Security & Privacy ----------------
        SectionCard(title = "Security & Privacy", subtitle = "Everything is processed on this phone. Nothing is sent to the cloud.") {
            SliderSetting(
                "Auto-lock", "Lock the app after it has been in the background this long. Turning the screen off always locks.",
                s.autoLockTimeoutS.toFloat(), 10f..600f, { v -> if (v < 60) "${v.roundToInt()} s" else "%.1f min".format(v / 60) }, steps = 0,
            ) { v -> update { it.copy(autoLockTimeoutS = ((v / 10).roundToInt() * 10).coerceIn(10, 600)) } }
            SwitchSetting("Record sessions", "Saves sensor and voice events to app-private files for replay in Diagnostics.", s.recordSession) { v -> update { it.copy(recordSession = v) } }
            SwitchSetting("Also save camera frames", "Debug only. Uses storage quickly.", s.recordFrames) { v -> update { it.copy(recordFrames = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { update { it.copy(disclaimerAccepted = false) } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Show safety notice") }
                OutlinedButton(onClick = a.lock, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Lock now") }
            }
        }
    }
}
