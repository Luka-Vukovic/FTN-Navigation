package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.PointM
import com.example.ftnnavigation.graph.Route
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Vrsta koraka uputstva ([routeSteps]). */
enum class StepKind {
    /** Početak hoda (od pozicije, glavnog ulaza, lokacije). Smer se ne zna - pokazuje ga linija na mapi. */
    KRENI,

    /** Ruta kreće iz sale [RouteStep.name]. */
    IZADJI_IZ_SALE,
    LEVO,
    DESNO,

    /** Okret za ~180°. */
    NAZAD,

    /** Stepenicama sa [RouteStep.fromFloor] na [RouteStep.floor] (jedan ili više spratova). */
    STEPENICE,

    /** Liftom sa [RouteStep.fromFloor] na [RouteStep.floor]. */
    LIFT,

    /** Krak stepenica bez promene sprata - do podesta (NB prolaz ka Amfiteatrima, trem Kule u AMF-u). */
    KRAK,

    /** Spolja u zgradu [RouteStep.buildingId]. */
    ULAZ,

    /** Iz zgrade [RouteStep.buildingId] napolje. */
    IZLAZ,

    /** Spojnim prolazom u zgradu [RouteStep.buildingId]. */
    PROLAZ,

    /** Kraj rute: sala [RouteStep.name] (vrata sa strane [RouteStep.side]) ili zgrada [RouteStep.buildingId]. */
    CILJ,
}

/** Strana vrata cilja u pravcu hoda. */
enum class Side { LEVO, DESNO, PRAVO }

/**
 * Korak uputstva: manevar, pa hod od [walkM] metara do sledećeg koraka. [node] je mesto manevra (posle promene sprata
 * ili zgrade - već na novom mestu), da se na dodir prikaže njegov plan. [buildingId]: zgrada u koju se ulazi, iz koje
 * se izlazi, ili zgrada cilja; [floor]: sprat posle manevra (null - zgrada bez plana). [steps]: prolaz ide i preko
 * stepenika (pasarela F-bloka na podest).
 */
data class RouteStep(
    val kind: StepKind,
    val node: Node,
    val walkM: Double = 0.0,
    val buildingId: String? = null,
    val floor: Int? = null,
    val fromFloor: Int? = null,
    val name: String? = null,
    val side: Side? = null,
    val steps: Boolean = false,
)

/** Gde je čvor: u zgradi (sa planom ili čvor ZGRADA bez plana), napolju ili u spojnom prolazu kampusa (K-P). */
private sealed interface Place {
    data class Inside(val buildingId: String, val hasPlan: Boolean) : Place
    data object Outside : Place
    data object Passage : Place
}

private fun placeOf(node: Node, campus: CampusData): Place = when {
    node.buildingId != CAMPUS_ID -> Place.Inside(node.buildingId, hasPlan = true)
    node.type == NodeType.PROLAZ -> Place.Passage
    node.type == NodeType.ZGRADA -> campus.buildings.find { it.nodeId == node.id }?.let { Place.Inside(it.id, hasPlan = false) }
        ?: Place.Outside
    else -> Place.Outside
}

/** Hod: tačke bliže od ovoga pravoj liniji se ne računaju kao skretanje (vrata naspram čvora hodnika, stubovi). */
private const val INDOOR_TOLERANCE_M = 1.0
private const val OUTDOOR_TOLERANCE_M = 3.0

/** Manji ugao je "pravo". Napolju su staze iz OSM-a krivudave. */
private const val INDOOR_TURN_DEG = 35.0
private const val OUTDOOR_TURN_DEG = 45.0

/** Dva skretanja bliža od ovoga su jedno (zaobilaženje, kratka niša) - ili nijedno, ako se ponište. */
private const val INDOOR_MERGE_M = 4.0
private const val OUTDOOR_MERGE_M = 12.0

/** Skretanja ovoliko pred stepeništem / liftom se ne javljaju - hol pred stepeništem, stepenište se vidi. */
private const val APPROACH_M = 4.0

/**
 * Pravac kojim se ulazi u prolaz / izlazi iz zgrade: iz hoda pre toga, sa ovoliko metara unazad (kraj hodnika jedne
 * zgrade i početak hodnika druge su ponekad na 1-2 m, pod uglom).
 */
private const val LEAD_IN_M = 3.0

/** Stepenice - kratak hod po podestu sprata - stepenice u istom smeru: jedan korak (krak naniže i krak naviše). */
private const val LANDING_M = 15.0

/** Veći ugao je okret nazad. */
private const val TURN_AROUND_DEG = 150.0

/** Vrata pod manjim uglom od pravca hoda su "pravo", ne levo/desno. */
private const val AHEAD_DEG = 30.0

/**
 * Uputstvo korak po korak za [route]: izlazak iz sale, skretanja (levo/desno iz geometrije ivica, posle
 * pojednostavljenja), stepenice i lift sa spratom, ulaz, izlaz i spojni prolazi između zgrada, i cilj sa stranom
 * vrata sale. Udaljenosti su do sledećeg koraka. Smer na početku se ne zna (pozicija nema smer), pa prvi korak
 * nema levo/desno; isto posle stepenica i lifta.
 */
fun routeSteps(route: Route, graph: BuildingGraph, campus: CampusData): List<RouteStep> {
    val nodes = route.nodes
    if (nodes.isEmpty()) return emptyList()
    val places = nodes.map { placeOf(it, campus) }.toMutableList()
    val steps = mutableListOf<RouteStep>()

    val first = nodes.first()
    var start = if (first.type == NodeType.PROSTORIJA) {
        RouteStep(StepKind.IZADJI_IZ_SALE, first, buildingId = first.buildingId, floor = first.floor, name = first.name)
    } else {
        RouteStep(StepKind.KRENI, first)
    }
    var run = mutableListOf(first)
    var outdoor = places.first() == Place.Outside
    // Tačka pre početka hoda (iz prethodnog hoda) - pravac za prvo skretanje; null = pravac se ne zna.
    var leadIn: PointM? = null

    /** Kraj dosadašnjeg hoda i početak novog manevrom [step]; [keepDirection]: hod se nastavlja istim pravcem. */
    fun begin(step: RouteStep, runNodes: List<Node>, isOutdoor: Boolean = false, keepDirection: Boolean = true) {
        val approach = step.kind in setOf(StepKind.STEPENICE, StepKind.LIFT, StepKind.KRAK)
        steps += walk(start, run, leadIn, graph, outdoor, approach)
        leadIn = if (keepDirection) pointBack(run.map(graph::position), leadIn) else null
        start = step
        run = runNodes.toMutableList()
        outdoor = isOutdoor
    }

    /** Kraj stepenica od [from]: zaredom, i preko kratkog hoda po podestu sprata (od kraka naniže do kraka naviše). */
    fun stairsEnd(from: Int): Int {
        val up = nodes[from + 1].floor > nodes[from].floor
        var j = from + 1
        while (j < nodes.size - 1) {
            val next = graph.edge(nodes[j].id, nodes[j + 1].id) ?: break
            if (next.type == EdgeType.STEPENICE) {
                if ((nodes[j + 1].floor > nodes[j].floor) != up) break
                j++
                continue
            }
            // Podest: hod istim spratom iste zgrade, pa opet stepenice istim smerom.
            var k = j
            var walkedM = 0.0
            while (k < nodes.size - 1 && graph.edge(nodes[k].id, nodes[k + 1].id)?.let { it.type == EdgeType.HOD && !it.steps } == true &&
                nodes[k + 1].buildingId == nodes[j].buildingId && nodes[k + 1].floor == nodes[j].floor && walkedM <= LANDING_M
            ) {
                walkedM += distance(graph.position(nodes[k]), graph.position(nodes[k + 1]))
                k++
            }
            val after = if (k < nodes.size - 1) graph.edge(nodes[k].id, nodes[k + 1].id) else null
            if (k == j || walkedM > LANDING_M || after?.type != EdgeType.STEPENICE || (nodes[k + 1].floor > nodes[k].floor) != up) break
            j = k
        }
        return j
    }

    var i = 0
    while (i < nodes.size - 1) {
        val a = nodes[i]
        val b = nodes[i + 1]
        val edge = graph.edge(a.id, b.id)
        val from = places[i]
        val to = places[i + 1]
        when {
            edge?.type == EdgeType.STEPENICE || edge?.type == EdgeType.LIFT -> {
                // Više spratova stepenicama zaredom je jedan korak.
                val j = if (edge.type == EdgeType.STEPENICE) stairsEnd(i) else i + 1
                val end = nodes[j]
                val kind = if (edge.type == EdgeType.LIFT) StepKind.LIFT else StepKind.STEPENICE
                // Posle stepenica i lifta se pravac ne zna (krakovi, vrata lifta).
                begin(
                    RouteStep(kind, end, buildingId = end.buildingId, floor = end.floor, fromFloor = a.floor),
                    listOf(end),
                    keepDirection = false,
                )
                i = j
                continue
            }
            to == Place.Passage && from != Place.Passage -> {
                var k = i + 1
                while (k < nodes.size && places[k] == Place.Passage) k++
                val after = places.getOrNull(k)
                if (after is Place.Inside && after != from) {
                    // Čvor prolaza kampusa (težište OSM spojnog dela) ume da bude iza kraja hodnika - preskače se.
                    val end = nodes[k]
                    val viaSteps = (i until k).any { graph.edge(nodes[it].id, nodes[it + 1].id)?.steps == true }
                    begin(
                        RouteStep(
                            StepKind.PROLAZ, end, buildingId = after.buildingId,
                            floor = end.floor.takeIf { after.hasPlan }, steps = viaSteps,
                        ),
                        listOf(a, end),
                    )
                    i = k
                    continue
                }
                // Prolaz koji ne vodi u drugu zgradu se hoda kao ono što je posle njega.
                for (m in i + 1 until k) places[m] = after ?: Place.Outside
                continue
            }
            from != to -> when {
                to == Place.Outside -> begin(
                    RouteStep(StepKind.IZLAZ, b, buildingId = (from as? Place.Inside)?.buildingId),
                    listOf(a, b),
                    isOutdoor = true,
                )
                to is Place.Inside && from == Place.Outside -> begin(
                    RouteStep(StepKind.ULAZ, b, buildingId = to.buildingId, floor = b.floor.takeIf { to.hasPlan }),
                    listOf(a, b),
                )
                to is Place.Inside -> begin(
                    RouteStep(
                        StepKind.PROLAZ, b, buildingId = to.buildingId, floor = b.floor.takeIf { to.hasPlan },
                        steps = edge?.steps == true,
                    ),
                    listOf(a, b),
                )
                else -> run += b
            }
            edge?.steps == true -> begin(RouteStep(StepKind.KRAK, b), listOf(a, b))
            else -> run += b
        }
        i++
    }

    val last = nodes.last()
    val lastPlace = places.last() as? Place.Inside
    var side: Side? = null
    // Sala: hod do čvora hodnika naspram vrata, pa strana vrata u odnosu na pravac hoda.
    if (last.type == NodeType.PROSTORIJA && run.size >= 3 && run[run.size - 2].type == NodeType.VRATA) {
        val door = run[run.size - 2]
        val corridor = run[run.size - 3]
        run = run.subList(0, run.size - 2).toMutableList()
        val back = pointBack(listOfNotNull(leadIn) + run.map(graph::position), null)
        if (back != null) {
            val turn = turnDeg(back, graph.position(corridor), graph.position(door))
            side = when {
                abs(turn) < AHEAD_DEG -> Side.PRAVO
                turn > 0 -> Side.DESNO
                else -> Side.LEVO
            }
        }
    }
    val goal = RouteStep(
        StepKind.CILJ, last,
        buildingId = lastPlace?.buildingId,
        floor = last.floor.takeIf { lastPlace?.hasPlan == true },
        name = last.name.takeIf { last.type == NodeType.PROSTORIJA },
        side = side,
    )
    if (nodes.size == 1) return listOf(goal)
    val walked = walk(start, run, leadIn, graph, outdoor, approach = false)
    // Ulaz u zgradu koja je sama cilj (ruta do glavnog ulaza, ili čvor zgrade bez plana) - samo cilj.
    val arrivedAtEntrance = last.type != NodeType.PROSTORIJA && walked.size == 1 &&
        (walked[0].walkM < 1.0 || lastPlace?.hasPlan == false) &&
        walked[0].kind in setOf(StepKind.ULAZ, StepKind.PROLAZ) && walked[0].buildingId == lastPlace?.buildingId
    if (!arrivedAtEntrance) steps += walked
    steps += goal
    // Početak bez hoda (ruta kreće stepenicama, ulazom...) nije korak.
    if (steps.size > 1 && steps[0].kind == StepKind.KRENI && steps[0].walkM < 1.0) steps.removeAt(0)
    return steps
}

/**
 * Tačke za pravac: sa [leadIn] (pravac pre hoda) umesto prve tačke - prva tačka je kraj prethodnog hoda, a prvi deo
 * (prolaz između dve zgrade, vrata) je ponekad kratak i pod uglom. Indeksi ostaju isti kao u [points].
 */
private fun directionPoints(points: List<PointM>, leadIn: PointM?): List<PointM> =
    if (leadIn == null || points.isEmpty()) points else listOf(leadIn) + points.drop(1)

/** Tačka bar [LEAD_IN_M] unazad od kraja [points]; ako je put kraći - [fallback] (ili prva tačka), null ako hoda nema. */
private fun pointBack(points: List<PointM>, fallback: PointM?): PointM? {
    var walkedM = 0.0
    for (k in points.size - 2 downTo 0) {
        walkedM += distance(points[k], points[k + 1])
        if (walkedM >= LEAD_IN_M) return points[k]
    }
    return fallback ?: points.firstOrNull()?.takeIf { walkedM > 0.5 }
}

/**
 * Hod kroz [run] posle manevra [start]: [start] sa udaljenošću do prvog skretanja, pa skretanja. Skretanje je teme
 * pojednostavljene linije sa uglom >= praga; bliska skretanja se spajaju. [leadIn]: tačka pre hoda (pravac za
 * skretanje odmah posle manevra). [approach]: hod vodi do stepeništa / lifta - skretanja tik pred njim se ne javljaju.
 */
private fun walk(
    start: RouteStep,
    run: List<Node>,
    leadIn: PointM?,
    graph: BuildingGraph,
    outdoor: Boolean,
    approach: Boolean,
): List<RouteStep> {
    val points = run.map(graph::position)
    val along = DoubleArray(points.size)
    for (k in 1 until points.size) along[k] = along[k - 1] + distance(points[k - 1], points[k])
    val total = along.lastOrNull() ?: 0.0
    val shape = directionPoints(points, leadIn)
    val kept = simplify(shape, if (outdoor) OUTDOOR_TOLERANCE_M else INDOOR_TOLERANCE_M)
    val threshold = if (outdoor) OUTDOOR_TURN_DEG else INDOOR_TURN_DEG
    val mergeM = if (outdoor) OUTDOOR_MERGE_M else INDOOR_MERGE_M

    // (indeks tačke, ugao: + desno)
    val turns = mutableListOf<Pair<Int, Double>>()
    for (k in 1 until kept.size - 1) {
        if (approach && total - along[kept[k]] < APPROACH_M) continue
        // Bez pravca pre hoda (posle lifta, stepenica, na početku) prvi deo ne govori kuda je korisnik okrenut. Iz sale
        // se izlazi kroz vrata - taj pravac je poznat.
        if (leadIn == null && start.kind != StepKind.IZADJI_IZ_SALE && along[kept[k]] < APPROACH_M) continue
        val angle = turnDeg(shape[kept[k - 1]], shape[kept[k]], shape[kept[k + 1]])
        if (abs(angle) >= threshold) turns += kept[k] to angle
    }
    val merged = mutableListOf<Pair<Int, Double>>()
    for (turn in turns) {
        val previous = merged.lastOrNull()
        if (previous != null && along[turn.first] - along[previous.first] < mergeM) {
            merged.removeAt(merged.size - 1)
            val sum = previous.second + turn.second
            if (abs(sum) >= threshold) merged += turn.first to sum
        } else {
            merged += turn
        }
    }

    val result = mutableListOf(start.copy(walkM = (merged.firstOrNull()?.let { along[it.first] } ?: total)))
    for ((n, turn) in merged.withIndex()) {
        val (index, angle) = turn
        val kind = when {
            abs(angle) >= TURN_AROUND_DEG -> StepKind.NAZAD
            angle > 0 -> StepKind.DESNO
            else -> StepKind.LEVO
        }
        val next = merged.getOrNull(n + 1)?.let { along[it.first] } ?: total
        result += RouteStep(kind, run[index], walkM = next - along[index])
    }
    return result
}

/** Ugao skretanja u [b] (od pravca a -> b na pravac b -> c), u stepenima: + = desno (y raste naniže, kao na ekranu). */
private fun turnDeg(a: PointM, b: PointM, c: PointM): Double {
    val ux = b.x - a.x
    val uy = b.y - a.y
    val vx = c.x - b.x
    val vy = c.y - b.y
    return Math.toDegrees(atan2(ux * vy - uy * vx, ux * vx + uy * vy))
}

private fun distance(a: PointM, b: PointM): Double = hypot(a.x - b.x, a.y - b.y)

/** Ramer-Douglas-Peucker: indeksi tačaka koje ostaju (prva i poslednja uvek); ponovljene tačke se izbacuju. */
private fun simplify(points: List<PointM>, toleranceM: Double): List<Int> {
    if (points.size <= 2) return points.indices.toList()
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.size - 1] = true
    fun split(from: Int, to: Int) {
        if (to - from < 2) return
        var worst = -1
        var worstD = toleranceM
        for (k in from + 1 until to) {
            val d = distanceToSegment(points[k], points[from], points[to])
            if (d > worstD) {
                worst = k
                worstD = d
            }
        }
        if (worst < 0) return
        keep[worst] = true
        split(from, worst)
        split(worst, to)
    }
    split(0, points.size - 1)
    val result = mutableListOf<Int>()
    for (index in points.indices) {
        if (keep[index] && (result.isEmpty() || distance(points[index], points[result.last()]) > 1e-6)) result += index
    }
    return result
}

private fun distanceToSegment(p: PointM, a: PointM, b: PointM): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 == 0.0) return distance(p, a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
    return distance(p, PointM(a.x + t * dx, a.y + t * dy))
}
