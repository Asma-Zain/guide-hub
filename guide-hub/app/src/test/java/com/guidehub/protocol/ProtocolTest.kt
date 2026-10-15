package com.guidehub.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTest {

    @Test fun compassCommandRoundTrip() {
        for (deg in listOf(-180, -90, -1, 0, 1, 45, 179, 180)) for (mode in CompassMode.entries) {
            val c = CompassCommand(CompassOpcode.SET_BEARING, 200, deg, mode, 0x5A)
            assertEquals(c, CompassCodec.decodeCommand(CompassCodec.encode(c)))
        }
    }

    @Test fun compassCommandIsLittleEndian() {
        val bytes = CompassCodec.encode(CompassCommand(CompassOpcode.SET_BEARING, 7, -90, CompassMode.NAVIGATE))
        // -90 = 0xFFA6 -> LE A6 FF
        assertArrayEquals(byteArrayOf(0x01, 0x07, 0xA6.toByte(), 0xFF.toByte(), 0x01, 0x00), bytes)
    }

    @Test fun seqWrapsToByte() {
        val c = CompassCodec.decodeCommand(CompassCodec.encode(CompassCommand(CompassOpcode.PING, 300, 0, CompassMode.IDLE)))
        assertEquals(300 and 0xFF, c.seq)
    }

    @Test fun ackAndStatusRoundTrip() {
        val a = CompassAck(255, AckStatus.CLAMPED, -170)
        assertEquals(a, CompassCodec.decodeAck(CompassCodec.encode(a)))
        val s = CompassStatus(100, 3, 65535)
        assertEquals(s, CompassCodec.decodeStatus(CompassCodec.encode(s)))
    }

    @Test fun compassMalformed() {
        assertThrows(ProtocolException::class.java) { CompassCodec.decodeAck(byteArrayOf(1, 2, 3)) }
        assertThrows(ProtocolException::class.java) { CompassCodec.decodeCommand(byteArrayOf(9, 0, 0, 0, 1, 0)) } // bad opcode
        assertThrows(ProtocolException::class.java) { CompassCodec.decodeCommand(byteArrayOf(1, 0, 0, 0, 77, 0)) } // bad mode
        assertThrows(ProtocolException::class.java) { CompassCodec.decodeAck(byteArrayOf(1, 9, 0, 0)) } // bad status
        assertThrows(ProtocolException::class.java) { CompassCodec.decodeStatus(ByteArray(0)) }
    }

    @Test fun sweepRoundTrip() {
        val p = TofSweepPacket(0xFFFFFFF0L, (-60..60 step 5).map { it to (it + 61) * 30 })
        assertEquals(p, HeadbandCodec.decodeSweep(HeadbandCodec.encode(p)))
        val empty = TofSweepPacket(0, emptyList())
        assertEquals(empty, HeadbandCodec.decodeSweep(HeadbandCodec.encode(empty)))
    }

    @Test fun sweepMalformed() {
        val ok = HeadbandCodec.encode(TofSweepPacket(1, listOf(0 to 1000, 5 to 1100)))
        assertThrows(ProtocolException::class.java) { HeadbandCodec.decodeSweep(ok.copyOf(ok.size - 1)) }
        assertThrows(ProtocolException::class.java) { HeadbandCodec.decodeSweep(ok + byteArrayOf(0)) }
        assertThrows(ProtocolException::class.java) { HeadbandCodec.decodeSweep(byteArrayOf(1, 2)) }
        assertThrows(ProtocolException::class.java) { HeadbandCodec.encode(TofSweepPacket(1, List(62) { 0 to 1 })) }
    }

    @Test fun voMarkerButtonStatusRoundTrip() {
        val vo = VoPacket(123456, -1234, 450, 200)
        assertEquals(vo, HeadbandCodec.decodeVo(HeadbandCodec.encode(vo)))
        val m1 = MarkerPacket(99, 12, 345, -123, null)
        assertEquals(m1, HeadbandCodec.decodeMarker(HeadbandCodec.encode(m1)))
        val m2 = MarkerPacket(99, 65535, 0, 1800, -450)
        assertEquals(m2, HeadbandCodec.decodeMarker(HeadbandCodec.encode(m2)))
        val b = ButtonPacket(2, ButtonPacket.DOUBLE_PRESS)
        assertEquals(b, HeadbandCodec.decodeButton(HeadbandCodec.encode(b)))
        val s = HeadbandStatus(80, -5, 2)
        assertEquals(s, HeadbandCodec.decodeStatus(HeadbandCodec.encode(s)))
    }

    @Test fun headbandMalformed() {
        assertThrows(ProtocolException::class.java) { HeadbandCodec.decodeVo(ByteArray(8)) }
        assertThrows(ProtocolException::class.java) { HeadbandCodec.decodeMarker(ByteArray(11)) }
        assertThrows(ProtocolException::class.java) { HeadbandCodec.decodeButton(ByteArray(3)) }
        assertThrows(ProtocolException::class.java) { HeadbandCodec.decodeStatus(ByteArray(2)) }
        assertThrows(ProtocolException::class.java) { HeadbandCodec.decodeCtrl(byteArrayOf(42)) }
        assertThrows(ProtocolException::class.java) { HeadbandCodec.decodeCtrl(byteArrayOf(3, 1)) } // truncated range
        assertThrows(ProtocolException::class.java) { HeadbandCodec.decodeCtrl(byteArrayOf(1, 0)) } // trailing byte
        assertThrows(ProtocolException::class.java) { HeadbandCodec.decodeCtrl(ByteArray(0)) }
    }

    @Test fun ctrlRoundTrip() {
        listOf(CtrlCommand.StartSweep, CtrlCommand.StopSweep, CtrlCommand.SetSweepRange(-60, 60), CtrlCommand.SetFrameRate(8), CtrlCommand.Shutdown)
            .forEach { assertEquals(it, HeadbandCodec.decodeCtrl(HeadbandCodec.encode(it))) }
    }

    @Test fun frameRoundTripAndRejectsNonJpeg() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val h = FrameHeader(42, 1_700_000_000_123, 640, 480)
        val (h2, j2) = FrameCodec.decode(FrameCodec.encode(h, jpeg))
        assertEquals(h, h2)
        assertArrayEquals(jpeg, j2)
        assertThrows(ProtocolException::class.java) { FrameCodec.decode(FrameCodec.encode(h, byteArrayOf(1, 2, 3, 4))) }
        assertThrows(ProtocolException::class.java) { FrameCodec.decode(byteArrayOf(2) + FrameCodec.encode(h, jpeg).drop(1)) }
        assertThrows(ProtocolException::class.java) { FrameCodec.decode(ByteArray(5)) }
    }

    @Test fun markerWithoutYawDecodesNull() {
        assertNull(HeadbandCodec.decodeMarker(HeadbandCodec.encode(MarkerPacket(1, 2, 3, 4, null))).markerYawDeciDeg)
    }
}
