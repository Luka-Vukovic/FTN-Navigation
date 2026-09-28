package com.example.ftnnavigation.graph

import java.util.PriorityQueue
import kotlin.math.hypot

/**
 * Tačka na ivici grafa: [t] = 0 je čvor [from], 1 je [to]. Relativne koordinate plana
 * ([x]/[y]) se interpoliraju linearno - smeštaj plana je afin, pa je to ista tačka i u metrima.
 */
data class EdgePoint(val from: Node, val to: Node, val t: Double) {
    val x: Float get() = from.x + (to.x - from.x) * t.toFloat()
    val y: Float get() = from.y + (to.y - from.y) * t.toFloat()
}

/**
 * PDR pozicija posle map-matching-a: [point] je pozicija zalepljena za ivicu grafa (nju
 * prikazujemo i od nje računamo rutu), a [x]/[y] slobodna pozicija (relativno na plan) na koju
 * se dodaju koraci. Slobodna pozicija sme da odstupi od ivice najviše toleranciju matchera:
 * korak poprečno na hodnik se tako ne gubi (skretanje u vrata), a drift kompasa se ne
 * nagomilava.
 */
data class MatchedPosition(val point: EdgePoint, val x: Float, val y: Float)

/** Koliko slobodna pozicija sme da odstupi od ivice (m). TODO: proveriti na terenu (širina hodnika). */
const val MATCH_TOLERANCE_M = 2.0

/**
 * Map-matching PDR pozicije na ivice hoda ([EdgeType.HOD]) jednog sprata zgrade. Posle
 * svakog koraka pozicija se projektuje na najbližu ivicu, ali biraju se samo ivice do kojih se
 * od prethodne pozicije stiže po grafu u dometu koraka + tolerancije - pozicija ne može da
 * "preskoči" zid u susedni hodnik, čak i kad je on vazdušno bliži.
 *
 * Računa se u metrima plana (relativne koordinate × dimenzije); rotacija i pomeraj smeštaja
 * plana ne menjaju rastojanja.
 */
class MapMatcher(
    graph: BuildingGraph,
    buildingId: String,
    floor: Int,
    private val toleranceM: Double = MATCH_TOLERANCE_M,
) {
    private val scale = graph.placement(buildingId).scale

    private inner class Segment(val a: Node, val b: Node) {
        val ax = a.x * scale.widthM.toDouble()
        val ay = a.y * scale.heightM.toDouble()
        val bx = b.x * scale.widthM.toDouble()
        val by = b.y * scale.heightM.toDouble()
        val length = hypot(bx - ax, by - ay)

        fun other(node: Node) = if (node.id == a.id) b else a

        /** Udeo ivice (0..1) najbliži tački. */
        fun project(px: Double, py: Double): Double {
            if (length == 0.0) return 0.0
            return (((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / (length * length)).coerceIn(0.0, 1.0)
        }

        fun distance(px: Double, py: Double): Double {
            val t = project(px, py)
            return hypot(ax + (bx - ax) * t - px, ay + (by - ay) * t - py)
        }
    }

    private val segments: Map<Pair<String, String>, Segment>
    private val incident: Map<String, List<Segment>>

    init {
        fun onFloor(id: String) = graph.node(id)?.takeIf { it.buildingId == buildingId && it.floor == floor }
        segments = graph.edges.filter { it.type == EdgeType.HOD }.mapNotNull { edge ->
            val a = onFloor(edge.fromId) ?: return@mapNotNull null
            val b = onFloor(edge.toId) ?: return@mapNotNull null
            (a.id to b.id) to Segment(a, b)
        }.toMap()
        incident = segments.values.flatMap { listOf(it.a.id to it, it.b.id to it) }
            .groupBy({ it.first }, { it.second })
    }

    /** Početna pozicija: najbliža ivica sprata (bez ograničenja), ili null ako ih nema. */
    fun start(x: Float, y: Float): MatchedPosition? {
        val px = x * scale.widthM.toDouble()
        val py = y * scale.heightM.toDouble()
        val nearest = segments.values.minByOrNull { it.distance(px, py) } ?: return null
        return fix(nearest, px, py)
    }

    /** Jedan korak [dxM]/[dyM] (metri plana: x udesno, y naniže) od pozicije [from]. */
    fun step(from: MatchedPosition, dxM: Double, dyM: Double): MatchedPosition {
        val px = from.x * scale.widthM + dxM
        val py = from.y * scale.heightM + dyM
        val nearest = segmentsNear(from.point, hypot(dxM, dyM) + toleranceM).minBy { it.distance(px, py) }
        return fix(nearest, px, py)
    }

    /** Ivice do kojih se od [point] stiže po grafu u najviše [reachM] metara (i ivica same tačke). */
    private fun segmentsNear(point: EdgePoint, reachM: Double): Set<Segment> {
        val current = segments[point.from.id to point.to.id] ?: segments.getValue(point.to.id to point.from.id)
        val result = linkedSetOf(current)
        val settled = HashSet<String>()
        val queue = PriorityQueue<Pair<Double, Node>>(compareBy { it.first })
        queue += point.t * current.length to point.from
        queue += (1 - point.t) * current.length to point.to
        while (queue.isNotEmpty()) {
            val (distance, node) = queue.poll()!!
            if (distance > reachM) break
            if (!settled.add(node.id)) continue
            for (segment in incident[node.id].orEmpty()) {
                result += segment
                val next = segment.other(node)
                if (next.id !in settled) queue += distance + segment.length to next
            }
        }
        return result
    }

    /** Projekcija na [segment]; slobodna pozicija se privlači ivici ako je dalje od tolerancije. */
    private fun fix(segment: Segment, px: Double, py: Double): MatchedPosition {
        val t = segment.project(px, py)
        val mx = segment.ax + (segment.bx - segment.ax) * t
        val my = segment.ay + (segment.by - segment.ay) * t
        val offset = hypot(px - mx, py - my)
        val k = if (offset > toleranceM) toleranceM / offset else 1.0
        return MatchedPosition(
            EdgePoint(segment.a, segment.b, t),
            ((mx + (px - mx) * k) / scale.widthM).toFloat(),
            ((my + (py - my) * k) / scale.heightM).toFloat(),
        )
    }
}
