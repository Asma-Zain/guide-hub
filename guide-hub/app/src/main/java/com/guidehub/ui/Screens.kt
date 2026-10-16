package com.guidehub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guidehub.audio.Priority
import com.guidehub.core.AppContainer
import com.guidehub.core.DegradedFlag
import com.guidehub.core.DestinationProposal
import com.guidehub.core.Hazard
import com.guidehub.core.SessionState
import com.guidehub.core.SttBackend
import com.guidehub.core.Severity
import com.guidehub.core.VoiceState
import com.guidehub.debug.DiagnosticsScreen
import com.guidehub.nav.NavCard
import com.guidehub.nav.TurnType
import com.guidehub.voice.SttErrorKind
import kotlin.math.roundToInt

const val DISCLAIMER = "Guide Hub is a navigation aid, not a replacement for your white cane or guide dog. " +
    "Always use your cane. Use open-ear or bone-conduction earbuds, or transparency mode, so you can still hear " +
    "traffic, people and alarms around you. Warnings may be missed in poor light or crowds."

private enum class Tab(val title: String) { GUIDE("Guide"), SETTINGS("Settings"), DIAGNOSTICS("Diagnostics") }

@Composable
fun Root(container: AppContainer, authMessage: String?, onUnlock: () -> Unit, micGranted: Boolean, onRequestMic: () -> Unit) {
    val locked by container.auth.locked.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding()) {
        if (locked) {
            LockScreen(authMessage, onUnlock)
            return@Box
        }
        val settings by container.settings.settings.collectAsStateWithLifecycle()
        if (!settings.disclaimerAccepted) {
            DisclaimerScreen(container)
            return@Box
        }
        var tabIndex by rememberSaveable { mutableIntStateOf(0) }
        val tab = Tab.entries[tabIndex]
        BackHandler(enabled = tab != Tab.GUIDE) { tabIndex = 0 }
        Column(Modifier.fillMaxSize()) {
            TopBar(tab.title, settings.simulatorMode) { container.auth.lock() }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.GUIDE -> GuideScreen(container, micGranted, onRequestMic)
                    Tab.SETTINGS -> SettingsScreen(container, micGranted, onRequestMic)
                    Tab.DIAGNOSTICS -> DiagnosticsScreen(container)
                }
            }
            NavigationBar(containerColor = Palette.Surface, tonalElevation = 0.dp) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tabIndex == i,
                        onClick = { tabIndex = i },
                        icon = { TabGlyph(t, if (tabIndex == i) Palette.OnTealSoft else Palette.InkSoft) },
                        label = { Text(t.title, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Palette.TealSoft,
                            selectedTextColor = Palette.OnTealSoft,
                            unselectedTextColor = Palette.InkSoft,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, simulator: Boolean, onLock: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.Surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Guide Hub", style = MaterialTheme.typography.labelMedium, color = Palette.Teal)
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.Ink, modifier = Modifier.semantics { heading() })
            }
            if (simulator) SimulatedBadge()
            OutlinedButton(onClick = onLock, modifier = Modifier.heightIn(min = 44.dp)) { Text("Lock") }
        }
        HorizontalDivider(color = Palette.Outline)
    }
}

@Composable
private fun TabGlyph(t: Tab, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        when (t) {
            Tab.GUIDE -> { // compass needle in a ring
                drawCircle(color, w * 0.42f, center, style = stroke)
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.5f, w * 0.18f); lineTo(w * 0.62f, w * 0.52f); lineTo(w * 0.5f, w * 0.82f); lineTo(w * 0.38f, w * 0.52f); close()
                }
                drawPath(p, color)
            }
            Tab.SETTINGS -> { // three sliders
                for ((i, k) in listOf(0.3f, 0.65f, 0.45f).withIndex()) {
                    val y = w * (0.22f + i * 0.28f)
                    drawLine(color, Offset(w * 0.08f, y), Offset(w * 0.92f, y), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
                    drawCircle(color, w * 0.1f, Offset(w * k, y))
                }
            }
            Tab.DIAGNOSTICS -> { // pulse line
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.04f, w * 0.55f); lineTo(w * 0.3f, w * 0.55f); lineTo(w * 0.42f, w * 0.2f)
                    lineTo(w * 0.58f, w * 0.85f); lineTo(w * 0.7f, w * 0.55f); lineTo(w * 0.96f, w * 0.55f)
                }
                drawPath(p, color, style = stroke)
            }
        }
    }
}

// ===================== lock & disclaimer =====================

@Composable
fun LockScreen(message: String?, onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Space.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Guide Hub", style = MaterialTheme.typography.headlineMedium, color = Palette.Ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.s))
        Text("Indoor navigation aid", style = MaterialTheme.typography.bodyLarge, color = Palette.InkSoft)
        Spacer(Modifier.height(Space.xl))
        SectionCard(Modifier.widthIn(max = 480.dp)) {
            Text("Authentication required", style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
            Text("Unlock with your fingerprint, face or screen lock to continue.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            Button(
                onClick = onUnlock,
                modifier = Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = "Unlock Guide Hub with fingerprint, face or screen lock" },
            ) { Text("Unlock", style = MaterialTheme.typography.titleLarge) }
            message?.let {
                Text(it, color = Palette.Red, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

@Composable
fun DisclaimerScreen(container: AppContainer) {
    LaunchedEffect(Unit) { container.audio.speak(DISCLAIMER + " Tap the button at the bottom of the screen to accept.", Priority.INFO) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Text("Before you start", style = MaterialTheme.typography.headlineMedium, color = Palette.Ink, modifier = Modifier.semantics { heading() })
        SectionCard(title = "Safety", tone = Tone.Warning) {
            Text(DISCLAIMER, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
        }
        Button(
            onClick = { container.settings.update { it.copy(disclaimerAccepted = true) } },
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text("I understand", style = MaterialTheme.typography.titleMedium) }
    }
}

// ===================== guide =====================

/** Everything the Guide screen shows, as plain values (so the screen can be previewed and tested). */
data class GuideUiModel(
    val state: SessionState,
    val degraded: Set<DegradedFlag>,
    val voice: VoiceState,
    val heard: String?,
    val spoken: String?,
    val proposal: DestinationProposal?,
    val navCard: NavCard?,
    val arrivedAt: String?,
    val hazards: List<Hazard>,
    val mapErrors: List<String>?,
    val simulator: Boolean,
    val demoRunning: Boolean,
    val micGranted: Boolean,
    val sttAvailable: Boolean,
    val quickPicks: List<String>,
)

class GuideActions(
    val onTalk: () -> Unit,
    val onRequestMic: () -> Unit,
    val onSubmit: (String) -> Unit,
    val onConfirm: (Int) -> Unit,
    val onDecline: () -> Unit,
    val onPauseResume: () -> Unit,
    val onStop: () -> Unit,
    val onDismissArrival: () -> Unit,
    val onRunDemo: () -> Unit,
    val onStopDemo: () -> Unit,
)

@Composable
fun GuideScreen(container: AppContainer, micGranted: Boolean, onRequestMic: () -> Unit) {
    val o = container.orchestrator
    val state by o.session.state.collectAsStateWithLifecycle()
    val degraded by o.session.degraded.collectAsStateWithLifecycle()
    val voice by o.voice.collectAsStateWithLifecycle()
    val heard by o.lastHeard.collectAsStateWithLifecycle()
    val spoken by o.lastSpoken.collectAsStateWithLifecycle()
    val proposal by o.proposal.collectAsStateWithLifecycle()
    val navCard by o.navCard.collectAsStateWithLifecycle()
    val arrivedAt by o.arrivedAt.collectAsStateWithLifecycle()
    val hazards by o.hazards.collectAsStateWithLifecycle()
    val mapStatus by o.mapStatus.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val demoRunning by container.demoRunning.collectAsStateWithLifecycle()
    val sttAvailable = remember { container.androidStt.isAvailable() }
    val model = GuideUiModel(
        state = state, degraded = degraded, voice = voice, heard = heard, spoken = spoken, proposal = proposal,
        navCard = navCard, arrivedAt = arrivedAt, hazards = hazards,
        mapErrors = mapStatus?.takeIf { !it.ok }?.errors,
        simulator = settings.simulatorMode, demoRunning = demoRunning, micGranted = micGranted,
        sttAvailable = sttAvailable || settings.sttBackend != SttBackend.ANDROID,
        quickPicks = o.graph?.destinations?.map { it.names.first() }.orEmpty(),
    )
    val actions = remember(container, micGranted) {
        GuideActions(
            onTalk = { if (!micGranted && !o.voice.value.micActive) onRequestMic() else o.pushToTalk() },
            onRequestMic = onRequestMic,
            onSubmit = { o.submitText(it) },
            onConfirm = { o.confirmDestination(it) },
            onDecline = { o.declineDestination() },
            onPauseResume = { o.submitText(if (o.session.current == SessionState.PAUSED) "resume" else "pause") },
            onStop = { o.stopNavigationNow() },
            onDismissArrival = { o.dismissArrival() },
            onRunDemo = { container.playDemoScenario() },
            onStopDemo = { container.stopDemo() },
        )
    }
    GuideContent(model, actions)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuideContent(m: GuideUiModel, a: GuideActions) {
    val navigating = m.state == SessionState.NAVIGATING || m.state == SessionState.PAUSED
    val paused = m.state == SessionState.PAUSED
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        m.mapErrors?.let { errors ->
            SectionCard(title = "Map could not be loaded", tone = Tone.Danger) {
                Text(errors.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = Palette.Ink)
            }
        }
        if (m.demoRunning) DemoBanner(a.onStopDemo)

        when {
            navigating -> {
                NavigationPanel(m.navCard, paused, m.simulator)
                Warnings(m.hazards, m.degraded, m.simulator)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    OutlinedButton(onClick = a.onPauseResume, modifier = Modifier.weight(1f).height(Space.touch)) { Text(if (paused) "Resume" else "Pause") }
                    Button(
                        onClick = a.onStop,
                        modifier = Modifier.weight(1f).height(Space.touch),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Red, contentColor = Color.White),
                    ) { Text("Stop") }
                }
                if (!m.demoRunning) {
                    TalkButton(m.voice, compact = true, enabled = true, onClick = a.onTalk)
                    VoiceStatus(m.voice, m.heard, false, a.onTalk, a.onRequestMic)
                    Text("Say \u201chow far\u201d, \u201crepeat\u201d, \u201cpause\u201d or \u201ccancel\u201d.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
                }
            }
            m.state == SessionState.CONFIRMING && m.proposal != null -> {
                ProposalCard(m.proposal, m.heard, m.demoRunning, onStart = a.onConfirm, onCancel = a.onDecline)
                if (!m.demoRunning) VoiceStatus(m.voice, heard = null, simulatedSpeech = false, onRetry = a.onTalk, onRequestMic = a.onRequestMic)
            }
            else -> {
                m.arrivedAt?.let { name -> ArrivalCard(name, m.simulator && !m.demoRunning, onDone = a.onDismissArrival, onRunAgain = a.onRunDemo) }
                SectionCard(title = "Where to?", subtitle = if (m.demoRunning) "The demo is choosing a destination." else "Speak a destination, or type it below.") {
                    TalkButton(m.voice, compact = false, enabled = !m.demoRunning, onClick = a.onTalk)
                    val permissionProblem = (m.voice as? VoiceState.Problem)?.kind == SttErrorKind.PERMISSION
                    if (!m.micGranted && !permissionProblem) Notice("Microphone access is off. Tap to Talk will ask for it, or type a destination.", Tone.Warning)
                    else if (!m.sttAvailable) Notice("Speech recognition isn't available on this phone. Type the destination instead.", Tone.Warning)
                    VoiceStatus(m.voice, m.heard, m.demoRunning, a.onTalk, a.onRequestMic)
                    HorizontalDivider(color = Palette.Outline)
                    TypedDestination(enabled = !m.demoRunning, onSubmit = a.onSubmit)
                    if (m.quickPicks.isNotEmpty()) {
                        Text("Quick picks", style = MaterialTheme.typography.titleSmall, color = Palette.InkSoft)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            m.quickPicks.forEach { n ->
                                OutlinedButton(onClick = { a.onSubmit(n) }, enabled = !m.demoRunning, modifier = Modifier.heightIn(min = 44.dp)) { Text(n) }
                            }
                        }
                    }
                }
                if (m.simulator && !m.demoRunning) DemoCard(onRun = a.onRunDemo, onReset = a.onStopDemo)
            }
        }
        m.spoken?.let {
            Text(
                "Last spoken: $it", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun Notice(text: String, tone: Tone) {
    Surface(color = tone.container, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, tone.border), modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tone.content, modifier = Modifier.padding(Space.m))
    }
}

@Composable
private fun TalkButton(voice: VoiceState, compact: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val active = voice.micActive
    val (label, sub) = when (voice) {
        VoiceState.Preparing -> "Getting ready…" to "Tap to cancel"
        is VoiceState.Listening -> "Listening…" to (if (voice.speechDetected) "Hearing you · tap to stop" else "Speak now · tap to stop")
        else -> "Tap to Talk" to (if (compact) "Ask a question or give a command" else "Say a destination, e.g. “Take me to Lab 204”")
    }
    val pulse = rememberInfiniteTransition(label = "listening")
    val ring by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ring")
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(if (compact) 72.dp else 108.dp)
            .semantics { contentDescription = if (active) "$label Tap to stop listening." else "Tap to Talk. $sub" },
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Palette.TealDark else Palette.Teal,
            contentColor = Color.White,
            disabledContainerColor = Palette.SurfaceAlt,
            disabledContentColor = Palette.InkSoft,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Box(Modifier.alpha(if (active) ring else 1f)) { MicGlyph(if (enabled) Color.White else Palette.InkSoft, if (compact) 26.dp else 34.dp) }
            Column(Modifier.weight(1f)) {
                Text(label, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall)
                Text(sub, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Live transcript, processing state, and recoverable problems with the action that fixes them. */
@Composable
private fun VoiceStatus(voice: VoiceState, heard: String?, simulatedSpeech: Boolean, onRetry: () -> Unit, onRequestMic: () -> Unit) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        when (voice) {
            is VoiceState.Listening -> Text(
                voice.partial?.let { "“$it”" } ?: "Waiting for speech…",
                style = MaterialTheme.typography.titleMedium, color = if (voice.partial != null) Palette.Ink else Palette.InkSoft,
                fontStyle = if (voice.partial == null) FontStyle.Italic else FontStyle.Normal,
            )
            is VoiceState.Processing -> Text(
                voice.text?.let { "Understanding “$it”…" } ?: "Understanding…",
                style = MaterialTheme.typography.bodyLarge, color = Palette.InkSoft,
            )
            is VoiceState.Problem -> {
                Notice(voice.message, Tone.Warning)
                when (voice.kind) {
                    SttErrorKind.PERMISSION -> Button(onClick = onRequestMic, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow microphone") }
                    SttErrorKind.UNAVAILABLE, SttErrorKind.NETWORK ->
                        Text("Type the destination below instead.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
                    else -> TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                }
            }
            VoiceState.Cancelled -> Text("Listening cancelled.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            else -> heard?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Last heard: “$it”", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft, modifier = Modifier.weight(1f, fill = false))
                    if (simulatedSpeech) SimulatedBadge()
                }
            }
        }
    }
}

@Composable
private fun TypedDestination(enabled: Boolean, onSubmit: (String) -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    val submit = { if (typed.isNotBlank()) { onSubmit(typed.trim()); typed = "" } }
    Text("Or type it", style = MaterialTheme.typography.titleSmall, color = Palette.InkSoft)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(
            typed, { typed = it },
            label = { Text("Destination") },
            placeholder = { Text("e.g. Lab 204 or Library") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        Button(onClick = submit, enabled = enabled && typed.isNotBlank(), modifier = Modifier.height(Space.touch)) { Text("Find") }
    }
}

@Composable
private fun ProposalCard(p: DestinationProposal, heard: String?, simulatedSpeech: Boolean, onStart: (Int) -> Unit, onCancel: () -> Unit) {
    SectionCard(
        title = if (p.ambiguous) "Which one did you mean?" else "Confirm destination",
        subtitle = "You asked for “${heard ?: p.query}”.",
        tone = Tone.Accent,
    ) {
        if (simulatedSpeech) SimulatedBadge()
        p.options.forEachIndexed { i, opt ->
            Surface(color = Palette.Surface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, Palette.Outline), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(opt.name, style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
                    Text(
                        opt.distanceM?.let { "About ${it.roundToInt()} m from you" } ?: "Distance appears once your position is found.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft,
                    )
                    if (opt.steps.isNotEmpty()) {
                        Text(opt.steps.joinToString("  →  "), style = MaterialTheme.typography.bodySmall, color = Palette.TealDark)
                    }
                    Button(onClick = { onStart(i) }, modifier = Modifier.fillMaxWidth().height(Space.touch).padding(top = Space.xs)) {
                        Text(if (p.ambiguous) "Go to ${opt.name}" else "Start navigation")
                    }
                }
            }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(Space.touch)) { Text("Cancel") }
        Text(
            if (p.ambiguous) "You can also say “the first one” or the room name." else "You can also say “yes” or “no”.",
            style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft,
        )
    }
}

@Composable
private fun NavigationPanel(card: NavCard?, paused: Boolean, sim: Boolean) {
    if (card == null) {
        SectionCard(title = "Finding your position", subtitle = "Turn your head slowly so the headband camera can see the next marker.", tone = Tone.Accent) {
            if (sim) SimulatedBadge()
        }
        return
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Box(Modifier.size(104.dp).background(Palette.TealSoft, MaterialTheme.shapes.large), contentAlignment = Alignment.Center) {
                TurnArrow(card.turn, Palette.TealDark, 80.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.instruction, style = MaterialTheme.typography.headlineSmall, color = Palette.Ink)
                Text(
                    if (card.distanceToTurnM < 1.5 && card.turn != TurnType.ARRIVE) "now" else "in ${metresLabel(card.distanceToTurnM)}",
                    style = MaterialTheme.typography.headlineMedium, color = Palette.Teal,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("To ${card.destination}", style = MaterialTheme.typography.titleMedium, color = Palette.Ink, modifier = Modifier.weight(1f))
            if (paused) StatusPill("Paused", Tone.Warning)
        }
        LinearProgressIndicator(
            progress = { card.progress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = Palette.Teal, trackColor = Palette.SurfaceAlt, drawStopIndicator = {},
        )
        Text(
            "${metresLabel(card.remainingM)} to go of ${card.totalM.roundToInt()} m · leg ${card.legNumber} of ${card.legCount}",
            style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft,
        )
    }
}

/** Only what helps the user right now: close hazards and degraded devices, in plain words. */
@Composable
private fun Warnings(hazards: List<Hazard>, degraded: Set<DegradedFlag>, sim: Boolean) {
    hazards.filter { it.severity.level >= Severity.WARNING.level }.sortedByDescending { it.severity.level }.take(2).forEach { h ->
        val what = if (h.label == "obstacle") "Obstacle" else h.label.replaceFirstChar(Char::uppercase)
        val where = when {
            kotlin.math.abs(h.bearingDeg) < 10 -> "ahead"
            h.bearingDeg > 0 -> "on the right"
            else -> "on the left"
        }
        WarningRow("$what $where", "${"%.1f".format(h.rangeM)} m away", if (h.severity == Severity.CRITICAL) Tone.Danger else Tone.Warning, sim)
    }
    degraded.sortedBy { it.ordinal }.forEach { f ->
        val (title, detail, device) = when (f) {
            DegradedFlag.COMPASS_LOST -> Triple("Compass not connected", "Spoken directions continue.", true)
            DegradedFlag.HEADBAND_LOST -> Triple("Headband disconnected", "Obstacle warnings and marker detection are off. Take extra care.", true)
            DegradedFlag.CAMERA_LOST -> Triple("Headband camera disconnected", "Marker detection is off.", true)
            DegradedFlag.EARBUDS_LOST -> Triple("Earbuds disconnected", "Using the phone speaker.", false)
            DegradedFlag.LOW_POSITION_CONFIDENCE -> Triple("Approximate position", "Looking for the next marker.", false)
            DegradedFlag.HEADING_UNCERTAIN -> Triple("Direction uncertain", "Continue straight until the next marker.", false)
            DegradedFlag.TOF_ERROR -> Triple("Distance sensor error", "Warnings use the camera only.", true)
        }
        WarningRow(title, detail, Tone.Warning, sim && device)
    }
}

@Composable
private fun WarningRow(title: String, detail: String, tone: Tone, simulated: Boolean) {
    Surface(color = tone.container, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, tone.border), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = tone.content)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink)
            }
            if (simulated) SimulatedBadge()
        }
    }
}

@Composable
private fun ArrivalCard(name: String, offerDemo: Boolean, onDone: () -> Unit, onRunAgain: () -> Unit) {
    SectionCard(title = "You have arrived", subtitle = name, tone = Tone.Success) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Button(onClick = onDone, modifier = Modifier.weight(1f).height(Space.touch)) { Text("Done") }
            if (offerDemo) OutlinedButton(onClick = onRunAgain, modifier = Modifier.weight(1f).height(Space.touch)) { Text("Run demo again") }
        }
    }
}

@Composable
private fun DemoCard(onRun: () -> Unit, onReset: () -> Unit) {
    SectionCard(
        title = "Demo journey",
        subtitle = "Plays one simulated corridor trip: choose Lab 204, a person in the way, a compass dropout and recovery, then arrival. About a minute.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Button(onClick = onRun, modifier = Modifier.weight(1f).height(Space.touch)) { Text("Run Demo") }
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f).height(Space.touch)) { Text("Reset") }
        }
    }
}

@Composable
private fun DemoBanner(onStop: () -> Unit) {
    Surface(color = Tone.Simulated.container, shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, Tone.Simulated.border), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Column(Modifier.weight(1f)) {
                Text("Demo running", style = MaterialTheme.typography.titleMedium, color = Palette.Indigo)
                Text("Speech, sensors and devices are scripted, not real.", style = MaterialTheme.typography.bodySmall, color = Palette.Ink, textAlign = TextAlign.Start)
            }
            Button(
                onClick = onStop,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Indigo, contentColor = Color.White),
            ) { Text("Stop Demo") }
        }
    }
}
