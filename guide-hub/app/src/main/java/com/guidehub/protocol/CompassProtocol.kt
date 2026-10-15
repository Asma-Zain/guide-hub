package com.guidehub.protocol

/** ESP32 tactile compass wire format (PRD 7.1). All multi-byte values little-endian. */
enum class CompassOpcode(val code: Int) {
    SET_BEARING(0x01), SET_MODE(0x02), CALIBRATE(0x03), PING(0x04);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: throw ProtocolException("Unknown compass opcode $code")
    }
}

enum class CompassMode(val code: Int) {
    IDLE(0), NAVIGATE(1), ARRIVED(2), HAZARD(3), CALIBRATE(4);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: throw ProtocolException("Unknown compass mode $code")
    }
}

enum class AckStatus(val code: Int) {
    OK(0), CLAMPED(1), BUSY(2), ERR(3);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: throw ProtocolException("Unknown ack status $code")
    }
}

/** `u8 opcode, u8 seq, i16 bearingDeg, u8 mode, u8 flags` = 6 bytes. */
data class CompassCommand(
    val opcode: CompassOpcode,
    val seq: Int,
    val bearingDeg: Int,
    val mode: CompassMode,
    val flags: Int = 0,
)

/** `u8 seq, u8 status, i16 actualDeg` = 4 bytes. */
data class CompassAck(val seq: Int, val status: AckStatus, val actualDeg: Int)

/** `u8 battery%, u8 state, u16 uptimeS` = 4 bytes. */
data class CompassStatus(val batteryPct: Int, val state: Int, val uptimeS: Int)

object CompassCodec {
    const val CMD_SIZE = 6
    const val ACK_SIZE = 4
    const val STATUS_SIZE = 4

    fun encode(cmd: CompassCommand): ByteArray =
        LeWriter(CMD_SIZE).u8(cmd.opcode.code).u8(cmd.seq and 0xFF).i16(cmd.bearingDeg).u8(cmd.mode.code).u8(cmd.flags).bytes()

    fun decodeCommand(bytes: ByteArray): CompassCommand {
        if (bytes.size != CMD_SIZE) throw ProtocolException("CMD must be $CMD_SIZE bytes, got ${bytes.size}")
        val r = LeReader(bytes)
        return CompassCommand(CompassOpcode.of(r.u8()), r.u8(), r.i16(), CompassMode.of(r.u8()), r.u8())
    }

    fun encode(ack: CompassAck): ByteArray =
        LeWriter(ACK_SIZE).u8(ack.seq and 0xFF).u8(ack.status.code).i16(ack.actualDeg).bytes()

    fun decodeAck(bytes: ByteArray): CompassAck {
        if (bytes.size != ACK_SIZE) throw ProtocolException("ACK must be $ACK_SIZE bytes, got ${bytes.size}")
        val r = LeReader(bytes)
        return CompassAck(r.u8(), AckStatus.of(r.u8()), r.i16())
    }

    fun encode(status: CompassStatus): ByteArray =
        LeWriter(STATUS_SIZE).u8(status.batteryPct).u8(status.state).u16(status.uptimeS).bytes()

    fun decodeStatus(bytes: ByteArray): CompassStatus {
        if (bytes.size != STATUS_SIZE) throw ProtocolException("STATUS must be $STATUS_SIZE bytes, got ${bytes.size}")
        val r = LeReader(bytes)
        return CompassStatus(r.u8(), r.u8(), r.u16())
    }
}
