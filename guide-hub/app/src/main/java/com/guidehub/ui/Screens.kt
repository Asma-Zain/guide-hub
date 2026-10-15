package com.guidehub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guidehub.audio.Priority
import com.guidehub.core.AppContainer
import com.guidehub.core.SessionState
import com.guidehub.debug.DebugScreen

const val DISCLAIMER = "Guide Hub is a navigation aid, not a replacement for your white cane or guide dog. " +
    "Always use your cane. Use open-ear or bone-conduction earbuds, or transparency mode, so you can still hear " +
    "traffic, people and alarms around you. Warnings may be missed in poor light or crowds."

@Composable
fun Root(container: AppContainer, authMessage: String?, onUnlock: () -> Unit) {
    val locked by container.auth.locked.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        if (locked) {
            LockScreen(authMessage, onUnlock)
            return@Box
        }
        val settings by container.settings.settings.collectAsStateWithLifecycle()
        if (!settings.disclaimerAccepted) {
            DisclaimerScreen(container)
            return@Box
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        BackHandler(enabled = tab != 0) { tab = 0 }
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Guide", "Debug", "Settings").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 18.sp) })
                }
            }
            when (tab) {
                0 -> HomeScreen(container)
                1 -> DebugScreen(container)
                else -> SettingsScreen(container)
            }
        }
    }
}

@Composable
fun LockScreen(message: String?, onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Guide Hub", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        Text("Authentication required", style = MaterialTheme.typography.titleLarge, color = Palette.Accent)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onUnlock,
            modifier = Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Unlock Guide Hub with fingerprint, face or screen lock" },
        ) { Text("Unlock", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        message?.let {
            Spacer(Modifier.height(24.dp))
            Text(it, color = Palette.Warn, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
fun DisclaimerScreen(container: AppContainer) {
    LaunchedEffect(Unit) { container.audio.speak(DISCLAIMER + " Tap the button at the bottom of the screen to accept.", Priority.INFO) }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Before you start", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(DISCLAIMER, style = MaterialTheme.typography.bodyLarge)
        Button(
            onClick = { container.settings.update { it.copy(disclaimerAccepted = true) } },
            modifier = Modifier.fillMaxWidth().height(96.dp),
        ) { Text("I understand", fontSize = 24.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(container: AppContainer) {
    val o = container.orchestrator
    val state by o.session.state.collectAsStateWithLifecycle()
    val degraded by o.session.degraded.collectAsStateWithLifecycle()
    val listening by o.listening.collectAsStateWithLifecycle()
    val heard by o.lastHeard.collectAsStateWithLifecycle()
    val spoken by o.lastSpoken.collectAsStateWithLifecycle()
    val route by o.route.collectAsStateWithLifecycle()
    val dest by o.destinationFlow.collectAsStateWithLifecycle()
    val mapStatus by o.mapStatus.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Primary control for sighted helpers: one huge target.
        Box(
            Modifier.fillMaxWidth().height(220.dp)
                .background(if (listening) Palette.Ok else Palette.Accent, RoundedCornerShape(16.dp))
                .clickable(onClickLabel = "Talk") { o.pushToTalk() }
                .semantics { role = Role.Button; contentDescription = if (listening) "Listening. Tap to stop." else "Tap anywhere to talk" },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (listening) "Listening…" else "Tap to talk", color = Color.Black, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }

        Column(Modifier.fillMaxWidth().background(Palette.Panel, RoundedCornerShape(12.dp)).padding(12.dp).semantics(mergeDescendants = true) {}) {
            Text("State: ${state.name}" + if (settings.simulatorMode) "  (SIMULATOR)" else "", fontWeight = FontWeight.Bold)
            dest?.let { d -> Text("Destination: ${o.graph?.displayName(d) ?: d}") }
            route?.let { r -> Text("Route: ${r.nodes.joinToString(" → ")}  (${"%.0f".format(r.totalM)} m)") }
            if (degraded.isNotEmpty()) Text("Degraded: ${degraded.joinToString { it.name }}", color = Palette.Warn)
            mapStatus?.let { m ->
                if (!m.ok) Text("MAP ERROR: ${m.errors.joinToString("; ")}", color = Palette.Danger)
            }
        }
        heard?.let { Text("Heard: \"$it\"", color = Palette.Info) }
        spoken?.let { Text("Said: $it", color = Palette.Dim, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(typed, { typed = it }, label = { Text("Type a destination or command") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { if (typed.isNotBlank()) { o.submitText(typed); typed = "" } }) { Text("Go") }
        }

        Text("Destinations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            o.graph?.destinations?.forEach { n ->
                OutlinedButton(onClick = { o.submitText("take me to ${n.names.first()}") }) { Text(n.names.first()) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { o.submitText("cancel") },
                enabled = state == SessionState.NAVIGATING || state == SessionState.PAUSED || state == SessionState.CONFIRMING,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Danger),
            ) { Text("Cancel navigation") }
            OutlinedButton(onClick = { container.auth.lock() }) { Text("Lock") }
        }
        if (settings.simulatorMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { container.playDemoScenario() }) { Text("Run demo scenario") }
                OutlinedButton(onClick = { container.scenarioPlayer.stop(); container.placeSimAtEntrance() }) { Text("Reset sim") }
            }
        }
    }
}
