package com.guidehub.link

import com.guidehub.core.Clock
import com.guidehub.core.CompassAckEvent
import com.guidehub.core.Constants
import com.guidehub.core.EventBus
import com.guidehub.core.LinkId
import com.guidehub.core.LinkState
import com.guidehub.core.LinkStatus
import com.guidehub.core.RingBuffer
import com.guidehub.nav.Geo
import com.guidehub.protocol.CompassAck
import com.guidehub.protocol.CompassCodec
import com.guidehub.protocol.CompassCommand
import com.guidehub.protocol.CompassMode
import com.guidehub.protocol.CompassOpcode
import com.guidehub.protocol.CompassStatus
import com.guidehub.protocol.ProtocolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** Debug log row: "each instruction sent to the compass is logged and processed". */
data class CompassLogEntry(
    val ts: Long,
    val command: CompassCommand,
    val attempts: Int,
    val ack: CompassAck?,
    val latencyMs: Long?,
    val outcome: String,
)

/** Rate limiter for needle commands: <= 5 Hz and >= 3 deg change, mode changes always pass. */
class BearingThrottle(
    private val minIntervalMs: Long = Constants.COMPASS_MIN_INTERVAL_MS,
    private val minDeltaDeg: Int = Constants.COMPASS_MIN_DELTA_DEG,
) {
    private var lastTs = Long.MIN_VALUE / 2
    private var lastDeg: Int? = null
    private var lastMode: CompassMode? = null

    fun waitMs(now: Long): Long = (lastTs + minIntervalMs - now).coerceAtLeast(0)

    fun accept(now: Long, deg: Int, mode: CompassMode): Boolean {
        val modeChanged = mode != lastMode
        if (!modeChanged) {
            if (now - lastTs < minIntervalMs) return false
            val prev = lastDeg
            if (prev != null && abs(Geo.norm180((deg - prev).toDouble())) < minDeltaDeg) return false
        }
        lastTs = now
        lastDeg = deg
        lastMode = mode
        return true
    }

    fun reset() { lastDeg = null; lastMode = null; lastTs = Long.MIN_VALUE / 2 }
}

/**
 * BLE client for the ESP32 tactile compass (FR-8). Every command expects an ACK; on timeout it retries
 * once after 300 ms, then marks the link degraded (announced by the orchestrator).
 */
class CompassLink(
    private val transport: BleTransport,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val bus: EventBus,
) {
    private data class Target(val deg: Int, val mode: CompassMode)

    val log = RingBuffer<CompassLogEntry>(Constants.COMPASS_LOG_SIZE)
    private val _degraded = MutableStateFlow(false)
    val degraded: StateFlow<Boolean> = _degraded.asStateFlow()
    private val _status = MutableStateFlow<CompassStatus?>(null)
    val status: StateFlow<CompassStatus?> = _status.asStateFlow()
    private val _needleDeg = MutableStateFlow<Int?>(null)
    /** Last needle angle reported by the ESP32 ACK. */
    val needleDeg: StateFlow<Int?> = _needleDeg.asStateFlow()

    private val target = MutableStateFlow<Target?>(null)
    private val throttle = BearingThrottle()
    private val sendMutex = Mutex()
    private var seq = 0
    private val pending = HashMap<Int, CompletableDeferred<CompassAck>>()
    private var lastSentTs = 0L
    private var lastTarget: Target? = null
    private val jobs = mutableListOf<Job>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            transport.notifications.collect { n ->
                try {
                    when (n.characteristic) {
                        Constants.COMPASS_ACK -> {
                            val ack = CompassCodec.decodeAck(n.value)
                            synchronized(pending) { pending.remove(ack.seq) }?.complete(ack)
                        }
                        Constants.COMPASS_STATUS -> _status.value = CompassCodec.decodeStatus(n.value)
                    }
                } catch (e: ProtocolException) {
                    bus.emit(com.guidehub.core.LogEvent("compass", "Malformed packet: ${e.message}", clock.now()))
                }
            }
        }
        // Throttled needle sender: StateFlow conflates, so the latest target always wins.
        jobs += scope.launch {
            target.filterNotNull().collect { _ ->
                val wait = throttle.waitMs(clock.now())
                if (wait > 0) delay(wait)
                val t = target.value ?: return@collect
                if (throttle.accept(clock.now(), t.deg, t.mode)) {
                    lastTarget = t
                    send(CompassOpcode.SET_BEARING, t.deg, t.mode)
                }
            }
        }
        // Keep-alive below the ESP32's 2 s failsafe.
        jobs += scope.launch {
            while (isActive) {
                delay(Constants.COMPASS_KEEPALIVE_MS / 2)
                if (transport.state.value == LinkState.CONNECTED && clock.now() - lastSentTs >= Constants.COMPASS_KEEPALIVE_MS) {
                    send(CompassOpcode.PING, lastTarget?.deg ?: 0, lastTarget?.mode ?: CompassMode.IDLE)
                }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /** Request a needle bearing; throttled to <= 5 Hz with a 3 deg dead-band. */
    fun setTarget(bearingDeg: Double, mode: CompassMode) {
        val deg = Geo.norm180(bearingDeg).roundToInt().let { if (it == -180) 180 else it }
        target.value = Target(deg, mode)
    }

    /** Immediate, unthrottled command (debug panel, calibration). */
    suspend fun send(opcode: CompassOpcode, bearingDeg: Int, mode: CompassMode): CompassLogEntry = sendMutex.withLock {
        val s = seq
        seq = (seq + 1) and 0xFF
        val cmd = CompassCommand(opcode, s, bearingDeg, mode)
        val bytes = CompassCodec.encode(cmd)
        val start = clock.now()
        lastSentTs = start
        var attempts = 0
        var ack: CompassAck? = null
        if (transport.state.value == LinkState.CONNECTED) {
            while (attempts < 2 && ack == null) {
                attempts++
                val deferred = CompletableDeferred<CompassAck>()
                synchronized(pending) { pending[s] = deferred }
                transport.write(Constants.COMPASS_CMD, bytes, withResponse = false)
                ack = withTimeoutOrNull(Constants.COMPASS_ACK_TIMEOUT_MS) { deferred.await() }
                synchronized(pending) { pending.remove(s) }
            }
        }
        val latency = ack?.let { clock.now() - start }
        val outcome = when {
            transport.state.value != LinkState.CONNECTED && ack == null -> "NOT_CONNECTED"
            ack == null -> "NO_ACK"
            else -> ack.status.name
        }
        val entry = CompassLogEntry(start, cmd, attempts, ack, latency, outcome)
        log.add(entry)
        if (ack != null) {
            _needleDeg.value = ack.actualDeg
            bus.emit(CompassAckEvent(ack.seq, ack.status.code, ack.actualDeg, latency ?: 0, clock.now()))
            if (_degraded.value) {
                _degraded.value = false
                bus.emit(LinkStatus(LinkId.COMPASS, LinkState.CONNECTED, "responding again", clock.now()))
            }
        } else if (attempts > 0 && !_degraded.value) {
            _degraded.value = true
            bus.emit(LinkStatus(LinkId.COMPASS, LinkState.DEGRADED, "Compass not responding", clock.now()))
        }
        entry
    }
}
