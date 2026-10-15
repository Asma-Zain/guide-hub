package com.guidehub.recording

import com.guidehub.core.BusEvent
import com.guidehub.core.Clock
import com.guidehub.core.EventBus
import com.guidehub.core.Utterance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes bus events to app-private JSONL files (filesDir/recordings). Frames are never written here;
 * frame capture is a separate, explicitly enabled debug option.
 */
class SessionRecorder(private val dir: File, private val bus: EventBus) {
    private var writer: BufferedWriter? = null
    var currentFile: File? = null; private set
    private val listener: (BusEvent) -> Unit = { e -> EventJson.encode(e)?.let { line -> write(line) } }

    @Synchronized
    fun start(): File {
        stop()
        dir.mkdirs()
        val name = "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jsonl"
        val f = File(dir, name)
        writer = f.bufferedWriter()
        currentFile = f
        bus.addListener(listener)
        return f
    }

    @Synchronized
    private fun write(line: String) {
        writer?.apply { write(line); newLine() }
    }

    @Synchronized
    fun stop() {
        bus.removeListener(listener)
        writer?.flush()
        writer?.close()
        writer = null
    }

    val recording: Boolean @Synchronized get() = writer != null

    fun list(): List<File> = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedByDescending { it.lastModified() }.orEmpty()
}

/** Replays recorded sensor inputs onto the bus with their original relative timing. */
class SessionReplayer(
    private val scope: CoroutineScope,
    private val bus: EventBus,
    private val clock: Clock,
    private val onUtterance: (String) -> Unit,
) {
    private var job: Job? = null

    fun replay(lines: List<String>, speed: Double = 1.0): Job {
        job?.cancel()
        val events = lines.mapNotNull { EventJson.decode(it) }
        return scope.launch {
            if (events.isEmpty()) return@launch
            val t0 = events.first().ts
            val start = clock.now()
            for (e in events) {
                val wait = start + ((e.ts - t0) / speed).toLong() - clock.now()
                if (wait > 0) delay(wait)
                if (e is Utterance) onUtterance(e.text) else bus.emit(rebase(e, clock.now()))
            }
        }.also { job = it }
    }

    fun stop() { job?.cancel() }

    private fun rebase(e: BusEvent, now: Long): BusEvent = when (e) {
        is com.guidehub.core.TofSweep -> e.copy(ts = now)
        is com.guidehub.core.VoDelta -> e.copy(ts = now)
        is com.guidehub.core.MarkerDetection -> e.copy(ts = now)
        is com.guidehub.core.ObjectDetections -> e.copy(ts = now)
        is com.guidehub.core.ButtonEvent -> e.copy(ts = now)
        is com.guidehub.core.LinkStatus -> e.copy(ts = now)
        else -> e
    }
}
