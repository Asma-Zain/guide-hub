package com.guidehub.sim

import com.guidehub.core.ButtonEvent
import com.guidehub.core.ButtonSource
import com.guidehub.core.Clock
import com.guidehub.core.EventBus
import com.guidehub.core.LinkId
import com.guidehub.core.LinkState
import com.guidehub.core.LinkStatus
import com.guidehub.core.LogEvent
import com.guidehub.core.Utterance
import com.guidehub.protocol.ButtonPacket
import com.guidehub.voice.SpeechToText
import com.guidehub.voice.SttProgress
import com.guidehub.voice.SttResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** STT fake: returns queued utterances. Used by scenarios and tests. */
class ScriptedSpeechToText(private val clock: Clock) : SpeechToText {
    override val name = "scripted"
    private val queue = Channel<Pair<String, Float>>(Channel.UNLIMITED)
    var listenCount = 0; private set
    override fun isAvailable() = true

    fun enqueue(text: String, confidence: Float = 0.9f) { queue.trySend(text to confidence) }

    override suspend fun listen(timeoutMs: Long, onProgress: (SttProgress) -> Unit): SttResult {
        listenCount++
        onProgress(SttProgress.Ready)
        val item = withTimeoutOrNull(timeoutMs) { queue.receive() } ?: return SttResult.NoSpeech
        onProgress(SttProgress.Processing)
        return SttResult.Heard(Utterance(item.first, item.second, clock.now()))
    }
}

/**
 * Scripted scenario: a JSON timeline of utterances, obstacles and link faults (FR-10).
 * See assets/scenarios/corridor_demo_scenario.json for the format.
 */
@Serializable
data class Scenario(
    val name: String,
    val map: String = "corridor_demo.json",
    val start: Start,
    val walkSpeedMps: Double = 1.2,
    val events: List<ScenarioEvent>,
) {
    @Serializable
    data class Start(val node: String, val headingDeg: Double, val backOffM: Double = 0.0)
}

@Serializable
data class ScenarioEvent(
    val t: Long,
    val type: String,
    val text: String? = null,
    val confidence: Float = 0.9f,
    val label: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val radius: Double = 0.25,
    val durationMs: Long? = null,
    val link: String? = null,
)

object ScenarioParser {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): Scenario = json.decodeFromString(Scenario.serializer(), text)
}

class ScenarioPlayer(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val bus: EventBus,
    private val world: SimWorld,
    private val stt: ScriptedSpeechToText,
    private val compass: MockCompassTransport,
    private val headband: MockHeadbandTransport,
) {
    private var job: Job? = null
    val running: Boolean get() = job?.isActive == true

    fun play(s: Scenario): Job {
        job?.cancel()
        world.placeAt(s.start.node, s.start.headingDeg, s.start.backOffM)
        world.speedMps = s.walkSpeedMps
        world.clearObstacles()
        bus.emit(LogEvent("scenario", "Playing '${s.name}'", clock.now()))
        return scope.launch {
            val t0 = clock.now()
            for (ev in s.events.sortedBy { it.t }) {
                val wait = t0 + ev.t - clock.now()
                if (wait > 0) delay(wait)
                apply(ev)
            }
        }.also { job = it }
    }

    fun stop() { job?.cancel(); job = null }

    private fun apply(ev: ScenarioEvent) {
        val now = clock.now()
        when (ev.type) {
            "utterance" -> {
                stt.enqueue(ev.text.orEmpty(), ev.confidence)
                bus.emit(ButtonEvent(ButtonSource.EARBUD, 0, ButtonPacket.PRESS, now))
            }
            // An answer to a question asked while auto-listening is already open: no button press.
            "answer" -> stt.enqueue(ev.text.orEmpty(), ev.confidence)
            "button" -> bus.emit(ButtonEvent(ButtonSource.EARBUD, 0, ButtonPacket.PRESS, now))
            "obstacle" -> world.addObstacle(ev.label ?: "obstacle", ev.x ?: 0.0, ev.y ?: 0.0, ev.radius, ev.durationMs)
            "clear_obstacles" -> world.clearObstacles()
            "drop" -> when (ev.link) {
                "compass" -> compass.simulateDrop(ev.durationMs ?: 3_000)
                "headband" -> headband.simulateDrop(ev.durationMs ?: 3_000)
                else -> Unit
            }
            "compass_unresponsive" -> {
                compass.responsive = false
                scope.launch { delay(ev.durationMs ?: 3_000); compass.responsive = true }
            }
            "earbuds" -> bus.emit(LinkStatus(LinkId.EARBUDS, if (ev.text == "connected") LinkState.CONNECTED else LinkState.DISCONNECTED, "scenario", now))
            "log" -> bus.emit(LogEvent("scenario", ev.text.orEmpty(), now))
        }
    }
}
