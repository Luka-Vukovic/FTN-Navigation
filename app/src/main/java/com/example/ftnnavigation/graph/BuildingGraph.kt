package com.example.ftnnavigation.graph

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot

/** Dimenzije plana sprata u metrima - pretvaraju relativne koordinate čvorova u metre. */
data class FloorScale(val widthM: Float, val heightM: Float)

/**
 * Težine za A*; sva vremena su u sekundama. [crowdFactor] (gužva između časova) množi
 * vreme hoda i stepenica - mora biti >= 1, inače heuristika precenjuje i A* nije optimalan.
 */
data class RoutingProfile(
    val walkingSpeedMps: Double = 1.3,
    val stairsUpSecPerFloor: Double = 18.0,
    val stairsDownSecPerFloor: Double = 12.0,
    val liftWaitSec: Double = 45.0,
    val liftSecPerFloor: Double = 4.0,
    /** Npr. kolica ili povreda - stepenice se ne koriste, ostaje lift. */
    val avoidStairs: Boolean = false,
    val crowdFactor: Double = 1.0,
) {
    init {
        require(walkingSpeedMps > 0) { "walkingSpeedMps mora biti > 0" }
        require(crowdFactor >= 1.0) { "crowdFactor mora biti >= 1" }
    }
}

data class Route(val nodes: List<Node>, val durationSec: Double)

/**
 * Graf jedne zgrade učitan iz baze. Svi spratovi dele [scale] (u PoC-u svi koriste isti
 * placeholder plan); kad stignu pravi planovi, skala ide po spratu.
 */
class BuildingGraph(
    val nodes: List<Node>,
    val edges: List<Edge>,
    private val scale: FloorScale,
) {
    private val byId = nodes.associateBy { it.id }
    private val adjacency: Map<String, List<Pair<Node, EdgeType>>>

    init {
        val adj = HashMap<String, MutableList<Pair<Node, EdgeType>>>()
        for (edge in edges) {
            val a = requireNotNull(byId[edge.fromId]) { "Ivica ka nepostojećem čvoru: ${edge.fromId}" }
            val b = requireNotNull(byId[edge.toId]) { "Ivica ka nepostojećem čvoru: ${edge.toId}" }
            val floors = abs(a.floor - b.floor)
            require(
                when (edge.type) {
                    EdgeType.HOD -> floors == 0
                    EdgeType.STEPENICE -> floors == 1
                    EdgeType.LIFT -> floors >= 1
                },
            ) { "Ivica ${edge.type} ${a.id} - ${b.id} povezuje pogrešne spratove" }
            adj.getOrPut(a.id) { mutableListOf() } += b to edge.type
            adj.getOrPut(b.id) { mutableListOf() } += a to edge.type
        }
        adjacency = adj
    }

    val rooms: List<Node> get() = nodes.filter { it.type == NodeType.PROSTORIJA }

    fun node(id: String): Node? = byId[id]

    /** Sala po nazivu iz rasporeda. */
    fun room(name: String): Node? = nodes.find { it.type == NodeType.PROSTORIJA && it.name == name }

    /** Najbrža ruta po [profile], ili null ako je nema (nepoznat čvor ili nedostižan). */
    fun route(fromId: String, toId: String, profile: RoutingProfile = RoutingProfile()): Route? {
        val start = byId[fromId] ?: return null
        val goal = byId[toId] ?: return null
        val best = hashMapOf(fromId to 0.0)
        val cameFrom = HashMap<String, String>()
        // (f, g, čvor). Heuristika je dopustiva ali ne i konzistentna (menja oblik pri promeni
        // sprata), pa čvor može ponovo da se otvori - zato nema zatvorenog skupa.
        val open = PriorityQueue<Triple<Double, Double, String>>(compareBy { it.first })
        open += Triple(heuristic(start, goal, profile), 0.0, fromId)

        while (open.isNotEmpty()) {
            val (_, g, id) = open.poll()!!
            if (g > best.getValue(id)) continue // zastareo unos
            if (id == toId) return Route(path(cameFrom, toId), g)
            val node = byId.getValue(id)
            for ((next, type) in neighbors(id)) {
                val nextG = g + (cost(node, next, type, profile) ?: continue)
                if (nextG < (best[next.id] ?: Double.POSITIVE_INFINITY)) {
                    best[next.id] = nextG
                    cameFrom[next.id] = id
                    open += Triple(nextG + heuristic(next, goal, profile), nextG, next.id)
                }
            }
        }
        return null
    }

    internal fun neighbors(id: String): List<Pair<Node, EdgeType>> = adjacency[id].orEmpty()

    /** Vreme prolaska ivice u sekundama; null = profil je ne dozvoljava. */
    internal fun cost(from: Node, to: Node, type: EdgeType, profile: RoutingProfile): Double? = when (type) {
        EdgeType.HOD -> distanceM(from, to) / profile.walkingSpeedMps * profile.crowdFactor
        EdgeType.STEPENICE -> if (profile.avoidStairs) null else {
            val floors = to.floor - from.floor
            val perFloor = if (floors > 0) profile.stairsUpSecPerFloor else profile.stairsDownSecPerFloor
            abs(floors) * perFloor * profile.crowdFactor
        }
        EdgeType.LIFT -> profile.liftWaitSec + abs(to.floor - from.floor) * profile.liftSecPerFloor
    }

    /**
     * Donja granica preostalog vremena: na istom spratu vazdušna linija, inače najjeftinija
     * promena sprata (horizontalni deo se preskače - planovi spratova ne moraju biti poravnati).
     */
    private fun heuristic(node: Node, goal: Node, profile: RoutingProfile): Double {
        if (node.floor == goal.floor) return distanceM(node, goal) / profile.walkingSpeedMps
        val perFloor = minOf(profile.stairsUpSecPerFloor, profile.stairsDownSecPerFloor, profile.liftSecPerFloor)
        return abs(node.floor - goal.floor) * perFloor
    }

    private fun distanceM(a: Node, b: Node): Double =
        hypot((a.x - b.x) * scale.widthM.toDouble(), (a.y - b.y) * scale.heightM.toDouble())

    private fun path(cameFrom: Map<String, String>, toId: String): List<Node> =
        generateSequence(toId) { cameFrom[it] }.map(byId::getValue).toList().asReversed()
}
