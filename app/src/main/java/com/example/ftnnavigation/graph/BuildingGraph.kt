package com.example.ftnnavigation.graph

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Dimenzije plana sprata u metrima - pretvaraju relativne koordinate čvorova u metre. */
data class FloorScale(val widthM: Float, val heightM: Float)

/** Tačka u metrima zajedničkog koordinatnog sistema (kampusa); y raste naniže, kao na ekranu. */
data class PointM(val x: Double, val y: Double)

/**
 * Gde je plan u zajedničkom sistemu: tačka (0, 0) plana je u [originX]/[originY] (metri), a
 * plan je zarotiran za [rotationDeg] u smeru kazaljke na satu. Podrazumevano plan počinje u
 * koordinatnom početku bez rotacije - dovoljno za graf jedne zgrade.
 */
data class PlanPlacement(
    val scale: FloorScale,
    val originX: Double = 0.0,
    val originY: Double = 0.0,
    val rotationDeg: Double = 0.0,
) {
    private val cos = cos(Math.toRadians(rotationDeg))
    private val sin = sin(Math.toRadians(rotationDeg))

    /** Relativna tačka plana (0..1) u metre. */
    fun toMeters(x: Float, y: Float): PointM {
        val dx = x * scale.widthM.toDouble()
        val dy = y * scale.heightM.toDouble()
        return PointM(originX + dx * cos - dy * sin, originY + dx * sin + dy * cos)
    }
}

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

/** Ruta kroz [nodes]; [lengthM] je pređeni put po spratovima (bez vertikale stepenica/lifta). */
data class Route(val nodes: List<Node>, val durationSec: Double, val lengthM: Double) {
    /** Trajanje za prikaz: minuti zaokruženi naviše, najmanje 1. */
    val minutes: Int get() = ceil(durationSec / 60).toInt().coerceAtLeast(1)
}

/**
 * Graf učitan iz baze: zgrade i spoljni graf kampusa. Svaka zgrada ([Node.buildingId]) ima
 * svoj [PlanPlacement], pa su sve dužine u metrima istog sistema i ivica sme da spaja dve
 * zgrade (ulaz sa stazom, spojni prolaz). Svi spratovi zgrade dele smeštaj (u PoC-u svi
 * koriste isti placeholder plan); kad stignu pravi planovi, smeštaj ide po spratu.
 */
class BuildingGraph(
    val nodes: List<Node>,
    val edges: List<Edge>,
    private val placements: Map<String, PlanPlacement>,
) {
    /** Graf u kome sve zgrade dele plan [scale] bez pomeraja (npr. jedna zgrada). */
    constructor(nodes: List<Node>, edges: List<Edge>, scale: FloorScale) :
        this(nodes, edges, nodes.map { it.buildingId }.distinct().associateWith { PlanPlacement(scale) })

    private val byId = nodes.associateBy { it.id }
    private val positions = nodes.associate { it.id to placement(it.buildingId).toMeters(it.x, it.y) }
    private val adjacency: Map<String, List<Pair<Node, EdgeType>>>

    init {
        val adj = HashMap<String, MutableList<Pair<Node, EdgeType>>>()
        for (edge in edges) {
            val a = requireNotNull(byId[edge.fromId]) { "Ivica ka nepostojećem čvoru: ${edge.fromId}" }
            val b = requireNotNull(byId[edge.toId]) { "Ivica ka nepostojećem čvoru: ${edge.toId}" }
            val floors = abs(a.floor - b.floor)
            require(
                when (edge.type) {
                    // Spratovi različitih zgrada su samo nazivi (trem iz prizemlja Kule ulazi u AMF na -1).
                    EdgeType.HOD -> floors == 0 || a.buildingId != b.buildingId
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

    fun placement(buildingId: String): PlanPlacement =
        requireNotNull(placements[buildingId]) { "Nema smeštaja plana za zgradu $buildingId" }

    /** Položaj čvora u metrima zajedničkog sistema. */
    fun position(node: Node): PointM = positions.getValue(node.id)

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
            if (id == toId) {
                val nodes = path(cameFrom, toId)
                val lengthM = nodes.zipWithNext { a, b -> if (a.floor == b.floor || a.buildingId != b.buildingId) distanceM(a, b) else 0.0 }.sum()
                return Route(nodes, g, lengthM)
            }
            val node = byId.getValue(id)
            // Sala nije usputni čvor (ni sala sa dva ulaza, npr. 205, ni amfiteatar sa zadnjim vratima).
            if (node.type == NodeType.PROSTORIJA && id != fromId) continue
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

    /**
     * Čvor zgrade [buildingId] najbliži tački ([x]/[y] relativno na plan sprata), samo tipova
     * [types] ako su zadati, ili null ako sprat nema takvih čvorova.
     */
    fun nearestNode(buildingId: String, floor: Int, x: Float, y: Float, types: Set<NodeType>? = null): Node? {
        val point = placement(buildingId).toMeters(x, y)
        return nodes.filter { it.buildingId == buildingId && it.floor == floor && (types == null || it.type in types) }
            .minByOrNull { distanceM(position(it), point) }
    }

    /**
     * Ruta od proizvoljne tačke (npr. PDR pozicije): pravom linijom do najbližeg čvora, pa A*.
     * Prvi deo puta je uračunat u vreme i dužinu; [Route.nodes] počinje tim čvorom.
     */
    fun routeFrom(
        buildingId: String,
        floor: Int,
        x: Float,
        y: Float,
        toId: String,
        profile: RoutingProfile = RoutingProfile(),
        startTypes: Set<NodeType>? = null,
    ): Route? {
        val start = nearestNode(buildingId, floor, x, y, startTypes) ?: return null
        val legM = distanceM(position(start), placement(buildingId).toMeters(x, y))
        return route(start.id, toId, profile)?.withLeg(legM, profile)
    }

    /**
     * Ruta od tačke na ivici (PDR pozicija posle map-matching-a): kroz onaj kraj ivice koji daje
     * kraće ukupno vreme. Deo ivice do tog kraja je uračunat; [Route.nodes] počinje tim krajem.
     */
    fun routeFrom(point: EdgePoint, toId: String, profile: RoutingProfile = RoutingProfile()): Route? {
        val edgeM = distanceM(point.from, point.to)
        return listOf(point.from to point.t * edgeM, point.to to (1 - point.t) * edgeM)
            .mapNotNull { (end, legM) -> route(end.id, toId, profile)?.withLeg(legM, profile) }
            .minByOrNull { it.durationSec }
    }

    /** Ruta produžena hodom od [legM] metara pre prvog čvora. */
    private fun Route.withLeg(legM: Double, profile: RoutingProfile) = copy(
        durationSec = durationSec + legM / profile.walkingSpeedMps * profile.crowdFactor,
        lengthM = lengthM + legM,
    )

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
     * Donja granica preostalog vremena: na istom spratu vazdušna linija (i između zgrada -
     * sve je u metrima istog sistema), inače najjeftinija promena sprata (horizontalni deo se
     * preskače - planovi spratova ne moraju biti poravnati). Različite zgrade na različitim spratovima: 0
     * (nivoi zgrada nisu iste visine - prolaz sme da poveže prizemlje jedne sa -1 druge).
     */
    private fun heuristic(node: Node, goal: Node, profile: RoutingProfile): Double {
        if (node.floor == goal.floor) return distanceM(node, goal) / profile.walkingSpeedMps
        if (node.buildingId != goal.buildingId) return 0.0
        val perFloor = minOf(profile.stairsUpSecPerFloor, profile.stairsDownSecPerFloor, profile.liftSecPerFloor)
        return abs(node.floor - goal.floor) * perFloor
    }

    private fun distanceM(a: Node, b: Node): Double = distanceM(position(a), position(b))

    private fun distanceM(a: PointM, b: PointM): Double = hypot(a.x - b.x, a.y - b.y)

    private fun path(cameFrom: Map<String, String>, toId: String): List<Node> =
        generateSequence(toId) { cameFrom[it] }.map(byId::getValue).toList().asReversed()
}
