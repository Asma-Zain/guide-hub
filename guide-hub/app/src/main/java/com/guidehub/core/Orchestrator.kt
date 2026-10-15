package com.guidehub.core

import com.guidehub.audio.HazardAnnouncer
import com.guidehub.audio.Priority
import com.guidehub.link.LinkSupervisor
import com.guidehub.nav.Geo
import com.guidehub.nav.MapGraph
import com.guidehub.nav.MapLoadResult
import com.guidehub.nav.NavigationController
import com.guidehub.nav.NeedleSmoother
import com.guidehub.nav.PositionEstimator
import com.guidehub.nav.Route
import com.guidehub.nav.Router
import com.guidehub.nav.Turns
import com.guidehub.nav.WallEstimate
import com.guidehub.nav.WallHeadingEstimator
import com.guidehub.protocol.ButtonPacket
import com.guidehub.protocol.CompassMode
import com.guidehub.vision.CameraIntrinsics
import com.guidehub.vision.DistanceFusion
import com.guidehub.vision.HazardConfig
import com.guidehub.vision.HazardEngine
import com.guidehub.voice.Candidate
import com.guidehub.voice.Confirmation
import com.guidehub.voice.ConfirmationParser
import com.guidehub.voice.DestinationMatcher
import com.guidehub.voice.IntentParser
import com.guidehub.voice.MatchResult
import com.guidehub.voice.RuleBasedIntentParser
import com.guidehub.voice.SpeechToText
import com.guidehub.voice.SttResult
import com.guidehub.voice.UserIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * FR-9 coordinator. Consumes the event bus on a single coroutine (all state lives on [scope]'s
 * dispatcher, which must be single-threaded), drives voice interaction, route planning, position
 * fusion, hazard warnings and the tactile compass, and applies the failure/degradation matrix.
 */
class Orchestrator(
    private val scope: CoroutineScope,
    private val bus: EventBus,
    private val clock: Clock,
    private val settings: SettingsRepository,
    private val audio: AudioOutput,
    private val mic: MicRouting,
    private val sttProvider: () -> SpeechToText,
    private val compass: CompassControl?,
    intrinsics: CameraIntrinsics,
    private val lockState: StateFlow<Boolean>,
    val session: NavigationSession = NavigationSession(),
    private val parser: IntentParser = RuleBasedIntentParser(),
) {
    // ---- map-dependent components ----
    var graph: MapGraph? = null; private set
    var position: PositionEstimator? = null; private set
    private var router: Router? = null
    private var nav: NavigationController? = null
    private var matcher: DestinationMatcher? = null

    private val wallEstimator = WallHeadingEstimator()
    private val hazardEngine = HazardEngine(DistanceFusion(intrinsics))
    private val announcer = HazardAnnouncer()
    private val smoother = NeedleSmoother()

    // ---- observable state for UI / debug ----
    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()
    private val _position = MutableStateFlow<PositionUpdate?>(null)
    val positionFlow: StateFlow<PositionUpdate?> = _position.asStateFlow()
    private val _hazards = MutableStateFlow<List<Hazard>>(emptyList())
    val hazards: StateFlow<List<Hazard>> = _hazards.asStateFlow()
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _lastHeard = MutableStateFlow<String?>(null)
    val lastHeard: StateFlow<String?> = _lastHeard.asStateFlow()
    private val _lastSpoken = MutableStateFlow<String?>(null)
    val lastSpoken: StateFlow<String?> = _lastSpoken.asStateFlow()
    private val _mapStatus = MutableStateFlow<MapLoadResult?>(null)
    val mapStatus: StateFlow<MapLoadResult?> = _mapStatus.asStateFlow()
    private val _lastSweep = MutableStateFlow<TofSweep?>(null)
    val lastSweepFlow: StateFlow<TofSweep?> = _lastSweep.asStateFlow()
    private val _lastWall = MutableStateFlow<WallEstimate?>(null)
    val lastWall: StateFlow<WallEstimate?> = _lastWall.asStateFlow()
    private val _destination = MutableStateFlow<String?>(null)
    val destinationFlow: StateFlow<String?> = _destination.asStateFlow()

    /** BLE peripherals may stay connected while locked only during an active navigation session (FR-1). */
    val linksWanted: StateFlow<Boolean> = combine(session.locked, session.state) { locked, _ ->
        !locked || session.navigationActive
    }.stateIn(scope, SharingStarted.Eagerly, false)

    // ---- internal state ----
    private var destination: String?
        get() = _destination.value
        set(v) { _destination.value = v }
    private var listenJob: Job? = null
    private var sttFailures = 0
    private var confirmFailures = 0
    private var autoListenBudget = 0
    private var candidates: List<Candidate> = emptyList()
    private var lastSweep: TofSweep? = null
    private var lastObjects: ObjectDetections? = null
    private var tofHealthy = true
    private var badSweeps = 0
    private var lastReplanTs = Long.MIN_VALUE / 2
    private var lowConfAnnounced = false
    private var headingUncertainAnnounced = false
    private var compassHazardMode = false
    private var lastPhoneVoTs = Long.MIN_VALUE / 2
    private var lastNavInstruction: String? = null
    private val linkStates = mutableMapOf<LinkId, LinkState>()
    private val linksSeenConnected = mutableSetOf<LinkId>()
    private val linksLossAnnounced = mutableSetOf<LinkId>()
    private var started = false

    private val cfg: AppSettings get() = settings.settings.value

    fun start() {
        if (started) return
        started = true
        scope.launch { bus.events.collect { handle(it) } }
        scope.launch {
            lockState.collect { locked -> if (locked) onLocked() else onUnlocked() }
        }
        scope.launch {
            combine(session.state, session.degraded, session.locked) { s, d, l -> Triple(s, d, l) }.collect { (s, d, l) ->
                bus.emit(SessionStateEvent(s.name, d.map { it.name }.toSet(), l, clock.now()))
            }
        }
        scope.launch {
            settings.settings.collect { s ->
                hazardEngine.config = HazardConfig(criticalM = s.criticalM, warningM = s.warningM, infoM = s.infoM)
                announcer.cooldownMs = s.hazardCooldownMs
            }
        }
        // 1 Hz housekeeping: stale hazard expiry, dead-reckoning decay when the headband is silent.
        scope.launch {
            while (isActive) {
                delay(1_000)
                val now = clock.now()
                if (lastSweep != null && now - lastSweep!!.ts > 1_500) updateHazards()
                if (session.hasFlag(DegradedFlag.HEADBAND_LOST) && session.navigationActive) {
                    position?.decayForSilence(now)
                    afterPose()
                }
            }
        }
    }

    // ===================== map =====================

    fun loadMap(result: MapLoadResult) {
        _mapStatus.value = result
        val map = result.map
        if (!result.ok || map == null) {
            graph = null; position = null; router = null; nav = null; matcher = null
            say("The building map could not be loaded. Please ask a helper to check the map file.", Priority.WARNING)
            return
        }
        val g = MapGraph(map)
        graph = g
        position = PositionEstimator(g, clock)
        router = Router(g)
        nav = NavigationController(g)
        matcher = DestinationMatcher(g.destinations)
        runCatching { sttProvider().setVocabulary(g.destinations.flatMap { it.names }) }
    }

    // ===================== entry points (thread-safe: hop onto scope) =====================

    /** Push-to-talk from any source (earbud button, headband/compass button, volume key, screen). */
    fun pushToTalk() {
        scope.launch { onPushToTalk() }
    }

    /** Typed destination / command (M3 text-driven navigation, debug). */
    fun submitText(text: String) {
        scope.launch {
            autoListenBudget = 0
            handleUtterance(Utterance(text, 1.0f, clock.now()))
        }
    }

    // ===================== bus =====================

    private fun handle(e: BusEvent) {
        when (e) {
            is TofSweep -> onSweep(e)
            is VoDelta -> onVo(e)
            is MarkerDetection -> onMarker(e)
            is ObjectDetections -> { lastObjects = e; updateHazards() }
            is FrameQuality -> position?.onFrameQuality(e)
            is ButtonEvent -> if (e.event == ButtonPacket.PRESS || e.event == ButtonPacket.DOUBLE_PRESS) onPushToTalk()
            is LinkStatus -> onLink(e)
            else -> Unit
        }
    }

    private fun onVo(e: VoDelta) {
        // If both the phone and the Pi run VO, use the phone's and ignore the Pi's to avoid double counting.
        if (e.source == DataSource.PI && clock.now() - lastPhoneVoTs < 1_000) return
        if (e.source != DataSource.PI) lastPhoneVoTs = clock.now()
        position?.onVo(e)
        afterPose()
    }

    private fun onSweep(s: TofSweep) {
        val valid = s.points.count { it.rangeM > 0.05 }
        badSweeps = if (valid == 0) badSweeps + 1 else 0
        val healthy = badSweeps < 5
        if (healthy != tofHealthy) {
            tofHealthy = healthy
            session.setFlag(DegradedFlag.TOF_ERROR, !healthy)
            if (!healthy) say("Distance sensor error. Hazard warnings now use the camera only. Take extra care.", Priority.WARNING)
            else say("Distance sensor working again.", Priority.INFO)
        }
        lastSweep = s
        _lastSweep.value = s
        val width = position?.edge?.width
        val est = if (healthy) wallEstimator.estimate(s, width) else null
        if (est != null) {
            position?.onWall(est)
            _lastWall.value = est
        }
        updateHazards()
        afterPose()
    }

    private fun onMarker(m: MarkerDetection) {
        val pos = position ?: return
        val upd = pos.onMarker(m) ?: return
        bus.emit(LogEvent("position", "Marker ${m.id} fix at ${pos.lastFixNode}; correction ${upd.lastCorrectionM?.let { "%.2f m".format(it) } ?: "n/a"}", clock.now()))
        if (session.navigationActive && nav?.route == null && destination != null) {
            if (planRoute()) {
                say("Position found. ${routeReadback()}", Priority.NAVIGATION)
                nav?.initialInstruction(pos.yaw)?.let { sayNav(it) }
            }
        }
        afterPose()
    }

    // ===================== hazards =====================

    private fun updateHazards() {
        val now = clock.now()
        val headbandLost = session.hasFlag(DegradedFlag.HEADBAND_LOST)
        val sweep = lastSweep?.takeIf { now - it.ts <= 1_500 }
        val objects = lastObjects?.takeIf { now - it.ts <= 1_000 }
        val hz = if (headbandLost) emptyList() else hazardEngine.evaluate(sweep, objects, now, tofHealthy)
        val changed = hz != _hazards.value
        _hazards.value = hz
        if (changed) bus.emit(HazardsUpdate(hz, now))
        audio.hazardCue(hz)
        for (h in announcer.update(hz, now)) {
            val p = if (h.severity == Severity.CRITICAL) Priority.CRITICAL else Priority.WARNING
            say(HazardAnnouncer.phrase(h), p, key = "hz:${h.trackKey}")
        }
        val critical = hz.firstOrNull { it.severity == Severity.CRITICAL }
        if (critical != null) {
            if (listenJob?.isActive == true && _listening.value) {
                listenJob?.cancel()
                say("Please say that again.", Priority.INFO)
            }
            if (session.current == SessionState.NAVIGATING) {
                compassHazardMode = true
                compass?.setTarget(critical.bearingDeg, CompassMode.HAZARD)
            }
        } else if (compassHazardMode) {
            compassHazardMode = false
            afterPose()
        }
    }

    // ===================== position & guidance =====================

    private fun afterPose() {
        val pos = position ?: return
        val upd = pos.snapshot() ?: return
        _position.value = upd
        bus.emit(upd)
        if (session.current != SessionState.NAVIGATING) return
        val n = nav ?: return
        if (n.route == null) return
        val g = n.update(upd)
        if (g.deviated) { replan(); return }
        if (g.stepChanged) n.route?.let { r -> bus.emit(RouteStepEvent(r.steps[g.stepIndex], g.stepIndex, r.steps.size, clock.now())) }
        g.announcements.forEach { sayNav(it) }
        if (g.arrived) { arrive(); return }

        val lowConf = upd.confidence < Constants.LOW_CONFIDENCE
        session.setFlag(DegradedFlag.LOW_POSITION_CONFIDENCE, lowConf)
        if (lowConf && !lowConfAnnounced) {
            lowConfAnnounced = true
            say("Approximate position. Looking for the next marker.", Priority.INFO)
        } else if (!lowConf) lowConfAnnounced = false

        val headingOk = upd.headingConfidence >= Constants.LOW_CONFIDENCE
        session.setFlag(DegradedFlag.HEADING_UNCERTAIN, !headingOk)
        if (!headingOk) {
            // Hold the last needle bearing rather than point confidently in the wrong direction.
            if (!headingUncertainAnnounced) {
                headingUncertainAnnounced = true
                sayNav("Direction uncertain. Continue straight until the next marker.")
            }
            return
        }
        headingUncertainAnnounced = false
        if (!compassHazardMode) {
            g.relativeBearingDeg?.let { rel -> compass?.setTarget(smoother.update(rel, upd.yawDeg, upd.ts), CompassMode.NAVIGATE) }
        }
    }

    /** Plans from the current edge to [destination]. Returns false if the position is unknown. */
    private fun planRoute(): Boolean {
        val dest = destination ?: return false
        val pos = position ?: return false
        val r = router ?: return false
        val e = pos.edge ?: return false
        val viaTo = r.shortestPath(e.to, dest)?.let { listOf(e) + it }
        val viaFrom = r.shortestPath(e.from, dest)?.let { listOf(e.reversed()) + it }
        val costTo = viaTo?.let { (e.lengthM - pos.s) + it.drop(1).sumOf { x -> x.lengthM } } ?: Double.POSITIVE_INFINITY
        val costFrom = viaFrom?.let { pos.s + it.drop(1).sumOf { x -> x.lengthM } } ?: Double.POSITIVE_INFINITY
        val edges = (if (costTo <= costFrom) viaTo else viaFrom) ?: return false
        val route = r.buildRoute(dest, edges)
        pos.setRoute(route)
        nav?.start(route)
        smoother.reset()
        _route.value = route
        return true
    }

    private fun routeReadback(): String {
        val g = graph ?: return ""
        val dest = destination ?: return ""
        val remaining = (_route.value?.totalM ?: 0.0) - (position?.s ?: 0.0)
        return "Navigating to ${g.displayName(dest)}, about ${Turns.metres(remaining.coerceAtLeast(1.0))}."
    }

    private fun replan() {
        val now = clock.now()
        if (now - lastReplanTs < 4_000) return
        lastReplanTs = now
        if (planRoute()) {
            sayNav("Re-routing. " + (nav?.initialInstruction(position?.yaw) ?: ""))
        }
    }

    private fun beginNavigation(c: Candidate) {
        val g = graph ?: return
        destination = c.node.id
        candidates = emptyList()
        sttFailures = 0
        confirmFailures = 0
        announcer.reset()
        if (!session.transition(SessionState.NAVIGATING)) {
            bus.emit(LogEvent("session", "Cannot start navigation from ${session.current}", clock.now()))
            return
        }
        audio.earcon(Earcon.CONFIRM)
        if (planRoute()) {
            say(routeReadback(), Priority.NAVIGATION)
            nav?.initialInstruction(position?.yaw)?.let { sayNav(it) }
            afterPose()
        } else {
            _route.value = null
            nav?.stop()
            say("Navigating to ${g.displayName(c.node.id)}. Looking for a marker to find your position. Turn your head slowly.", Priority.NAVIGATION)
            compass?.setTarget(0.0, CompassMode.IDLE)
        }
    }

    private fun arrive() {
        session.transition(SessionState.ARRIVED)
        audio.earcon(Earcon.ARRIVED)
        compass?.setTarget(0.0, CompassMode.ARRIVED)
        nav?.stop()
        position?.setRoute(null)
        _route.value = null
        destination = null
        scope.launch {
            delay(5_000)
            if (session.current == SessionState.ARRIVED) {
                session.transition(SessionState.IDLE)
                compass?.setTarget(0.0, CompassMode.IDLE)
                session.settleAfterNavigation()
            }
        }
    }

    private fun stopNavigation(message: String) {
        destination = null
        candidates = emptyList()
        nav?.stop()
        position?.setRoute(null)
        _route.value = null
        compass?.setTarget(0.0, CompassMode.IDLE)
        session.transition(SessionState.IDLE)
        session.settleAfterNavigation()
        say(message, Priority.NAVIGATION)
    }

    // ===================== voice =====================

    private fun onPushToTalk() {
        if (session.locked.value) {
            // FR-1: a locked app must not accept voice commands.
            audio.earcon(Earcon.ERROR)
            say("The app is locked. Unlock it on the phone first.", Priority.INFO)
            return
        }
        if (listenJob?.isActive == true) {
            listenJob?.cancel() // second press cancels listening
            return
        }
        autoListenBudget = 2
        listenJob = scope.launch { listenOnce() }
    }

    private suspend fun listenOnce() {
        val stt = sttProvider()
        if (session.current == SessionState.IDLE || session.current == SessionState.ARRIVED) {
            session.transition(SessionState.AWAITING_DESTINATION)
        }
        var result: SttResult = SttResult.NoSpeech
        var cancelled = false
        try {
            audio.beginListening()
            mic.openMic()
            _listening.value = true
            audio.earcon(Earcon.LISTEN_START)
            result = stt.listen()
        } catch (c: CancellationException) {
            cancelled = true
            throw c
        } finally {
            withContext(NonCancellable) {
                _listening.value = false
                audio.earcon(Earcon.LISTEN_END)
                mic.closeMic()
                audio.endListening()
                if (cancelled) backToIdleIfAwaiting()
            }
        }
        when (result) {
            is SttResult.Heard -> handleUtterance(result.utterance)
            SttResult.NoSpeech -> { say("I didn't hear anything.", Priority.INFO); backToIdleIfAwaiting() }
            is SttResult.Error -> { say("Speech recognition failed. ${result.message}", Priority.INFO); backToIdleIfAwaiting() }
        }
    }

    private fun maybeAutoListen() {
        if (!cfg.autoRelisten || autoListenBudget <= 0 || session.locked.value) return
        autoListenBudget--
        listenJob = scope.launch {
            audio.awaitSpeechDone()
            listenOnce()
        }
    }

    private fun backToIdleIfAwaiting() {
        if (session.current == SessionState.AWAITING_DESTINATION) session.transition(SessionState.IDLE)
        session.settleAfterNavigation()
    }

    private fun handleUtterance(u: Utterance) {
        bus.emit(u)
        _lastHeard.value = u.text
        if (session.locked.value) return
        if (u.text.isBlank() || u.confidence < Constants.STT_LOW_CONFIDENCE) {
            sttFailures++
            say(
                if (sttFailures >= 2) "I still didn't catch that. Please say the room number digit by digit, for example two zero four."
                else "Sorry, I didn't catch that. Please repeat.",
                Priority.INFO,
            )
            backToIdleIfAwaiting()
            maybeAutoListen()
            return
        }
        if (session.current == SessionState.CONFIRMING) {
            handleConfirmation(u.text)
            return
        }
        val intent = parser.parse(u.text)
        bus.emit(IntentEvent(intent, u.text, clock.now()))
        if (intent != UserIntent.Unknown && intent !is UserIntent.NavigateTo) sttFailures = 0
        when (intent) {
            is UserIntent.NavigateTo -> resolveDestination(intent.destinationQuery)
            UserIntent.Cancel ->
                if (session.navigationActive || session.current == SessionState.CONFIRMING) stopNavigation("Navigation cancelled.")
                else say("There is nothing to cancel.", Priority.INFO)
            UserIntent.Repeat -> say(lastNavInstruction ?: nav?.lastInstruction ?: "There is nothing to repeat.", Priority.NAVIGATION)
            UserIntent.WhereAmI -> say(describePosition(), Priority.INFO)
            UserIntent.HowFar -> say(if (session.navigationActive) nav?.describeRemaining() ?: "Still looking for your position." else "You are not navigating right now.", Priority.INFO)
            UserIntent.Pause ->
                if (session.current == SessionState.NAVIGATING) {
                    session.transition(SessionState.PAUSED)
                    compass?.setTarget(0.0, CompassMode.IDLE)
                    say("Navigation paused. Say resume to continue.", Priority.NAVIGATION)
                } else say("Navigation is not running.", Priority.INFO)
            UserIntent.Resume ->
                if (session.current == SessionState.PAUSED) {
                    session.transition(SessionState.NAVIGATING)
                    say("Resuming navigation.", Priority.NAVIGATION)
                    afterPose()
                } else say("Navigation is not paused.", Priority.INFO)
            UserIntent.VolumeUp -> say("Volume ${(audio.volumeStep(+1) * 10).roundToInt()}.", Priority.INFO)
            UserIntent.VolumeDown -> say("Volume ${(audio.volumeStep(-1) * 10).roundToInt()}.", Priority.INFO)
            UserIntent.Help -> say(
                "You can say: take me to, followed by a room name or number. Or say where am I, how far, repeat, pause, resume, cancel, volume up, or volume down.",
                Priority.INFO,
            )
            UserIntent.Unknown -> {
                sttFailures++
                say("Sorry, I didn't understand. Say help to hear what you can say.", Priority.INFO)
                maybeAutoListen()
            }
        }
        backToIdleIfAwaiting()
    }

    private fun resolveDestination(query: String) {
        val m = matcher ?: run { say("No building map is loaded.", Priority.INFO); return }
        when (val r = m.match(query)) {
            is MatchResult.Confident -> beginNavigation(r.candidate)
            is MatchResult.Ambiguous -> {
                candidates = r.candidates
                confirmFailures = 0
                if (session.transition(SessionState.CONFIRMING)) {
                    say(confirmQuestion(), Priority.INFO)
                    maybeAutoListen()
                }
            }
            MatchResult.NoMatch -> {
                sttFailures++
                say(
                    "I couldn't find $query on this map. Please try again." +
                        if (sttFailures >= 2) " You can say the room number digit by digit." else "",
                    Priority.INFO,
                )
                maybeAutoListen()
            }
        }
    }

    private fun candidateNames(): List<String> = candidates.map { graph?.displayName(it.node.id) ?: it.name }

    private fun confirmQuestion(): String {
        val n = candidateNames()
        return when (n.size) {
            1 -> "Did you mean ${n[0]}?"
            2 -> "Did you mean ${n[0]} or ${n[1]}?"
            else -> "Did you mean ${n.dropLast(1).joinToString(", ")}, or ${n.last()}?"
        }
    }

    private fun handleConfirmation(text: String) {
        val names = candidateNames()
        when (val c = ConfirmationParser.parse(text, names)) {
            is Confirmation.Choice -> candidates.getOrNull(c.index)?.let { beginNavigation(it) }
            Confirmation.Yes ->
                if (candidates.size == 1) beginNavigation(candidates[0])
                else {
                    say(names.mapIndexed { i, n -> "${i + 1} for $n" }.joinToString(", ", prefix = "Please say ") + ".", Priority.INFO)
                    maybeAutoListen()
                }
            Confirmation.No -> {
                candidates = emptyList()
                session.transition(SessionState.IDLE)
                session.settleAfterNavigation()
                say("Okay. Where would you like to go?", Priority.INFO)
                maybeAutoListen()
            }
            Confirmation.Unclear -> {
                confirmFailures++
                if (confirmFailures >= 2) {
                    candidates = emptyList()
                    session.transition(SessionState.IDLE)
                    session.settleAfterNavigation()
                    say("Let's start again. Press the button and say your destination.", Priority.INFO)
                } else {
                    say("Please answer yes or no. ${confirmQuestion()}", Priority.INFO)
                    maybeAutoListen()
                }
            }
        }
    }

    private fun describePosition(): String {
        val g = graph ?: return "No building map is loaded."
        val pos = position ?: return "No building map is loaded."
        val e = pos.edge ?: return "I don't know your position yet. Looking for a marker."
        val near = if (pos.s < e.lengthM / 2) e.from else e.to
        val dist = if (near == e.from) pos.s else e.lengthM - pos.s
        val nearName = g.displayName(near)
        val where = if (dist < 2) "At $nearName" else "About ${Turns.metres(dist)} from $nearName"
        val lm = g.landmarkAt(near)?.let { " Landmark: $it." } ?: ""
        val approx = if (pos.confidence < Constants.LOW_CONFIDENCE) " This position is approximate." else ""
        val facing = pos.yaw?.let { y -> " You are facing towards ${g.displayName(if (Geo.angleDiff(y, e.headingDeg) < 90) e.to else e.from)}." } ?: ""
        return "$where, on ${g.map.building} floor ${g.map.floor}.$facing$lm$approx"
    }

    // ===================== links & failure matrix =====================

    private fun onLink(e: LinkStatus) {
        val prev = linkStates[e.link]
        linkStates[e.link] = e.state
        if (e.detail == LinkSupervisor.RELEASED) {
            // Intentional disconnect: forget the link so a later reconnect is not announced as recovery.
            linksSeenConnected -= e.link
            linksLossAnnounced -= e.link
            setLinkFlag(e.link, false)
            return
        }
        if (prev == e.state) return
        when (e.state) {
            LinkState.CONNECTED -> {
                linksSeenConnected += e.link
                setLinkFlag(e.link, false)
                if (linksLossAnnounced.remove(e.link)) say(recoveredText(e.link), Priority.INFO)
            }
            LinkState.DISCONNECTED, LinkState.DEGRADED -> {
                if (e.link !in linksSeenConnected || e.link in linksLossAnnounced) return
                linksLossAnnounced += e.link
                setLinkFlag(e.link, true)
                when (e.link) {
                    LinkId.EARBUDS -> audio.vibrate()
                    LinkId.HEADBAND_BLE -> { lastSweep = null; updateHazards() }
                    else -> Unit
                }
                say(lostText(e.link, e.state), Priority.WARNING)
            }
            LinkState.CONNECTING -> Unit
        }
    }

    private fun setLinkFlag(link: LinkId, on: Boolean) {
        val flag = when (link) {
            LinkId.COMPASS -> DegradedFlag.COMPASS_LOST
            LinkId.HEADBAND_BLE -> DegradedFlag.HEADBAND_LOST
            LinkId.HEADBAND_WIFI -> DegradedFlag.CAMERA_LOST
            LinkId.EARBUDS -> DegradedFlag.EARBUDS_LOST
        }
        session.setFlag(flag, on)
    }

    private fun lostText(link: LinkId, state: LinkState) = when (link) {
        LinkId.COMPASS -> if (state == LinkState.DEGRADED) "Compass not responding. Continuing with spoken directions."
        else "Compass disconnected. Continuing with spoken directions."
        LinkId.HEADBAND_BLE -> "Headband disconnected. Hazard warnings and marker detection are off. Take extra care."
        LinkId.HEADBAND_WIFI -> "Headband camera disconnected. Marker detection is off."
        LinkId.EARBUDS -> "Earbuds disconnected. Using the phone speaker."
    }

    private fun recoveredText(link: LinkId) = when (link) {
        LinkId.COMPASS -> "Compass reconnected."
        LinkId.HEADBAND_BLE -> "Headband reconnected."
        LinkId.HEADBAND_WIFI -> "Headband camera reconnected."
        LinkId.EARBUDS -> "Earbuds connected."
    }

    // ===================== lock =====================

    private fun onLocked() {
        if (listenJob?.isActive == true) listenJob?.cancel()
        if (session.current == SessionState.CONFIRMING) candidates = emptyList()
        session.lock()
    }

    private fun onUnlocked() {
        session.unlock()
    }

    // ===================== output helpers =====================

    private fun say(text: String, priority: Priority, key: String? = null) {
        if (text.isBlank()) return
        _lastSpoken.value = text
        audio.speak(text, priority, key)
        bus.emit(SpokenEvent(text, priority.name, clock.now()))
    }

    private fun sayNav(text: String) {
        lastNavInstruction = text
        say(text, Priority.NAVIGATION, key = "nav")
    }
}
