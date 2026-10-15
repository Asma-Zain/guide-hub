package com.guidehub.recording

import com.guidehub.core.BusEvent
import com.guidehub.core.ButtonEvent
import com.guidehub.core.ButtonSource
import com.guidehub.core.CameraObject
import com.guidehub.core.CompassAckEvent
import com.guidehub.core.DataSource
import com.guidehub.core.HazardsUpdate
import com.guidehub.core.LinkId
import com.guidehub.core.LinkState
import com.guidehub.core.LinkStatus
import com.guidehub.core.MarkerDetection
import com.guidehub.core.ObjectDetections
import com.guidehub.core.PositionUpdate
import com.guidehub.core.SpokenEvent
import com.guidehub.core.TofPoint
import com.guidehub.core.TofSweep
import com.guidehub.core.Utterance
import com.guidehub.core.VoDelta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * JSONL encoding of bus events for session recording/replay (FR-10). Sensor inputs round-trip so a
 * corridor run can be replayed offline; derived outputs (position, hazards, speech, acks) are
 * recorded one-way for analysis (drift, latency).
 */
object EventJson {
    fun encode(e: BusEvent): String? {
        val o: JsonObject = when (e) {
            is TofSweep -> buildJsonObject {
                put("type", "sweep"); put("ts", e.ts); put("src", e.source.name)
                putJsonArray("p") { e.points.forEach { p -> add(JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(p.angleDeg), kotlinx.serialization.json.JsonPrimitive(p.rangeM)))) } }
            }
            is VoDelta -> buildJsonObject { put("type", "vo"); put("ts", e.ts); put("dyaw", e.dYawDeg); put("dfwd", e.dForwardM); put("q", e.quality); put("src", e.source.name) }
            is MarkerDetection -> buildJsonObject {
                put("type", "marker"); put("ts", e.ts); put("id", e.id); put("r", e.rangeM); put("b", e.bearingDeg)
                e.markerYawDeg?.let { put("yaw", it) }; put("src", e.source.name)
            }
            is ObjectDetections -> buildJsonObject {
                put("type", "objects"); put("ts", e.ts)
                putJsonArray("o") {
                    e.objects.forEach { c ->
                        addJsonObject { put("l", c.label); put("s", c.score); put("b", c.bearingDeg); put("h", c.boxHeightPx); put("fh", c.frameHeightPx); put("t", c.top); put("bt", c.bottom) }
                    }
                }
            }
            is Utterance -> buildJsonObject { put("type", "utterance"); put("ts", e.ts); put("text", e.text); put("c", e.confidence) }
            is ButtonEvent -> buildJsonObject { put("type", "button"); put("ts", e.ts); put("src", e.source.name); put("id", e.buttonId); put("ev", e.event) }
            is LinkStatus -> buildJsonObject { put("type", "link"); put("ts", e.ts); put("link", e.link.name); put("state", e.state.name); put("detail", e.detail) }
            is PositionUpdate -> buildJsonObject {
                put("type", "position"); put("ts", e.ts); put("from", e.edgeFrom); put("to", e.edgeTo); put("s", e.s)
                put("x", e.x); put("y", e.y); put("yaw", e.yawDeg); put("conf", e.confidence); put("hconf", e.headingConfidence)
                e.lastCorrectionM?.let { put("corr", it) }
            }
            is HazardsUpdate -> buildJsonObject {
                put("type", "hazards"); put("ts", e.ts)
                putJsonArray("h") { e.hazards.forEach { h -> addJsonObject { put("l", h.label); put("r", h.rangeM); put("b", h.bearingDeg); put("sev", h.severity.name); put("z", h.heightZone.name) } } }
            }
            is SpokenEvent -> buildJsonObject { put("type", "spoken"); put("ts", e.ts); put("text", e.text); put("p", e.priority) }
            is CompassAckEvent -> buildJsonObject { put("type", "ack"); put("ts", e.ts); put("seq", e.seq); put("st", e.status); put("deg", e.actualDeg); put("lat", e.latencyMs) }
            else -> return null
        }
        return o.toString()
    }

    /** Decodes replayable input events; returns null for output-only or unknown lines. */
    fun decode(line: String): BusEvent? {
        val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val ts = o["ts"]?.jsonPrimitive?.long ?: return null
        fun src() = o["src"]?.jsonPrimitive?.content?.let { runCatching { DataSource.valueOf(it) }.getOrNull() } ?: DataSource.PHONE
        return when (o["type"]?.jsonPrimitive?.content) {
            "sweep" -> TofSweep(o["p"]!!.jsonArray.map { p -> val a = p.jsonArray; TofPoint(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double) }, ts, src())
            "vo" -> VoDelta(o["dyaw"]!!.jsonPrimitive.double, o["dfwd"]!!.jsonPrimitive.double, o["q"]!!.jsonPrimitive.double, ts, src())
            "marker" -> MarkerDetection(o["id"]!!.jsonPrimitive.int, o["r"]!!.jsonPrimitive.double, o["b"]!!.jsonPrimitive.double, o["yaw"]?.jsonPrimitive?.double, ts, src())
            "objects" -> ObjectDetections(o["o"]!!.jsonArray.map { c ->
                val j = c.jsonObject
                CameraObject(j["l"]!!.jsonPrimitive.content, j["s"]!!.jsonPrimitive.float, j["b"]!!.jsonPrimitive.double, j["h"]!!.jsonPrimitive.double,
                    j["fh"]!!.jsonPrimitive.int, j["t"]!!.jsonPrimitive.double, j["bt"]!!.jsonPrimitive.double)
            }, ts)
            "utterance" -> Utterance(o["text"]!!.jsonPrimitive.content, o["c"]!!.jsonPrimitive.float, ts)
            "button" -> ButtonEvent(ButtonSource.valueOf(o["src"]!!.jsonPrimitive.content), o["id"]!!.jsonPrimitive.int, o["ev"]!!.jsonPrimitive.int, ts)
            "link" -> LinkStatus(LinkId.valueOf(o["link"]!!.jsonPrimitive.content), LinkState.valueOf(o["state"]!!.jsonPrimitive.content), o["detail"]?.jsonPrimitive?.content ?: "", ts)
            else -> null
        }
    }
}
