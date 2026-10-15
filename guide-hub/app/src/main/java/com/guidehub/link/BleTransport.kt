package com.guidehub.link

import com.guidehub.core.LinkState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class BleNotification(val characteristic: UUID, val value: ByteArray, val ts: Long)

/**
 * GATT-central boundary. Implemented by [AndroidBleTransport] for real hardware and by the
 * simulator mocks (MockCompassTransport, MockHeadbandTransport).
 */
interface BleTransport {
    val name: String
    val state: StateFlow<LinkState>
    val notifications: SharedFlow<BleNotification>

    /** Connects (scanning if needed), discovers services, enables notifications. */
    suspend fun connect(): Boolean
    fun disconnect()
    suspend fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean = false): Boolean
    suspend fun read(characteristic: UUID): ByteArray?
}

/** Exponential reconnect backoff: 1 s, 2 s, 4 s ... capped. */
class Backoff(private val initialMs: Long = 1_000, private val maxMs: Long = 30_000) {
    private var attempt = 0
    fun next(): Long = (initialMs shl attempt.coerceAtMost(10)).coerceAtMost(maxMs).also { attempt++ }
    fun reset() { attempt = 0 }
}
