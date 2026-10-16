package com.guidehub

import com.guidehub.audio.Priority
import com.guidehub.core.AudioOutput
import com.guidehub.core.Earcon
import com.guidehub.core.Hazard
import com.guidehub.core.MicRouting
import com.guidehub.nav.BuildingMap
import com.guidehub.nav.MapGraph
import com.guidehub.nav.MapParser
import java.io.File

object TestMaps {
    fun demoText(): String {
        // Unit tests run with the module directory as working directory.
        val f = listOf("src/main/assets/maps/corridor_demo.json", "app/src/main/assets/maps/corridor_demo.json").map(::File).first { it.exists() }
        return f.readText()
    }

    fun demo(): BuildingMap = MapParser.parse(demoText()).map!!
    fun demoGraph() = MapGraph(demo())

    fun asset(path: String): String =
        listOf("src/main/assets/$path", "app/src/main/assets/$path").map(::File).first { it.exists() }.readText()
}

data class Said(val text: String, val priority: Priority)

class FakeAudio(override var stereo: Boolean = true) : AudioOutput {
    val said = mutableListOf<Said>()
    val earcons = mutableListOf<Earcon>()
    var lastHazards: List<Hazard> = emptyList()
    var listening = false
    var vibrations = 0
    var volume = 0.5f
    var speechStops = 0

    override fun speak(text: String, priority: Priority, key: String?) { said += Said(text, priority) }
    override fun earcon(e: Earcon) { earcons += e }
    override fun hazardCue(hazards: List<Hazard>) { lastHazards = hazards }
    override fun beginListening() { listening = true }
    override fun endListening() { listening = false }
    override fun stopSpeech() { speechStops++ }
    override fun volumeStep(delta: Int): Float { volume = (volume + delta * 0.1f).coerceIn(0.1f, 1f); return volume }
    override fun vibrate() { vibrations++ }
    override suspend fun awaitSpeechDone() {}

    fun texts() = said.map { it.text }
    fun any(sub: String) = said.any { it.text.contains(sub, ignoreCase = true) }
    fun count(sub: String) = said.count { it.text.contains(sub, ignoreCase = true) }
}

class FakeMic : MicRouting {
    var opened = 0
    var closed = 0
    override suspend fun openMic(): String { opened++; return "phone" }
    override suspend fun closeMic() { closed++ }
}
