package com.example.ftnnavigation.graph

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
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
 * korak poprečno na hodnik se tako ne gubi (skretanje u ogranak hodnika), a drift kompasa se ne
 * nagomilava. [stuckXM]/[stuckYM]: zbir koraka (m) od kad se tačka na grafu ne pomera.
 */
data class MatchedPosition(
    val point: EdgePoint,
    val x: Float,
    val y: Float,
    val stuckXM: Double = 0.0,
    val stuckYM: Double = 0.0,
)

/** Koliko slobodna pozicija sme da odstupi od ivice (m). TODO: proveriti na terenu (širina hodnika). */
const val MATCH_TOLERANCE_M = 2.0

/** Korak u kome se tačka na grafu pomeri manje od ovog dela koraka - tačka ne napreduje. */
private const val STUCK_PROGRESS = 0.3

/** Posle ovoliko metara hoda bez napretka na kraju grane sale tačka se vraća u hodnik. */
const val ROOM_ESCAPE_M = 2.5

/** ... ako je taj hod bar ovoliko popreko na granu (inače korisnik ide dublje u salu). */
private const val ROOM_ESCAPE_ANGLE_DEG = 45.0

/** Posle ovoliko metara hoda bez napretka (bilo gde) traži se ivica koja bolje objašnjava taj hod ... */
const val STUCK_ESCAPE_M = 3.0

/** ... i prelazi se na nju ako je bar ovoliko bliža od trenutne. */
private const val STUCK_ESCAPE_MARGIN_M = 1.0

/** Tačka na ivici bliže čvoru od ovoga (m) je na čvoru. */
private const val AT_NODE_M = 0.05

/** Ivice čiji se pravci razlikuju za manje od ovoga su isti hodnik (bez skretanja). */
private const val SAME_DIRECTION_DEG = 30.0

private val ROOM_TYPES = setOf(NodeType.PROSTORIJA, NodeType.VRATA)

/**
 * Map-matching PDR pozicije na ivice hoda ([EdgeType.HOD]) jednog sprata zgrade. Posle
 * svakog koraka pozicija se projektuje na najbližu ivicu, ali biraju se samo ivice do kojih se
 * od prethodne pozicije stiže po grafu u dometu koraka + tolerancije - pozicija ne može da
 * "preskoči" zid u susedni hodnik, čak i kad je on vazdušno bliži.
 *
 * Grane sala (sala - vrata - hodnik) se iz hodnika ne biraju: uz grešku smera slobodna pozicija
 * stalno stoji na toleranciji pored hodnika, pa je kod svakih vrata grana bliža od hodnika i tačka
 * je skretala u sale (teren 03.10.2026: NB sala 021, pa 018, AMF B001; kompas ~+22°). Grana je
 * slepa - koraci popreko na nju tačku ne pomeraju, a domet koraka ne stiže nazad do hodnika, pa je
 * tačka ostajala u sali. U salu se zato dolazi samo označavanjem ("Ovde sam"); kad korisnik ulazi u
 * salu, tačka ostaje u hodniku naspram vrata. Iz sale (start u njoj) se izlazi granom, a ako tačka
 * stoji na kraju grane dok korisnik hoda [ROOM_ESCAPE_M] popreko na nju, prelazi na raskrsnicu
 * grane sa hodnikom, pomerenu za taj hod.
 *
 * Isto i van sala: ako tačka ne napreduje dok korisnik hoda [STUCK_ESCAPE_M] (na raskrsnici je
 * izabran pogrešan krak - NTP prizemlje posle ulaza, 03.10.2026), prelazi na dostižnu ivicu koja
 * bolje objašnjava taj hod.
 *
 * Skretanje na raskrsnici: na ivicu drugog pravca (> [SAME_DIRECTION_DEG]) tačka prelazi samo ako je
 * korak bliži pravcu te ivice nego pravcu trenutne. Inače bi o skretanju odlučivala samo slobodna
 * pozicija, a ona uz grešku smera stoji na toleranciji pored hodnika - kod svakog kraka na toj strani
 * je krak bliži (teren 04.10.2026: NB prizemlje, kompas ~+35°, korisnik ide hodnikom ka holu, tačka
 * skrenula u krak ka prolazu u Amfiteatre - korak 56° od kraka, 34° od hodnika - prešla ga celog i
 * okinula prolaz).
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

        /** Deo grane sale (vrata su uvek samo između sale i hodnika). */
        val isRoom = a.type in ROOM_TYPES || b.type in ROOM_TYPES

        fun other(node: Node) = if (node.id == a.id) b else a

        /** |cos| ugla između ivice i vektora (bez smera ivice): 1 = duž ivice, 0 = popreko. */
        fun alignment(dx: Double, dy: Double): Double {
            val norm = length * hypot(dx, dy)
            return if (norm == 0.0) 1.0 else abs((dx * (bx - ax) + dy * (by - ay)) / norm)
        }

        fun sameDirection(other: Segment) = alignment(other.bx - other.ax, other.by - other.ay) >= cos(Math.toRadians(SAME_DIRECTION_DEG))

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

    /** Slepa grana sale: od sale ([room], list grafa) preko vrata do prvog čvora sa više od dva suseda ([junction]). */
    private inner class RoomBranch(val room: Node, val junction: Node, val segments: Set<Segment>)

    private val roomBranches: Map<String, RoomBranch> = incident.values.flatten().toSet()
        .flatMap { listOf(it.a, it.b) }
        .filter { it.type == NodeType.PROSTORIJA && incident[it.id]?.size == 1 }
        .distinctBy { it.id }
        .mapNotNull { room ->
            val chain = linkedSetOf<Segment>()
            var node = room
            while (true) {
                val next = incident.getValue(node.id).singleOrNull { it !in chain } ?: return@mapNotNull null
                chain += next
                node = next.other(node)
                if (incident.getValue(node.id).size != 2) break
            }
            if (incident.getValue(node.id).size < 3) null else room.id to RoomBranch(room, node, chain)
        }.toMap()

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
        val stepM = hypot(dxM, dyM)
        val current = segmentOf(from.point)
        val inRoom = current.isRoom
        val along = current.alignment(dxM, dyM)
        val nearest = segmentsNear(from.point, stepM + toleranceM)
            .filter { inRoom || !it.isRoom }
            .filter { it == current || current.sameDirection(it) || it.alignment(dxM, dyM) > along }
            .minBy { it.distance(px, py) }
        val next = fix(nearest, px, py)
        if (stepM == 0.0 || distanceM(from.point, next.point) >= STUCK_PROGRESS * stepM) return next
        val stuckX = from.stuckXM + dxM
        val stuckY = from.stuckYM + dyM
        return escapeRoom(next.point, stuckX, stuckY)
            ?: escapeStuck(next.point, stuckX, stuckY, inRoom)
            ?: next.copy(stuckXM = stuckX, stuckYM = stuckY)
    }

    /**
     * Tačka ne napreduje bar [STUCK_ESCAPE_M] (pogrešan krak na raskrsnici, korisnik ide popreko na ivicu) ->
     * ivica dostižna po grafu u dometu tog hoda koja je bar [STUCK_ESCAPE_MARGIN_M] bliža mestu gde bi korisnik
     * bio (tačka + hod bez napretka) od trenutne ivice. Inače null.
     */
    private fun escapeStuck(point: EdgePoint, stuckX: Double, stuckY: Double, inRoom: Boolean): MatchedPosition? {
        val stuckM = hypot(stuckX, stuckY)
        if (stuckM < STUCK_ESCAPE_M) return null
        val qx = point.x * scale.widthM + stuckX
        val qy = point.y * scale.heightM + stuckY
        val current = segmentOf(point)
        val best = segmentsNear(point, stuckM + toleranceM)
            .filter { it != current && (inRoom || !it.isRoom) }
            .minByOrNull { it.distance(qx, qy) } ?: return null
        if (best.distance(qx, qy) > current.distance(qx, qy) - STUCK_ESCAPE_MARGIN_M) return null
        return fix(best, qx, qy)
    }

    /**
     * Tačka na kraju grane sale, a hod bez napretka ([stuckX]/[stuckY]) je dovoljno dug i popreko na granu ->
     * raskrsnica grane sa hodnikom pomerena za taj hod, zalepljena za najbližu ivicu van grane. Inače null.
     */
    private fun escapeRoom(point: EdgePoint, stuckX: Double, stuckY: Double): MatchedPosition? {
        val stuckM = hypot(stuckX, stuckY)
        if (stuckM < ROOM_ESCAPE_M) return null
        val current = segmentOf(point)
        val node = when {
            point.t * current.length < AT_NODE_M -> point.from
            (1 - point.t) * current.length < AT_NODE_M -> point.to
            else -> return null
        }
        val branch = roomBranches[node.id] ?: return null
        val jx = branch.junction.x * scale.widthM.toDouble()
        val jy = branch.junction.y * scale.heightM.toDouble()
        val bx = branch.room.x * scale.widthM - jx
        val by = branch.room.y * scale.heightM - jy
        val cos = (stuckX * bx + stuckY * by) / (stuckM * hypot(bx, by))
        if (cos > cos(Math.toRadians(ROOM_ESCAPE_ANGLE_DEG))) return null
        val qx = jx + stuckX
        val qy = jy + stuckY
        val nearest = reachable(listOf(0.0 to branch.junction), stuckM + toleranceM)
            .filter { it !in branch.segments }
            .minByOrNull { it.distance(qx, qy) } ?: return null
        return fix(nearest, qx, qy)
    }

    private fun segmentOf(point: EdgePoint): Segment =
        segments[point.from.id to point.to.id] ?: segments.getValue(point.to.id to point.from.id)

    /** Rastojanje (m) između dve tačke na ivicama. */
    private fun distanceM(a: EdgePoint, b: EdgePoint): Double =
        hypot((a.x - b.x) * scale.widthM.toDouble(), (a.y - b.y) * scale.heightM.toDouble())

    /** Ivice do kojih se od [point] stiže po grafu u najviše [reachM] metara (i ivica same tačke). */
    private fun segmentsNear(point: EdgePoint, reachM: Double): Set<Segment> {
        val current = segmentOf(point)
        return linkedSetOf(current) + reachable(listOf(point.t * current.length to point.from, (1 - point.t) * current.length to point.to), reachM)
    }

    /** Ivice incidentne čvorovima do kojih se od [starts] (rastojanje, čvor) stiže po grafu u najviše [reachM] metara. */
    private fun reachable(starts: List<Pair<Double, Node>>, reachM: Double): Set<Segment> {
        val result = linkedSetOf<Segment>()
        val settled = HashSet<String>()
        val queue = PriorityQueue<Pair<Double, Node>>(compareBy { it.first })
        queue += starts
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
