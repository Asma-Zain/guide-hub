package com.guidehub.nav

import kotlin.math.abs

/** An edge with a direction of travel. [headingDeg] is the heading when walking from -> to. */
data class DirectedEdge(
    val from: String,
    val to: String,
    val lengthM: Double,
    val headingDeg: Double,
    val width: Double,
) {
    fun reversed() = DirectedEdge(to, from, lengthM, Geo.norm360(headingDeg + 180.0), width)
    fun sameSegment(a: String?, b: String?) = (from == a && to == b) || (from == b && to == a)
    override fun toString() = "$from->$to"
}

data class Projection(val edge: DirectedEdge, val s: Double, val lateral: Double, val distance: Double)

class MapGraph(val map: BuildingMap) {
    val nodes: Map<String, MapNode> = map.nodes.associateBy { it.id }
    private val adjacency: Map<String, List<DirectedEdge>>
    private val markerToNode: Map<Int, MapNode> = map.nodes.mapNotNull { n -> n.marker?.let { it.id to n } }.toMap()
    private val landmarks: Map<String, String> = map.landmarks.associate { it.nodeId to it.say }

    init {
        val adj = mutableMapOf<String, MutableList<DirectedEdge>>()
        map.edges.forEach { e ->
            val de = DirectedEdge(e.from, e.to, e.lengthM, Geo.norm360(e.headingDeg), e.width)
            adj.getOrPut(e.from) { mutableListOf() } += de
            adj.getOrPut(e.to) { mutableListOf() } += de.reversed()
        }
        adjacency = adj
    }

    val allEdges: List<DirectedEdge> get() = adjacency.values.flatten()

    fun neighbours(nodeId: String): List<DirectedEdge> = adjacency[nodeId].orEmpty()

    fun edge(from: String, to: String): DirectedEdge? = adjacency[from]?.firstOrNull { it.to == to }

    fun nodeForMarker(markerId: Int): MapNode? = markerToNode[markerId]

    fun landmarkAt(nodeId: String): String? = landmarks[nodeId]

    fun displayName(nodeId: String): String = nodes[nodeId]?.names?.firstOrNull() ?: nodeId

    /** Nodes that can be navigated to by name. */
    val destinations: List<MapNode> get() = map.nodes.filter { it.names.isNotEmpty() }

    /** Position at distance [s] along [edge], interpolated between node coordinates. */
    fun pointOn(edge: DirectedEdge, s: Double): Pair<Double, Double> {
        val a = nodes.getValue(edge.from)
        val b = nodes.getValue(edge.to)
        val f = (s / edge.lengthM).coerceIn(0.0, 1.0)
        return (a.x + (b.x - a.x) * f) to (a.y + (b.y - a.y) * f)
    }

    /** Project a point onto one edge. Lateral is + to the right of the travel direction. */
    fun project(edge: DirectedEdge, x: Double, y: Double): Projection {
        val a = nodes.getValue(edge.from)
        val b = nodes.getValue(edge.to)
        val vx = b.x - a.x
        val vy = b.y - a.y
        val len2 = vx * vx + vy * vy
        val t = if (len2 < 1e-9) 0.0 else (((x - a.x) * vx + (y - a.y) * vy) / len2).coerceIn(0.0, 1.0)
        val px = a.x + vx * t
        val py = a.y + vy * t
        val segLen = kotlin.math.sqrt(len2)
        // Right-hand normal of (vx, vy) is (vy, -vx).
        val lateral = if (segLen < 1e-9) 0.0 else ((x - px) * vy + (y - py) * (-vx)) / segLen
        return Projection(edge, t * edge.lengthM, lateral, Geo.dist(x, y, px, py))
    }

    /**
     * Nearest edge to (x, y). Among equally near edges (e.g. at a node) prefer [preferred] edges,
     * then the orientation closest to [yawDeg].
     */
    fun nearestEdge(x: Double, y: Double, yawDeg: Double?, preferred: Collection<DirectedEdge> = emptyList()): Projection? {
        val candidates = map.edges.map { e -> DirectedEdge(e.from, e.to, e.lengthM, Geo.norm360(e.headingDeg), e.width) }
        if (candidates.isEmpty()) return null
        val scored = candidates.map { e ->
            val p = project(e, x, y)
            val pref = preferred.firstOrNull { it.sameSegment(e.from, e.to) }
            Triple(p, pref, p.distance - (if (pref != null) 0.75 else 0.0))
        }
        val best = scored.minBy { it.third }
        var proj = best.first
        val pref = best.second
        // Orientation: follow the preferred (route) direction if any, else the yaw.
        val flip = when {
            pref != null -> pref.from != proj.edge.from
            yawDeg != null -> abs(Geo.norm180(proj.edge.headingDeg - yawDeg)) > 90.0
            else -> false
        }
        if (flip) {
            val r = proj.edge.reversed()
            proj = Projection(r, r.lengthM - proj.s, -proj.lateral, proj.distance)
        }
        return proj
    }
}
