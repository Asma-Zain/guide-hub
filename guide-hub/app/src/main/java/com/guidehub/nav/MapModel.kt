package com.guidehub.nav

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Hand-written building map (PRD section 8). See assets/maps/map.schema.json.
 *
 * Marker `facingDeg`: the heading a user has when looking at the marker head-on
 * (i.e. the viewing direction, in the map frame).
 */
@Serializable
data class BuildingMap(
    val building: String,
    val floor: Int = 0,
    val units: String = "m",
    val nodes: List<MapNode>,
    val edges: List<MapEdge>,
    val landmarks: List<Landmark> = emptyList(),
)

@Serializable
data class MapNode(
    val id: String,
    val x: Double,
    val y: Double,
    val type: String = "corridor",
    val names: List<String> = emptyList(),
    val marker: MarkerRef? = null,
)

@Serializable
data class MarkerRef(
    val id: Int,
    val facingDeg: Double,
    /** Physical side length of the printed tag, metres. */
    @SerialName("sizeM") val sizeM: Double? = null,
)

@Serializable
data class MapEdge(
    val from: String,
    val to: String,
    val lengthM: Double,
    val headingDeg: Double,
    val width: Double = 2.0,
)

@Serializable
data class Landmark(val nodeId: String, val say: String)

data class MapLoadResult(
    val map: BuildingMap?,
    val errors: List<String>,
    val warnings: List<String>,
) {
    val ok: Boolean get() = map != null && errors.isEmpty()
}

object MapParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): MapLoadResult {
        val map = try {
            json.decodeFromString(BuildingMap.serializer(), text)
        } catch (e: Exception) {
            return MapLoadResult(null, listOf("Map file is not valid JSON for the map schema: ${e.message?.lineSequence()?.firstOrNull()}"), emptyList())
        }
        val v = MapValidator.validate(map)
        return MapLoadResult(if (v.errors.isEmpty()) map else null, v.errors, v.warnings)
    }
}
