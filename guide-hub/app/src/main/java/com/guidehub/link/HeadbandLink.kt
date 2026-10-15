package com.guidehub.link

import com.guidehub.core.ButtonEvent
import com.guidehub.core.ButtonSource
import com.guidehub.core.Clock
import com.guidehub.core.Constants
import com.guidehub.core.DataSource
import com.guidehub.core.EventBus
import com.guidehub.core.LinkId
import com.guidehub.core.LinkState
import com.guidehub.core.LinkStatus
import com.guidehub.core.LogEvent
import com.guidehub.core.MarkerDetection
import com.guidehub.core.TofPoint
import com.guidehub.core.TofSweep
import com.guidehub.core.VoDelta
import com.guidehub.protocol.CtrlCommand
import com.guidehub.protocol.HeadbandCodec
import com.guidehub.protocol.HeadbandStatus
import com.guidehub.protocol.ProtocolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Decodes headband GATT notifications onto the event bus and watches for a stale link.
 * Marker/VO packets are only sent if the Pi runs those algorithms itself (see docs/pi_contract.md);
 * they arrive on the bus in the same models as phone-side detections.
 */
class HeadbandLink(
    private val transport: BleTransport,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val bus: EventBus,
) {
    private val _status = MutableStateFlow<HeadbandStatus?>(null)
    val status: StateFlow<HeadbandStatus?> = _status.asStateFlow()
    private var lastRx = 0L
    private var stale = false
    private val jobs = mutableListOf<Job>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            transport.notifications.collect { n ->
                lastRx = clock.now()
                if (stale) {
                    stale = false
                    bus.emit(LinkStatus(LinkId.HEADBAND_BLE, LinkState.CONNECTED, "data resumed", clock.now()))
                }
                try {
                    handle(n)
                } catch (e: ProtocolException) {
                    bus.emit(LogEvent("headband", "Malformed packet on ${n.characteristic}: ${e.message}", clock.now()))
                }
            }
        }
        // Heartbeat watchdog: sweeps arrive at 1-2 Hz, so silence > 3 s means the headband is gone.
        jobs += scope.launch {
            while (isActive) {
                delay(Constants.HEARTBEAT_MS / 2)
                if (transport.state.value == LinkState.CONNECTED && lastRx > 0 && !stale &&
                    clock.now() - lastRx > Constants.HEADBAND_STALE_MS
                ) {
                    stale = true
                    bus.emit(LinkStatus(LinkId.HEADBAND_BLE, LinkState.DEGRADED, "no data for ${Constants.HEADBAND_STALE_MS} ms", clock.now()))
                }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    suspend fun send(cmd: CtrlCommand): Boolean = transport.write(Constants.HEADBAND_CTRL, HeadbandCodec.encode(cmd), withResponse = true)

    private fun handle(n: BleNotification) {
        val now = clock.now()
        when (n.characteristic) {
            Constants.HEADBAND_TOF_SWEEP -> {
                val p = HeadbandCodec.decodeSweep(n.value)
                bus.emit(TofSweep(p.points.map { (a, mm) -> TofPoint(a.toDouble(), mm / 1000.0) }, now, DataSource.PI))
            }
            Constants.HEADBAND_VO -> {
                val p = HeadbandCodec.decodeVo(n.value)
                bus.emit(VoDelta(p.dYawCentiDeg / 100.0, p.dForwardMm / 1000.0, p.quality / 255.0, now, DataSource.PI))
            }
            Constants.HEADBAND_MARKER -> {
                val p = HeadbandCodec.decodeMarker(n.value)
                bus.emit(MarkerDetection(p.id, p.rangeCm / 100.0, p.bearingDeciDeg / 10.0, p.markerYawDeciDeg?.let { it / 10.0 }, now, DataSource.PI))
            }
            Constants.HEADBAND_BUTTON -> {
                val p = HeadbandCodec.decodeButton(n.value)
                bus.emit(ButtonEvent(ButtonSource.HEADBAND, p.buttonId, p.event, now))
            }
            Constants.HEADBAND_STATUS -> _status.value = HeadbandCodec.decodeStatus(n.value)
        }
    }
}

/**
 * Keeps a transport connected while [wanted] is true: reconnects with exponential backoff and
 * publishes [LinkStatus] transitions (the orchestrator announces loss/recovery once).
 */
class LinkSupervisor(
    private val link: LinkId,
    private val transport: BleTransport,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val bus: EventBus,
    private val backoff: Backoff = Backoff(),
) {
    private var job: Job? = null
    private var stateJob: Job? = null

    fun setWanted(wanted: Boolean) {
        if (wanted && job == null) {
            stateJob = scope.launch {
                transport.state.collect { s -> bus.emit(LinkStatus(link, s, transport.name, clock.now())) }
            }
            job = scope.launch {
                while (isActive) {
                    if (transport.state.value == LinkState.DISCONNECTED) {
                        val ok = try { transport.connect() } catch (e: Exception) {
                            bus.emit(LogEvent(link.name, "connect failed: ${e.message}", clock.now())); false
                        }
                        if (ok) backoff.reset() else { delay(backoff.next()); continue }
                    }
                    delay(500)
                }
            }
        } else if (!wanted && job != null) {
            // Stop reporting first so a deliberate release is not announced as a link loss.
            stateJob?.cancel(); stateJob = null
            job?.cancel(); job = null
            transport.disconnect()
            bus.emit(LinkStatus(link, LinkState.DISCONNECTED, RELEASED, clock.now()))
        }
    }

    companion object {
        /** [LinkStatus.detail] for an intentional disconnect (app locked, mode switch): not announced. */
        const val RELEASED = "released"
    }
}
