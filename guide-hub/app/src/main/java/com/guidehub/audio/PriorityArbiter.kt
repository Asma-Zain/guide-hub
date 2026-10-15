package com.guidehub.audio

/** FR-7 priority order: CRITICAL hazard > WARNING hazard > navigation instruction > info/confirmation. */
enum class Priority(val level: Int) { INFO(0), NAVIGATION(1), WARNING(2), CRITICAL(3) }

data class SpeechItem(val id: Long, val text: String, val priority: Priority, val key: String? = null)

/** TTS boundary used by the arbiter. [speak] must eventually call [PriorityArbiter.onDone] with the item id. */
interface Speaker {
    fun speak(item: SpeechItem)
    fun stop()
}

/**
 * Speech arbitration. Higher priority interrupts lower; interrupted navigation instructions are
 * re-queued and replayed afterwards; interrupted info/hazard speech is dropped (it is stale).
 * While [paused] (mic open), only CRITICAL speech plays.
 */
class PriorityArbiter(private val speaker: Speaker) {
    private var nextId = 1L
    var current: SpeechItem? = null; private set
    private val queue = ArrayList<SpeechItem>()
    var paused = false; private set

    @Synchronized
    fun submit(text: String, priority: Priority, key: String? = null): SpeechItem {
        val item = SpeechItem(nextId++, text, priority, key)
        // Keyed items replace older queued items with the same key (e.g. the latest turn instruction).
        if (key != null) queue.removeAll { it.key == key }
        val cur = current
        when {
            paused && priority != Priority.CRITICAL -> enqueue(item)
            cur == null -> play(item)
            priority.level > cur.priority.level -> {
                speaker.stop()
                if (cur.priority == Priority.NAVIGATION) queue.add(0, cur)
                current = null
                play(item)
            }
            else -> enqueue(item)
        }
        return item
    }

    @Synchronized
    fun onDone(id: Long) {
        if (current?.id != id) return
        current = null
        playNext()
    }

    /** Mic is opening: stop everything except CRITICAL; keep navigation for later. */
    @Synchronized
    fun pause() {
        paused = true
        val cur = current ?: return
        if (cur.priority == Priority.CRITICAL) return
        speaker.stop()
        if (cur.priority == Priority.NAVIGATION) queue.add(0, cur)
        current = null
    }

    @Synchronized
    fun resume() {
        paused = false
        if (current == null) playNext()
    }

    @Synchronized
    fun clear() {
        queue.clear()
        if (current != null) speaker.stop()
        current = null
    }

    @Synchronized
    fun queued(): List<SpeechItem> = queue.toList()

    private fun enqueue(item: SpeechItem) {
        // Stable insertion: after all items with >= priority.
        val idx = queue.indexOfFirst { it.priority.level < item.priority.level }
        if (idx < 0) queue.add(item) else queue.add(idx, item)
    }

    private fun playNext() {
        val idx = if (paused) queue.indexOfFirst { it.priority == Priority.CRITICAL } else if (queue.isEmpty()) -1 else 0
        if (idx < 0) return
        play(queue.removeAt(idx))
    }

    private fun play(item: SpeechItem) {
        current = item
        speaker.speak(item)
    }
}
