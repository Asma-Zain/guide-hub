package com.guidehub.sim

import com.guidehub.core.Clock
import com.guidehub.core.Constants
import com.guidehub.core.LinkState
import com.guidehub.core.RingBuffer
import com.guidehub.link.BleNotification
import com.guidehub.link.BleTransport
import com.guidehub.protocol.AckStatus
import com.guidehub.protocol.CompassAck
import com.guidehub.protocol.CompassCodec
import com.guidehub.protocol.CompassCommand
import com.guidehub.protocol.CompassMode
import com.guidehub.protocol.CompassOpcode
import com.guidehub.protocol.CompassStatus
import com.guidehub.protocol.CtrlCommand
import com.guidehub.protocol.HeadbandCodec
import com.guidehub.protocol.HeadbandStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** In-app fake of the ESP32 compass: decodes commands, moves a virtual needle, ACKs after [latencyMs]. */
class MockCompassTransport(
    private val scope: CoroutineScope,
    private val clock: Clock,
    var latencyMs: Long = 25,
) : BleTransport {
    override val name = "MockCompass"
    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    override val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _n = MutableSharedFlow<BleNotification>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<BleNotification> = _n.asSharedFlow()

    /** When false the mock stops ACKing (tests "Compass not responding"). */
    @Volatile var responsive = true
    @Volatile var available = true
    @Volatile var needleDeg = 0; private set
    @Volatile var mode = CompassMode.IDLE; private set
    val received = RingBuffer<CompassCommand>(500)
    private val startTs = clock.now()

    override suspend fun connect(): Boolean {
        if (!available) return false
        _state.value = LinkState.CONNECTING
        delay(50)
        _state.value = LinkState.CONNECTED
        return true
    }

    override fun disconnect() { _state.value = LinkState.DISCONNECTED }

    override suspend fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean {
        if (_state.value != LinkState.CONNECTED || characteristic != Constants.COMPASS_CMD) return false
        val cmd = CompassCodec.decodeCommand(value)
        received.add(cmd)
        if (!responsive) return true
        scope.launch {
            delay(latencyMs)
            var status = AckStatus.OK
            when (cmd.opcode) {
                CompassOpcode.SET_BEARING, CompassOpcode.SET_MODE -> {
                    mode = cmd.mode
                    val target = when (cmd.mode) {
                        CompassMode.IDLE -> 0
                        else -> cmd.bearingDeg
                    }
                    if (target !in -180..180) status = AckStatus.CLAMPED
                    needleDeg = target.coerceIn(-180, 180)
                }
                CompassOpcode.CALIBRATE -> { mode = CompassMode.CALIBRATE; needleDeg = 0 }
                CompassOpcode.PING -> Unit
            }
            _n.tryEmit(BleNotification(Constants.COMPASS_ACK, CompassCodec.encode(CompassAck(cmd.seq, status, needleDeg)), clock.now()))
        }
        return true
    }

    override suspend fun read(characteristic: UUID): ByteArray? =
        if (characteristic == Constants.COMPASS_STATUS)
            CompassCodec.encode(CompassStatus(87, mode.code, (((clock.now() - startTs) / 1000).toInt()).coerceIn(0, 0xFFFF)))
        else null

    /** Simulate a BLE drop for [durationMs]; the supervisor reconnects afterwards. */
    fun simulateDrop(durationMs: Long) {
        available = false
        _state.value = LinkState.DISCONNECTED
        scope.launch { delay(durationMs); available = true }
    }
}

/** In-app fake of the headband BLE peripheral. [SimWorld] pushes encoded packets through it. */
class MockHeadbandTransport(private val scope: CoroutineScope, private val clock: Clock) : BleTransport {
    override val name = "MockHeadband"
    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    override val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _n = MutableSharedFlow<BleNotification>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<BleNotification> = _n.asSharedFlow()
    @Volatile var available = true
    val ctrlLog = RingBuffer<CtrlCommand>(50)
    @Volatile var sweeping = true; private set

    override suspend fun connect(): Boolean {
        if (!available) return false
        _state.value = LinkState.CONNECTING
        delay(50)
        _state.value = LinkState.CONNECTED
        return true
    }

    override fun disconnect() { _state.value = LinkState.DISCONNECTED }

    override suspend fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean {
        if (_state.value != LinkState.CONNECTED || characteristic != Constants.HEADBAND_CTRL) return false
        val c = HeadbandCodec.decodeCtrl(value)
        ctrlLog.add(c)
        when (c) {
            CtrlCommand.StartSweep -> sweeping = true
            CtrlCommand.StopSweep -> sweeping = false
            else -> Unit
        }
        return true
    }

    override suspend fun read(characteristic: UUID): ByteArray? =
        if (characteristic == Constants.HEADBAND_STATUS) HeadbandCodec.encode(HeadbandStatus(76, 48, 2)) else null

    /** Deliver a notification as if the Pi sent it. Dropped while disconnected. */
    fun push(characteristic: UUID, value: ByteArray) {
        if (_state.value == LinkState.CONNECTED) _n.tryEmit(BleNotification(characteristic, value, clock.now()))
    }

    fun simulateDrop(durationMs: Long) {
        available = false
        _state.value = LinkState.DISCONNECTED
        scope.launch { delay(durationMs); available = true }
    }
}
