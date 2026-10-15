package com.guidehub.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** Unified event bus (FR-9). Non-suspending emit; slow collectors drop the oldest events. */
class EventBus(tailSize: Int = Constants.EVENT_TAIL_SIZE) {
    private val flow = MutableSharedFlow<BusEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<BusEvent> = flow.asSharedFlow()

    /** Last N events for the debug "event bus tail" view. */
    val tail = RingBuffer<BusEvent>(tailSize)

    private val listeners = mutableListOf<(BusEvent) -> Unit>()

    fun emit(event: BusEvent) {
        tail.add(event)
        synchronized(listeners) { listeners.toList() }.forEach { it(event) }
        flow.tryEmit(event)
    }

    /** Synchronous listener (used by the session recorder so nothing is dropped). */
    fun addListener(listener: (BusEvent) -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    fun removeListener(listener: (BusEvent) -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }
}

/** Fixed-size thread-safe ring buffer. */
class RingBuffer<T>(val capacity: Int) {
    private val items = ArrayDeque<T>(capacity)

    @Synchronized
    fun add(item: T) {
        if (items.size == capacity) items.removeFirst()
        items.addLast(item)
    }

    @Synchronized
    fun snapshot(): List<T> = items.toList()

    @Synchronized
    fun clear() = items.clear()

    val size: Int @Synchronized get() = items.size
}
