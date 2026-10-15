package com.guidehub.protocol

/** Headband (Pi Zero 2 W) BLE packets (PRD 7.1). All multi-byte values little-endian. */

/** `u32 ts, u8 nPoints, nPoints x (i8 angleDeg, u16 mm)`. A range of 0 mm means "no return". */
data class TofSweepPacket(val ts: Long, val points: List<Pair<Int, Int>>)

/** `u32 ts, i16 dYawDeg*100, u16 dForwardMm, u8 quality(0-255)`. */
data class VoPacket(val ts: Long, val dYawCentiDeg: Int, val dForwardMm: Int, val quality: Int)

/**
 * `u32 ts, u16 id, u16 rangeCm, i16 bearingDeg*10` (10 bytes), optionally followed by
 * `i16 markerYawDeg*10` (12 bytes). The trailing yaw field is a v1 extension, see docs/DECISIONS.md.
 */
data class MarkerPacket(val ts: Long, val id: Int, val rangeCm: Int, val bearingDeciDeg: Int, val markerYawDeciDeg: Int?)

/** `u8 buttonId, u8 event`. */
data class ButtonPacket(val buttonId: Int, val event: Int) {
    companion object {
        const val PRESS = 0
        const val RELEASE = 1
        const val LONG_PRESS = 2
        const val DOUBLE_PRESS = 3
    }
}

/** `u8 batteryPct, i8 temperatureC, u8 wifiState(0=down,1=connecting,2=connected)`. */
data class HeadbandStatus(val batteryPct: Int, val temperatureC: Int, val wifiState: Int)

sealed class CtrlCommand {
    data object StartSweep : CtrlCommand()
    data object StopSweep : CtrlCommand()
    data class SetSweepRange(val minDeg: Int, val maxDeg: Int) : CtrlCommand()
    data class SetFrameRate(val fps: Int) : CtrlCommand()
    data object Shutdown : CtrlCommand()
}

object HeadbandCodec {
    const val MAX_SWEEP_POINTS = 61

    fun encode(p: TofSweepPacket): ByteArray {
        if (p.points.size > MAX_SWEEP_POINTS) throw ProtocolException("Too many sweep points: ${p.points.size}")
        val w = LeWriter(5 + 3 * p.points.size).u32(p.ts).u8(p.points.size)
        p.points.forEach { (a, mm) -> w.i8(a).u16(mm) }
        return w.bytes()
    }

    fun decodeSweep(bytes: ByteArray): TofSweepPacket {
        val r = LeReader(bytes)
        val ts = r.u32()
        val n = r.u8()
        if (r.remaining != 3 * n) throw ProtocolException("Sweep declares $n points but has ${r.remaining} payload bytes")
        val pts = List(n) { r.i8() to r.u16() }
        return TofSweepPacket(ts, pts)
    }

    fun encode(p: VoPacket): ByteArray = LeWriter(9).u32(p.ts).i16(p.dYawCentiDeg).u16(p.dForwardMm).u8(p.quality).bytes()

    fun decodeVo(bytes: ByteArray): VoPacket {
        if (bytes.size != 9) throw ProtocolException("VO must be 9 bytes, got ${bytes.size}")
        val r = LeReader(bytes)
        return VoPacket(r.u32(), r.i16(), r.u16(), r.u8())
    }

    fun encode(p: MarkerPacket): ByteArray {
        val w = LeWriter(if (p.markerYawDeciDeg != null) 12 else 10).u32(p.ts).u16(p.id).u16(p.rangeCm).i16(p.bearingDeciDeg)
        p.markerYawDeciDeg?.let { w.i16(it) }
        return w.bytes()
    }

    fun decodeMarker(bytes: ByteArray): MarkerPacket {
        if (bytes.size != 10 && bytes.size != 12) throw ProtocolException("MARKER must be 10 or 12 bytes, got ${bytes.size}")
        val r = LeReader(bytes)
        return MarkerPacket(r.u32(), r.u16(), r.u16(), r.i16(), if (bytes.size == 12) r.i16() else null)
    }

    fun encode(p: ButtonPacket): ByteArray = LeWriter(2).u8(p.buttonId).u8(p.event).bytes()

    fun decodeButton(bytes: ByteArray): ButtonPacket {
        if (bytes.size != 2) throw ProtocolException("BUTTON must be 2 bytes, got ${bytes.size}")
        val r = LeReader(bytes)
        return ButtonPacket(r.u8(), r.u8())
    }

    fun encode(s: HeadbandStatus): ByteArray = LeWriter(3).u8(s.batteryPct).i8(s.temperatureC).u8(s.wifiState).bytes()

    fun decodeStatus(bytes: ByteArray): HeadbandStatus {
        if (bytes.size != 3) throw ProtocolException("STATUS must be 3 bytes, got ${bytes.size}")
        val r = LeReader(bytes)
        return HeadbandStatus(r.u8(), r.i8(), r.u8())
    }

    /** CTRL opcodes: 1 start sweep, 2 stop sweep, 3 sweep range (i8 min, i8 max), 4 frame rate (u8 fps), 5 shutdown. */
    fun encode(c: CtrlCommand): ByteArray = when (c) {
        CtrlCommand.StartSweep -> LeWriter(1).u8(1).bytes()
        CtrlCommand.StopSweep -> LeWriter(1).u8(2).bytes()
        is CtrlCommand.SetSweepRange -> LeWriter(3).u8(3).i8(c.minDeg).i8(c.maxDeg).bytes()
        is CtrlCommand.SetFrameRate -> LeWriter(2).u8(4).u8(c.fps).bytes()
        CtrlCommand.Shutdown -> LeWriter(1).u8(5).bytes()
    }

    fun decodeCtrl(bytes: ByteArray): CtrlCommand {
        val r = LeReader(bytes)
        val cmd = when (val op = r.u8()) {
            1 -> CtrlCommand.StartSweep
            2 -> CtrlCommand.StopSweep
            3 -> CtrlCommand.SetSweepRange(r.i8(), r.i8())
            4 -> CtrlCommand.SetFrameRate(r.u8())
            5 -> CtrlCommand.Shutdown
            else -> throw ProtocolException("Unknown CTRL opcode $op")
        }
        if (r.remaining != 0) throw ProtocolException("Trailing bytes in CTRL packet")
        return cmd
    }
}

/**
 * WebSocket `/frames` binary message: 17-byte header then JPEG bytes.
 * `u8 version(=1), u32 seq, i64 tsMs, u16 width, u16 height, <jpeg...>`
 */
data class FrameHeader(val seq: Long, val tsMs: Long, val width: Int, val height: Int)

object FrameCodec {
    const val HEADER_SIZE = 17

    fun encode(h: FrameHeader, jpeg: ByteArray): ByteArray {
        val head = LeWriter(HEADER_SIZE).u8(com.guidehub.core.Constants.FRAME_HEADER_VERSION).u32(h.seq).i64(h.tsMs).u16(h.width).u16(h.height).bytes()
        return head + jpeg
    }

    fun decode(bytes: ByteArray): Pair<FrameHeader, ByteArray> {
        val r = LeReader(bytes)
        val v = r.u8()
        if (v != com.guidehub.core.Constants.FRAME_HEADER_VERSION) throw ProtocolException("Unsupported frame header version $v")
        val h = FrameHeader(r.u32(), r.i64(), r.u16(), r.u16())
        val jpeg = r.rest()
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) throw ProtocolException("Frame payload is not a JPEG")
        return h to jpeg
    }
}
