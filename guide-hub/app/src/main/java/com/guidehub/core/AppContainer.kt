package com.guidehub.core

import android.app.Application
import com.guidehub.audio.AudioFeedback
import com.guidehub.audio.AudioRouteManager
import com.guidehub.auth.AuthManager
import com.guidehub.auth.AutoLock
import com.guidehub.link.AndroidBleTransport
import com.guidehub.link.BleTransport
import com.guidehub.link.CompassLink
import com.guidehub.link.HeadbandLink
import com.guidehub.link.LinkSupervisor
import com.guidehub.link.WsServer
import com.guidehub.nav.MapLoadResult
import com.guidehub.nav.MapParser
import com.guidehub.protocol.CompassMode
import com.guidehub.recording.SessionRecorder
import com.guidehub.recording.SessionReplayer
import com.guidehub.sim.MockCompassTransport
import com.guidehub.sim.MockHeadbandTransport
import com.guidehub.sim.ScenarioParser
import com.guidehub.sim.ScenarioPlayer
import com.guidehub.sim.ScriptedSpeechToText
import com.guidehub.sim.SimWorld
import com.guidehub.vision.CameraIntrinsics
import com.guidehub.vision.FrameProcessor
import com.guidehub.vision.MarkerDetector
import com.guidehub.vision.ObjectDetector
import com.guidehub.vision.TfliteObjectDetector
import com.guidehub.voice.AndroidSpeechToText
import com.guidehub.voice.PushToTalk
import com.guidehub.voice.SpeechToText
import com.guidehub.voice.VoskSpeechToText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import java.io.File

/** One set of peripheral connections: either real BLE or the in-app simulator. */
class LinkSet(
    val simulated: Boolean,
    val compassTransport: BleTransport,
    val headbandTransport: BleTransport,
    val compass: CompassLink,
    val headband: HeadbandLink,
    val compassSupervisor: LinkSupervisor,
    val headbandSupervisor: LinkSupervisor,
)

/** Manual dependency graph (Hilt is optional per the PRD; this keeps the build simple). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(val app: Application) {
    val clock = Clock.SYSTEM
    val bus = EventBus()
    val settings: SettingsRepository = PrefsSettingsRepository(app)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Single-threaded: all orchestrator state is confined here. */
    val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val auth = AuthManager(clock, appScope) { settings.settings.value.autoLockTimeoutS }
    private val autoLock = AutoLock(app, auth)

    val intrinsics: CameraIntrinsics = runCatching {
        CameraIntrinsics.parse(app.assets.open("calibration/headband_cam_v2_640x480.json").bufferedReader().readText())
    }.getOrDefault(CameraIntrinsics.DEFAULT_640x480)

    val audioRoute = AudioRouteManager(app, bus, clock) { settings.settings.value.audioRoute }
    lateinit var audio: AudioFeedback; private set
    lateinit var pushToTalk: PushToTalk; private set

    val androidStt by lazy { AndroidSpeechToText(app, clock) { Permissions.hasMic(app) } }
    private val voskStt by lazy { VoskSpeechToText(app, clock) { Permissions.hasMic(app) } }
    val scriptedStt = ScriptedSpeechToText(clock)

    private val _links = MutableStateFlow<LinkSet?>(null)
    val links: StateFlow<LinkSet?> = _links.asStateFlow()

    // ---- simulator ----
    val mockCompass = MockCompassTransport(appScope, clock)
    val mockHeadband = MockHeadbandTransport(appScope, clock)
    lateinit var orchestrator: Orchestrator; private set
    lateinit var simWorld: SimWorld; private set
    lateinit var scenarioPlayer: ScenarioPlayer; private set

    // ---- vision ----
    var wsServer: WsServer? = null; private set
    var frameProcessor: FrameProcessor? = null; private set
    private var objectDetector: ObjectDetector? = null

    val recorder = SessionRecorder(File(app.filesDir, "recordings"), bus)
    lateinit var replayer: SessionReplayer; private set

    private val _linkStates = MutableStateFlow<Map<LinkId, LinkStatus>>(emptyMap())
    val linkStates: StateFlow<Map<LinkId, LinkStatus>> = _linkStates.asStateFlow()

    private var started = false
    private val jobs = mutableListOf<Job>()

    /**
     * The scripted fake is used only while the demo scenario is playing (its utterances are part of
     * the script). Tap to Talk otherwise always uses a real recogniser; if none is available the
     * session reports a clear error and the typed field is the fallback. (Previously simulator mode
     * silently substituted the fake whenever the recogniser check failed, so the mic never opened.)
     */
    private val sttProvider: () -> SpeechToText = {
        val s = settings.settings.value
        when {
            scenarioPlayer.running -> scriptedStt
            s.sttBackend == SttBackend.VOSK && voskStt.isAvailable() -> voskStt
            else -> androidStt
        }
    }

    /** Human-readable STT backend that Tap to Talk will use, for Settings/Diagnostics. */
    fun sttDescription(): String {
        val s = settings.settings.value
        return when {
            s.sttBackend == SttBackend.VOSK && voskStt.isAvailable() -> "Vosk (offline model)"
            s.sttBackend == SttBackend.VOSK -> "Vosk selected but no model installed, using Android: ${androidStt.activeBackend.value}"
            !androidStt.isAvailable() -> "No speech recognition service on this phone"
            else -> "Android SpeechRecognizer: ${androidStt.activeBackend.value}"
        }
    }

    private val _demoRunning = MutableStateFlow(false)
    /** True from Run Demo until it reaches arrival or Stop Demo is pressed. */
    val demoRunning: StateFlow<Boolean> = _demoRunning.asStateFlow()

    /** Must be called on the main thread (TTS, MediaSession). Idempotent. */
    fun start() {
        if (started) return
        started = true
        OpenCVLoader.initLocal()
        autoLock.install()
        audioRoute.start()
        audio = AudioFeedback(app, appScope, settings, audioRoute.earbudsConnected)
        pushToTalk = PushToTalk(app, bus, clock) { settings.settings.value.pttButton }.also { it.activate() }

        orchestrator = Orchestrator(
            scope = coreScope, bus = bus, clock = clock, settings = settings, audio = audio, mic = audioRoute,
            sttProvider = { sttProvider() },
            compass = object : CompassControl {
                override fun setTarget(bearingDeg: Double, mode: CompassMode) { _links.value?.compass?.setTarget(bearingDeg, mode) }
            },
            intrinsics = intrinsics, lockState = auth.locked,
        )
        simWorld = SimWorld(appScope, clock, bus, { orchestrator.graph }, mockHeadband, {
            if (orchestrator.session.current == SessionState.NAVIGATING) orchestrator.route.value else null
        }, intrinsics)
        scenarioPlayer = ScenarioPlayer(appScope, clock, bus, simWorld, scriptedStt, mockCompass, mockHeadband)
        replayer = SessionReplayer(appScope, bus, clock) { orchestrator.submitText(it) }

        jobs += appScope.launch { bus.events.collect { e -> if (e is LinkStatus) _linkStates.value = _linkStates.value + (e.link to e) } }
        orchestrator.start()
        loadMap(settings.settings.value.mapAsset)
        placeSimAtEntrance()

        jobs += appScope.launch {
            settings.settings.map { it.simulatorMode }.distinctUntilChanged().collect { sim -> buildLinks(sim) }
        }
        jobs += appScope.launch {
            orchestrator.linksWanted.collect { wanted ->
                _links.value?.let { it.compassSupervisor.setWanted(wanted); it.headbandSupervisor.setWanted(wanted) }
            }
        }
        // The demo is over once the simulated user arrives: stop the script so nothing else fires.
        jobs += appScope.launch {
            orchestrator.arrivedAt.collect { if (it != null && _demoRunning.value) { scenarioPlayer.stop(); _demoRunning.value = false } }
        }
        jobs += appScope.launch {
            settings.settings.map { it.recordSession }.distinctUntilChanged().collect { rec -> if (rec) recorder.start() else recorder.stop() }
        }
    }

    fun loadMap(asset: String): MapLoadResult {
        val text = runCatching { app.assets.open("maps/$asset").bufferedReader().readText() }.getOrNull()
        val result = if (text == null) MapLoadResult(null, listOf("Map file maps/$asset not found"), emptyList()) else MapParser.parse(text)
        orchestrator.loadMap(result)
        return result
    }

    /** Simulated user starts at the map entrance (or first node), facing along its first edge. */
    fun placeSimAtEntrance() {
        val g = orchestrator.graph ?: return
        val start = g.map.nodes.firstOrNull { it.type == "entrance" } ?: g.map.nodes.first()
        val heading = g.neighbours(start.id).firstOrNull()?.headingDeg ?: 0.0
        simWorld.placeAt(start.id, heading)
    }

    fun availableMaps(): List<String> = app.assets.list("maps")?.filter { it.endsWith(".json") && !it.contains("schema") }.orEmpty()

    private fun buildLinks(sim: Boolean) {
        _links.value?.let { old ->
            old.compassSupervisor.setWanted(false); old.headbandSupervisor.setWanted(false)
            old.compass.stop(); old.headband.stop()
        }
        stopVision()
        val s = settings.settings.value
        val compassT: BleTransport = if (sim) mockCompass else AndroidBleTransport(
            app, "ESP32 compass", Constants.COMPASS_SERVICE, listOf(Constants.COMPASS_ACK, Constants.COMPASS_STATUS),
        ) { settings.settings.value.compassAddress }
        val headbandT: BleTransport = if (sim) mockHeadband else AndroidBleTransport(
            app, "Headband", Constants.HEADBAND_SERVICE,
            listOf(Constants.HEADBAND_TOF_SWEEP, Constants.HEADBAND_VO, Constants.HEADBAND_MARKER, Constants.HEADBAND_BUTTON, Constants.HEADBAND_STATUS),
        ) { settings.settings.value.headbandAddress }
        val set = LinkSet(
            simulated = sim,
            compassTransport = compassT,
            headbandTransport = headbandT,
            compass = CompassLink(compassT, appScope, clock, bus).also { it.start() },
            headband = HeadbandLink(headbandT, appScope, clock, bus).also { it.start() },
            compassSupervisor = LinkSupervisor(LinkId.COMPASS, compassT, appScope, clock, bus),
            headbandSupervisor = LinkSupervisor(LinkId.HEADBAND_BLE, headbandT, appScope, clock, bus),
        )
        _links.value = set
        val wanted = orchestrator.linksWanted.value
        set.compassSupervisor.setWanted(wanted)
        set.headbandSupervisor.setWanted(wanted)
        if (sim) simWorld.start() else { simWorld.stop(); startVision(s) }
    }

    private fun startVision(s: AppSettings) {
        val server = WsServer(s.wsHost, s.wsPort, settings.pairingToken, clock, bus)
        runCatching { server.start() }.onFailure { bus.emit(LogEvent("ws", "Could not start: ${it.message}", clock.now())) }
        wsServer = server
        val od = objectDetector ?: TfliteObjectDetector.createOrNoop(app).also { objectDetector = it }
        frameProcessor = FrameProcessor(
            scope = appScope, source = server, bus = bus, clock = clock, intrinsics = intrinsics,
            markerDetector = MarkerDetector(intrinsics, s.markerDictionary, { settings.settings.value.markerSizeM }),
            objectDetector = od,
            tofForwardM = { orchestrator.lastSweepFlow.value?.points?.filter { kotlin.math.abs(it.angleDeg) <= 5 && it.rangeM > 0.05 }?.minOfOrNull { it.rangeM } },
            objectFps = { settings.settings.value.objectDetectorFps },
            frameDir = { if (settings.settings.value.recordFrames) File(app.filesDir, "recordings/frames") else null },
        ).also { it.start() }
    }

    private fun stopVision() {
        frameProcessor?.stop(); frameProcessor = null
        wsServer?.let { runCatching { it.stop(500) } }
        wsServer = null
    }

    /** Restart the frame server after host/port/token changes. */
    fun restartVision() {
        if (_links.value?.simulated == false) { stopVision(); startVision(settings.settings.value) }
    }

    /** Run Demo: one deterministic, paced corridor journey. Ignored while one is already running. */
    fun playDemoScenario(name: String = "corridor_demo_scenario.json") {
        if (_demoRunning.value) return
        _demoRunning.value = true
        resetSimulation()
        if (!settings.settings.value.simulatorMode) settings.update { it.copy(simulatorMode = true) }
        val text = app.assets.open("scenarios/$name").bufferedReader().readText()
        val sc = ScenarioParser.parse(text)
        if (orchestrator.mapStatus.value?.map?.building == null || settings.settings.value.mapAsset != sc.map) {
            settings.update { it.copy(mapAsset = sc.map) }
            loadMap(sc.map)
        }
        scenarioPlayer.play(sc).invokeOnCompletion { _demoRunning.value = false }
    }

    /** Stop Demo: cancel the scenario, its pending faults and obstacles, and every queued announcement. */
    fun stopDemo() {
        _demoRunning.value = false
        resetSimulation()
    }

    private fun resetSimulation() {
        scenarioPlayer.stop()
        simWorld.clearObstacles()
        mockCompass.available = true
        mockCompass.responsive = true
        mockHeadband.available = true
        orchestrator.resetSession()
        placeSimAtEntrance()
    }
}
