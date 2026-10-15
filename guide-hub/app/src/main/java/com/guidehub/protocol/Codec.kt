package com.guidehub.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolException(message: String) : Exception(message)

/** Little-endian reader with bounds checks that throw [ProtocolException] instead of crashing. */
internal class LeReader(private val bytes: ByteArray) {
    private val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val remaining: Int get() = buf.remaining()

    private fun need(n: Int) {
        if (buf.remaining() < n) throw ProtocolException("Truncated packet: need $n bytes at ${buf.position()}, have ${buf.remaining()} (total ${bytes.size})")
    }

    fun u8(): Int { need(1); return buf.get().toInt() and 0xFF }
    fun i8(): Int { need(1); return buf.get().toInt() }
    fun u16(): Int { need(2); return buf.short.toInt() and 0xFFFF }
    fun i16(): Int { need(2); return buf.short.toInt() }
    fun u32(): Long { need(4); return buf.int.toLong() and 0xFFFFFFFFL }
    fun i64(): Long { need(8); return buf.long }
    fun rest(): ByteArray { val out = ByteArray(buf.remaining()); buf.get(out); return out }
}

internal class LeWriter(size: Int) {
    private val buf: ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun u8(v: Int) = apply { require(v in 0..255) { "u8 out of range: $v" }; buf.put(v.toByte()) }
    fun i8(v: Int) = apply { require(v in -128..127) { "i8 out of range: $v" }; buf.put(v.toByte()) }
    fun u16(v: Int) = apply { require(v in 0..0xFFFF) { "u16 out of range: $v" }; buf.putShort(v.toShort()) }
    fun i16(v: Int) = apply { require(v in Short.MIN_VALUE..Short.MAX_VALUE) { "i16 out of range: $v" }; buf.putShort(v.toShort()) }
    fun u32(v: Long) = apply { require(v in 0..0xFFFFFFFFL) { "u32 out of range: $v" }; buf.putInt(v.toInt()) }
    fun i64(v: Long) = apply { buf.putLong(v) }
    fun bytes(): ByteArray = buf.array()
}
