package com.guidehub.nav

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt

enum class TurnType { START, STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, U_TURN, ARRIVE }

/**
 * One leg of a route: perform [turnType] at [fromNode], then walk [distanceM] to [nextNode].
 * [firstEdge]..[lastEdge] index into [Route.edges]. The final step is ARRIVE with distance 0.
 */
data class RouteStep(
    val turnType: TurnType,
    val distanceM: Double,
    val landmark: String?,
    val nextNode: String,
    val fromNode: String,
    val headingDeg: Double,
    val firstEdge: Int,
    val lastEdge: Int,
)

data class Route(
    val destination: String,
    val edges: List<DirectedEdge>,
    val steps: List<RouteStep>,
) {
    val totalM: Double get() = edges.sumOf { it.lengthM }
    val nodes: List<String> get() = if (edges.isEmpty()) listOf(destination) else listOf(edges.first().from) + edges.map { it.to }

    /** Distance from route start to the start of edge [i]. */
    fun offsetOf(i: Int): Double = edges.take(i).sumOf { it.lengthM }

    fun indexOf(from: String?, to: String?): Int = edges.indexOfFirst { it.from == from && it.to == to }
}

object Turns {
    fun classify(deltaDeg: Double): TurnType {
        val d = Geo.norm180(deltaDeg)
        val a = abs(d)
        return when {
            a < 20 -> TurnType.STRAIGHT
            a < 60 -> if (d > 0) TurnType.SLIGHT_RIGHT else TurnType.SLIGHT_LEFT
            a <= 135 -> if (d > 0) TurnType.RIGHT else TurnType.LEFT
            else -> TurnType.U_TURN
        }
    }

    fun phrase(t: TurnType): String = when (t) {
        TurnType.START -> "Start walking"
        TurnType.STRAIGHT -> "Continue straight"
        TurnType.SLIGHT_LEFT -> "Bear left"
        TurnType.SLIGHT_RIGHT -> "Bear right"
        TurnType.LEFT -> "Turn left"
        TurnType.RIGHT -> "Turn right"
        TurnType.U_TURN -> "Turn around"
        TurnType.ARRIVE -> "You have arrived"
    }

    fun metres(m: Double): String {
        val r = m.roundToInt().coerceAtLeast(1)
        return if (r == 1) "1 metre" else "$r metres"
    }
}

class Router(private val graph: MapGraph) {

    /** A* shortest path over the map graph. Returns null if unreachable. */
    fun shortestPath(start: String, goal: String): List<DirectedEdge>? {
        if (start !in graph.nodes || goal !in graph.nodes) return null
        if (start == goal) return emptyList()
        val goalNode = graph.nodes.getValue(goal)
        fun h(id: String): Double {
            val n = graph.nodes.getValue(id)
            // Euclidean distance is admissible only if map lengths are >= straight-line distance;
            // scale down slightly to stay admissible when hand-written lengths are a bit short.
            return 0.8 * Geo.dist(n.x, n.y, goalNode.x, goalNode.y)
        }
        val g = mutableMapOf(start to 0.0)
        val cameFrom = mutableMapOf<String, DirectedEdge>()
        val open = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
        open += start to h(start)
        val closed = mutableSetOf<String>()
        while (open.isNotEmpty()) {
            val (cur, _) = open.poll()!!
            if (cur == goal) break
            if (!closed.add(cur)) continue
            for (e in graph.neighbours(cur)) {
                val tentative = g.getValue(cur) + e.lengthM
                if (tentative < (g[e.to] ?: Double.POSITIVE_INFINITY)) {
                    g[e.to] = tentative
                    cameFrom[e.to] = e
                    open += e.to to tentative + h(e.to)
                }
            }
        }
        if (goal !in cameFrom) return null
        val path = ArrayDeque<DirectedEdge>()
        var node = goal
        while (node != start) {
            val e = cameFrom.getValue(node)
            path.addFirst(e)
            node = e.from
        }
        return path.toList()
    }

    fun route(start: String, goal: String): Route? = shortestPath(start, goal)?.let { buildRoute(goal, it) }

    /** Groups edges into legs separated by turns and produces [RouteStep]s. */
    fun buildRoute(destination: String, edges: List<DirectedEdge>): Route {
        val steps = mutableListOf<RouteStep>()
        var legStart = 0
        var legTurn = TurnType.START
        for (i in 1..edges.size) {
            val endOfLeg = i == edges.size || Turns.classify(edges[i].headingDeg - edges[i - 1].headingDeg) != TurnType.STRAIGHT
            if (endOfLeg) {
                val leg = edges.subList(legStart, i)
                steps += RouteStep(
                    turnType = legTurn,
                    distanceM = leg.sumOf { it.lengthM },
                    landmark = graph.landmarkAt(leg.last().to),
                    nextNode = leg.last().to,
                    fromNode = leg.first().from,
                    headingDeg = leg.first().headingDeg,
                    firstEdge = legStart,
                    lastEdge = i - 1,
                )
                if (i < edges.size) {
                    legTurn = Turns.classify(edges[i].headingDeg - edges[i - 1].headingDeg)
                    legStart = i
                }
            }
        }
        steps += RouteStep(TurnType.ARRIVE, 0.0, graph.landmarkAt(destination), destination, destination,
            edges.lastOrNull()?.headingDeg ?: 0.0, edges.size, edges.size - 1)
        return Route(destination, edges, steps)
    }
}
