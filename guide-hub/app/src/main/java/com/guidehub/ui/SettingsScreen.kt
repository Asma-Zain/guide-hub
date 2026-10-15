package com.guidehub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guidehub.core.AppContainer
import com.guidehub.core.AppSettings
import com.guidehub.core.AudioRoutePref
import com.guidehub.core.PttButton
import com.guidehub.core.SttBackend
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val s by container.settings.settings.collectAsStateWithLifecycle()
    fun update(t: (AppSettings) -> AppSettings) = container.settings.update(t)

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Section("Mode")
        Toggle("Simulator mode (mock headband + compass)", s.simulatorMode) { v -> update { it.copy(simulatorMode = v) } }

        Section("Audio")
        Choice("Audio route", AudioRoutePref.entries, s.audioRoute) { v -> update { it.copy(audioRoute = v) } }
        Choice("Push-to-talk earbud button", PttButton.entries, s.pttButton) { v -> update { it.copy(pttButton = v) } }
        Toggle("Volume-down key is push-to-talk (app open)", s.volumeKeyPtt) { v -> update { it.copy(volumeKeyPtt = v) } }
        Toggle("Re-open the mic automatically after a question", s.autoRelisten) { v -> update { it.copy(autoRelisten = v) } }
        SliderRow("Speech rate", s.ttsRate, 0.5f..2.0f) { v -> update { it.copy(ttsRate = v) } }
        SliderRow("Speech volume", s.ttsVolume, 0.1f..1.0f) { v -> update { it.copy(ttsVolume = v) } }
        SliderRow("Hazard cue volume", s.hazardVolume, 0.1f..0.8f) { v -> update { it.copy(hazardVolume = v) } }
        Choice("Speech recognition", SttBackend.entries, s.sttBackend) { v -> update { it.copy(sttBackend = v) } }

        Section("Hazards")
        SliderRow("Critical below (m)", s.criticalM.toFloat(), 0.5f..2.0f) { v -> update { it.copy(criticalM = v.toDouble()) } }
        SliderRow("Warning below (m)", s.warningM.toFloat(), 1.0f..3.0f) { v -> update { it.copy(warningM = v.toDouble()) } }
        SliderRow("Info below (m)", s.infoM.toFloat(), 2.0f..4.0f) { v -> update { it.copy(infoM = v.toDouble()) } }
        SliderRow("Spoken warning cooldown (s)", s.hazardCooldownMs / 1000f, 1f..10f) { v -> update { it.copy(hazardCooldownMs = (v * 1000).toLong()) } }

        Section("Navigation")
        Choice("Map", container.availableMaps(), s.mapAsset) { v -> update { it.copy(mapAsset = v) }; container.loadMap(v) }
        SliderRow("Step length (m, for spoken steps only)", s.stepLengthM.toFloat(), 0.4f..1.0f) { v -> update { it.copy(stepLengthM = v.toDouble()) } }

        Section("Headband camera")
        Choice("Marker dictionary", listOf("DICT_APRILTAG_36h11", "DICT_4X4_50", "DICT_APRILTAG_25h9"), s.markerDictionary) { v -> update { it.copy(markerDictionary = v) } }
        NumberField("Marker size (cm)", (s.markerSizeM * 100).roundToInt()) { v -> update { it.copy(markerSizeM = v / 100.0) } }
        NumberField("Frame rate requested from headband (fps)", s.frameRate) { v -> update { it.copy(frameRate = v.coerceIn(1, 15)) } }
        NumberField("Object detector rate (fps)", s.objectDetectorFps) { v -> update { it.copy(objectDetectorFps = v.coerceIn(1, 10)) } }
        TextRow("WebSocket host", s.wsHost) { v -> update { it.copy(wsHost = v) } }
        NumberField("WebSocket port", s.wsPort) { v -> update { it.copy(wsPort = v.coerceIn(1024, 65535)) } }
        Button(onClick = { container.restartVision() }) { Text("Restart frame server") }
        TextRow("Compass BLE address (blank = scan)", s.compassAddress.orEmpty()) { v -> update { it.copy(compassAddress = v.ifBlank { null }) } }
        TextRow("Headband BLE address (blank = scan)", s.headbandAddress.orEmpty()) { v -> update { it.copy(headbandAddress = v.ifBlank { null }) } }

        Section("Security & recording")
        NumberField("Auto-lock after background (s)", s.autoLockTimeoutS) { v -> update { it.copy(autoLockTimeoutS = v.coerceIn(10, 3600)) } }
        Toggle("Record session (JSONL, app-private)", s.recordSession) { v -> update { it.copy(recordSession = v) } }
        Toggle("Also save camera frames (debug only)", s.recordFrames) { v -> update { it.copy(recordFrames = v) } }
        Button(onClick = { update { it.copy(disclaimerAccepted = false) } }) { Text("Show safety disclaimer again") }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Accent, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(label: String, options: List<T>, value: T, onChange: (T) -> Unit) {
    Text(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == value, onClick = { onChange(o) }, label = { Text(o.toString()) }) }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label: ${"%.2f".format(value)}")
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text, { t -> text = t; t.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun TextRow(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(text, { t -> text = t; onChange(t) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
