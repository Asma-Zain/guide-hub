package com.guidehub.nav

import com.guidehub.core.Constants
import com.guidehub.core.PositionUpdate

data class Guidance(
    /** Instructions to speak now (navigation priority). */
    val announcements: List<String>,
    /** Where the needle should point relative to head-forward; null = no target. */
    val relativeBearingDeg: Double?,
    val arrived: Boolean,
    val deviated: Boolean,
    val remainingM: Double,
    val stepIndex: Int,
    val stepChanged: Boolean,
)

/**
 * Tracks progress along a [Route] from fused position updates and produces turn-by-turn guidance.
 * Pure logic: no audio, no BLE.
 */
class NavigationController(private val graph: MapGraph) {
    var route: Route? = null; private set
    var stepIndex = 0; private set
    var lastInstruction: String? = null; private set
    var remainingM: Double = 0.0; private set

    private val announced = mutableSetOf<String>()

    fun start(r: Route) {
        route = r
        stepIndex = 0
        announced.clear()
        remainingM = r.totalM
        lastInstruction = null
    }

    fun stop() {
        route = null
        announced.clear()
        lastInstruction = null
    }

    /** First instruction after route planning, phrased relative to the user's current yaw. */
    fun initialInstruction(yawDeg: Double?): String {
        val r = route ?: return ""
        val first = r.steps.first()
        if (first.turnType == TurnType.ARRIVE) return "You are already at ${graph.displayName(r.destination)}."
        val turn = yawDeg?.let { Turns.classify(first.headingDeg - it) } ?: TurnType.STRAIGHT
        val lead = when (turn) {
            TurnType.STRAIGHT -> "Walk straight ahead"
            TurnType.U_TURN -> "Turn around, then walk"
            else -> "${Turns.phrase(turn)}, then walk"
        }
        val next = r.steps.getOrNull(1)
        val tail = when {
            next == null || next.turnType == TurnType.ARRIVE -> "to ${graph.displayName(r.destination)}"
            else -> "then ${Turns.phrase(next.turnType).lowercase()}"
        }
        return "$lead ${Turns.metres(first.distanceM)}, $tail.".also { lastInstruction = it }
    }

    fun update(pos: PositionUpdate): Guidance {
        val r = route ?: return Guidance(emptyList(), null, false, false, 0.0, 0, false)
        val out = mutableListOf<String>()

        if (r.edges.isEmpty()) {
            return Guidance(emptyList(), null, true, false, 0.0, r.steps.lastIndex, false)
        }
        val k = r.indexOf(pos.edgeFrom, pos.edgeTo)
        if (k < 0) {
            return Guidance(emptyList(), null, false, true, remainingM, stepIndex, false)
        }
        val progress = r.offsetOf(k) + pos.s
        remainingM = (r.totalM - progress).coerceAtLeast(0.0)

        if (remainingM <= Constants.ARRIVAL_RADIUS_M) {
            val text = "You have arrived at ${graph.displayName(r.destination)}." +
                (graph.landmarkAt(r.destination)?.let { " ${it.replaceFirstChar(Char::uppercase)}." } ?: "")
            lastInstruction = text
            return Guidance(listOf(text), null, true, false, remainingM, r.steps.lastIndex, true)
        }

        val newStep = r.steps.indexOfFirst { k in it.firstEdge..it.lastEdge }.coerceAtLeast(0)
        val stepChanged = newStep != stepIndex
        stepIndex = newStep
        val step = r.steps[stepIndex]
        val next = r.steps[stepIndex + 1]
        val stepEnd = r.offsetOf(step.lastEdge + 1)
        val toTurn = stepEnd - progress

        // Landmarks at intermediate nodes inside a straight leg.
        for (i in step.firstEdge until step.lastEdge) {
            val node = r.edges[i].to
            val lm = graph.landmarkAt(node) ?: continue
            val d = r.offsetOf(i + 1) - progress
            if (d in -0.5..2.5 && announced.add("lm:$node")) out += lm.replaceFirstChar(Char::uppercase) + "."
        }

        if (next.turnType == TurnType.ARRIVE) {
            if (toTurn <= Constants.TURN_PREANNOUNCE_M && toTurn > Constants.ARRIVAL_RADIUS_M + 0.5 && announced.add("pre:$stepIndex")) {
                out += "In ${Turns.metres(toTurn)}, you will arrive at ${graph.displayName(r.destination)}."
            }
        } else {
            val lm = step.landmark?.let { ", $it" } ?: ""
            if (toTurn <= Constants.TURN_PREANNOUNCE_M && toTurn > Constants.TURN_NOW_M && announced.add("pre:$stepIndex")) {
                out += "${Turns.phrase(next.turnType)} in ${Turns.metres(toTurn)}$lm."
            }
            if (toTurn <= Constants.TURN_NOW_M && announced.add("now:$stepIndex")) {
                out += "${Turns.phrase(next.turnType)} now."
            }
        }
        if (out.isNotEmpty()) lastInstruction = out.last()

        return Guidance(out, relativeBearing(r, k, pos), false, false, remainingM, stepIndex, stepChanged)
    }

    /**
     * Needle target: bearing to the next waypoint minus estimated head yaw (FR-8).
     * Near the end of an edge (within TURN_NOW_M), the waypoint advances to the following node so the
     * turn angle appears at the junction.
     */
    private fun relativeBearing(r: Route, k: Int, pos: PositionUpdate): Double {
        val e = r.edges[k]
        val remainingOnEdge = e.lengthM - pos.s
        val target = if (remainingOnEdge <= Constants.TURN_NOW_M && k + 1 < r.edges.size) r.edges[k + 1].to else e.to
        val n = graph.nodes.getValue(target)
        val bearing = Geo.bearing(pos.x, pos.y, n.x, n.y)
        return Geo.norm180(bearing - pos.yawDeg)
    }

    fun describeRemaining(): String {
        val r = route ?: return "No active route."
        return "About ${Turns.metres(remainingM)} to ${graph.displayName(r.destination)}."
    }
}
