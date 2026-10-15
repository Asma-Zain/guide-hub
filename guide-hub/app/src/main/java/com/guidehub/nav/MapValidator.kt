package com.guidehub.nav

import com.guidehub.core.Constants

data class ValidationResult(val errors: List<String>, val warnings: List<String>)

/** Rejects structurally invalid maps; warns about maps that will navigate poorly. */
object MapValidator {
    private val NODE_TYPES = setOf("junction", "room", "corridor", "entrance", "stairs", "lift", "marker")

    fun validate(map: BuildingMap): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (map.units != "m") errors += "Only metre units are supported (got '${map.units}')"
        if (map.nodes.isEmpty()) errors += "Map has no nodes"

        val ids = map.nodes.groupBy { it.id }
        ids.filter { it.value.size > 1 }.keys.forEach { errors += "Duplicate node id '$it'" }
        map.nodes.forEach { n ->
            if (n.id.isBlank()) errors += "Node with blank id"
            if (!n.x.isFinite() || !n.y.isFinite()) errors += "Node '${n.id}' has non-finite coordinates"
            if (n.type !in NODE_TYPES) warnings += "Node '${n.id}' has unknown type '${n.type}'"
            if (n.type == "room" && n.names.isEmpty()) errors += "Room node '${n.id}' has no names"
            n.marker?.let { m ->
                if (m.id < 0 || m.id > 0xFFFF) errors += "Node '${n.id}' marker id ${m.id} out of range"
                if (!m.facingDeg.isFinite()) errors += "Node '${n.id}' marker facingDeg invalid"
            }
        }
        map.nodes.mapNotNull { n -> n.marker?.let { it.id to n.id } }.groupBy { it.first }
            .filter { it.value.size > 1 }
            .forEach { (mid, nodes) -> errors += "Marker id $mid used by several nodes: ${nodes.map { it.second }}" }

        val nodeById = map.nodes.associateBy { it.id }
        val seenEdges = mutableSetOf<Pair<String, String>>()
        map.edges.forEach { e ->
            val a = nodeById[e.from]
            val b = nodeById[e.to]
            if (a == null) errors += "Edge ${e.from}->${e.to}: unknown node '${e.from}'"
            if (b == null) errors += "Edge ${e.from}->${e.to}: unknown node '${e.to}'"
            if (e.from == e.to) errors += "Edge ${e.from}->${e.to} is a self-loop"
            if (!(e.lengthM > 0.0)) errors += "Edge ${e.from}->${e.to} must have positive lengthM"
            if (!e.headingDeg.isFinite() || e.headingDeg < -360 || e.headingDeg > 360) errors += "Edge ${e.from}->${e.to} headingDeg invalid"
            if (!(e.width > 0.0)) errors += "Edge ${e.from}->${e.to} must have positive width"
            val key = if (e.from < e.to) e.from to e.to else e.to to e.from
            if (!seenEdges.add(key)) errors += "Duplicate edge between ${e.from} and ${e.to}"
            if (a != null && b != null && e.lengthM > 0) {
                val geomLen = Geo.dist(a.x, a.y, b.x, b.y)
                if (geomLen > 0.01 && kotlin.math.abs(geomLen - e.lengthM) / e.lengthM > 0.2)
                    warnings += "Edge ${e.from}->${e.to}: lengthM ${e.lengthM} differs from coordinates (${"%.1f".format(geomLen)} m)"
                val geomHeading = Geo.bearing(a.x, a.y, b.x, b.y)
                if (geomLen > 0.01 && Geo.angleDiff(geomHeading, e.headingDeg) > 15)
                    warnings += "Edge ${e.from}->${e.to}: headingDeg ${e.headingDeg} differs from coordinates (${"%.0f".format(geomHeading)})"
            }
        }
        map.landmarks.forEach { l -> if (l.nodeId !in nodeById) errors += "Landmark refers to unknown node '${l.nodeId}'" }

        if (errors.isEmpty() && map.nodes.isNotEmpty()) {
            // Connectivity
            val adj = mutableMapOf<String, MutableList<String>>()
            map.edges.forEach { adj.getOrPut(it.from) { mutableListOf() } += it.to; adj.getOrPut(it.to) { mutableListOf() } += it.from }
            val seen = mutableSetOf(map.nodes.first().id)
            val stack = ArrayDeque(listOf(map.nodes.first().id))
            while (stack.isNotEmpty()) adj[stack.removeLast()].orEmpty().forEach { if (seen.add(it)) stack.addLast(it) }
            val unreachable = map.nodes.map { it.id }.filter { it !in seen }
            if (unreachable.isNotEmpty()) errors += "Map is not connected; unreachable nodes: $unreachable"

            // Marker density (FR-5): every junction should carry a marker and markers should be <= ~15 m apart.
            map.nodes.filter { it.type == "junction" && it.marker == null }
                .forEach { warnings += "markerSpacing: junction '${it.id}' has no marker" }
            markerGaps(map, adj).forEach { warnings += it }
            if (map.nodes.none { it.marker != null }) warnings += "markerSpacing: map has no markers; position can never be fixed"
        }
        return ValidationResult(errors, warnings)
    }

    /**
     * Marker-free stretches longer than [Constants.MAX_MARKER_SPACING_M]: walks each corridor chain
     * (through non-branching, marker-less nodes) and sums its length.
     */
    private fun markerGaps(map: BuildingMap, adj: Map<String, List<String>>): List<String> {
        val byId = map.nodes.associateBy { it.id }
        val lengths = map.edges.associate { setOf(it.from, it.to) to it.lengthM }
        val out = mutableListOf<String>()
        val reported = mutableSetOf<Set<String>>()
        // Start a walk from every marker node (or branch/dead end) along each incident edge.
        val anchors = map.nodes.filter { it.marker != null || adj[it.id].orEmpty().size != 2 }.map { it.id }
        for (start in anchors) for (first in adj[start].orEmpty()) {
            var prev = start
            var cur = first
            var total = lengths[setOf(prev, cur)] ?: 0.0
            var guard = 0
            while (byId[cur]?.marker == null && adj[cur].orEmpty().size == 2 && guard++ < 100) {
                val next = adj[cur]!!.first { it != prev }
                total += lengths[setOf(cur, next)] ?: 0.0
                prev = cur
                cur = next
            }
            val key = setOf(start, cur)
            val bothMarkerless = byId[start]?.marker == null && byId[cur]?.marker == null
            if (total > Constants.MAX_MARKER_SPACING_M && reported.add(key)) {
                out += if (bothMarkerless) "markerSpacing: $start..$cur (${"%.1f".format(total)} m) has no marker at either end"
                else "markerSpacing: $start..$cur is ${"%.1f".format(total)} m without a marker (max ${Constants.MAX_MARKER_SPACING_M})"
            }
        }
        return out
    }
}
