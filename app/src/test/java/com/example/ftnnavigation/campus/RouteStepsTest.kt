package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.Edge
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.FloorScale
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Uputstvo korak po korak ([routeSteps]): smer na veštačkom grafu, pa prave rute (svi planovi + kampus). */
class RouteStepsTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private val plans = INDOOR_BUILDINGS.map { IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }

    private val graph = seedGraph(campus, plans).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private fun nodeOf(place: String): String =
        graph.room(place)?.id ?: checkNotNull(campus.buildingByName(place) ?: campus.building(place)) { place }.nodeId

    private fun steps(from: String, to: String): List<RouteStep> {
        val route = checkNotNull(graph.route(from, nodeOf(to))) { "$from -> $to" }
        return routeSteps(route, graph, campus)
    }

    private fun roomId(name: String) = checkNotNull(graph.room(name)) { name }.id

    private fun List<RouteStep>.kinds() = map { it.kind }

    /**
     * Hodnik na istok, skretanje na jug (y raste naniže - kao na ekranu), sala istočno od hodnika: idući na jug je
     * istok levo.
     */
    @Test
    fun leftAndRight_fromGeometry() {
        fun node(id: String, x: Float, y: Float, type: NodeType, name: String? = null) = Node(id, "X", 0, x, y, type, name)
        val nodes = listOf(
            node("A", 0.1f, 0.5f, NodeType.HODNIK),
            node("B", 0.5f, 0.5f, NodeType.HODNIK),
            node("C", 0.5f, 0.8f, NodeType.HODNIK),
            node("D", 0.55f, 0.8f, NodeType.VRATA),
            node("R", 0.6f, 0.8f, NodeType.PROSTORIJA, "101"),
        )
        val edges = listOf("A" to "B", "B" to "C", "C" to "D", "D" to "R").map { (a, b) -> Edge(a, b, EdgeType.HOD) }
        val small = BuildingGraph(nodes, edges, FloorScale(100f, 100f))

        val steps = routeSteps(small.route("A", "R")!!, small, campus)

        assertEquals(listOf(StepKind.KRENI, StepKind.DESNO, StepKind.CILJ), steps.kinds())
        assertEquals(40.0, steps[0].walkM, 0.01)
        assertEquals(30.0, steps[1].walkM, 0.01)
        assertEquals("101", steps[2].name)
        assertEquals(Side.LEVO, steps[2].side)

        // Nazad: iz sale izlazi se na zapad (vrata su zapadno od sale), pa desno - na sever.
        val back = routeSteps(small.route("R", "A")!!, small, campus)
        assertEquals(listOf(StepKind.IZADJI_IZ_SALE, StepKind.DESNO, StepKind.LEVO, StepKind.CILJ), back.kinds())
    }

    /** Krak naniže i krak naviše povezani hodom po podestu sprata su jedan korak "stepenicama", sa oba sprata. */
    @Test
    fun stairsOverSeveralFloors_oneStep() {
        val steps = steps(NbPlan.ENTRANCE_ID, "204")
        val stairs = steps.filter { it.kind == StepKind.STEPENICE }
        assertEquals(1, stairs.size)
        assertEquals(0, stairs[0].fromFloor)
        assertEquals(2, stairs[0].floor)
        val goal = steps.last()
        assertEquals(StepKind.CILJ, goal.kind)
        assertEquals("204", goal.name)
        assertEquals(2, goal.floor)
        assertNotNull(goal.side)
    }

    @Test
    fun fromRoom_startsByLeavingIt() {
        val steps = steps(roomId("204"), "106")
        assertEquals(StepKind.IZADJI_IZ_SALE, steps.first().kind)
        assertEquals("204", steps.first().name)
        assertEquals(listOf(2 to 1), steps.filter { it.kind == StepKind.STEPENICE }.map { it.fromFloor to it.floor })
        assertEquals("106", steps.last().name)
    }

    /** NB -> Kula -> AMF -> F: prolazi po zgradama, pasarela F-bloka je preko stepenika. */
    @Test
    fun passagesBetweenBuildings() {
        val steps = steps(NbPlan.ENTRANCE_ID, "F 315")
        val passages = steps.filter { it.kind == StepKind.PROLAZ }
        assertEquals(listOf("KULA", "AMF", "F"), passages.map { it.buildingId })
        assertEquals(listOf(false, false, true), passages.map { it.steps })
        assertFalse(StepKind.IZLAZ in steps.kinds())
        assertEquals(listOf(1 to 3), steps.filter { it.kind == StepKind.STEPENICE }.map { it.fromFloor to it.floor })
    }

    /** Kratak prolaz NB - Kula (krajevi hodnika na ~1 m, pod uglom) i izlazak iz lifta nisu "okreni se". */
    @Test
    fun noTurnAround_afterPassageOrLift() {
        val steps = steps(NbPlan.ENTRANCE_ID, "Kula 905")
        assertFalse(StepKind.NAZAD in steps.kinds())
        assertEquals(listOf(0 to 9), steps.filter { it.kind == StepKind.LIFT }.map { it.fromFloor to it.floor })
        assertEquals("Kula 905", steps.last().name)
    }

    @Test
    fun outdoorRoute_exitAndEnter() {
        val steps = steps(NbPlan.ENTRANCE_ID, "NTP-307")
        val exit = steps.single { it.kind == StepKind.IZLAZ }
        assertEquals("NB", exit.buildingId)
        val entry = steps.single { it.kind == StepKind.ULAZ }
        assertEquals("NTP", entry.buildingId)
        assertEquals(0, entry.floor)
        assertTrue(steps.indexOf(exit) < steps.indexOf(entry))
        assertEquals(3, steps.last().floor)
    }

    /**
     * Zbir hoda po koracima je dužina rute, bez dela od vrata do sredine sale na cilju (uputstvo vodi do vrata). Bez
     * spojnih prolaza: kroz njih uputstvo meri pravu liniju, a ruta ide preko čvora prolaza kampusa (težište OSM
     * spojnog dela, 4-9 m u stranu) - NB -> A2 je 45 m naspram 56 m.
     */
    @Test
    fun walkedDistance_matchesRoute() {
        for ((from, to) in listOf(NbPlan.ENTRANCE_ID to "NTP-307", roomId("101") to "AH9", roomId("204") to "106")) {
            val route = graph.route(from, nodeOf(to))!!
            val walked = routeSteps(route, graph, campus).sumOf { it.walkM }
            val n = route.nodes.size
            val roomLeg = listOf(n - 3 to n - 2, n - 2 to n - 1).sumOf { (a, b) ->
                val p = graph.position(route.nodes[a])
                val q = graph.position(route.nodes[b])
                kotlin.math.hypot(p.x - q.x, p.y - q.y)
            }
            assertEquals("$to: $walked / ${route.lengthM}", route.lengthM - roomLeg, walked, 0.5)
        }
    }

    /** Zgrada bez plana (Menza) i zgrada kao cilj (glavni ulaz NB-a iznutra): cilj bez suvišnog "uđi". */
    @Test
    fun buildingGoal_noExtraEntryStep() {
        val menza = steps(roomId("204"), "MENZA")
        assertEquals(StepKind.CILJ, menza.last().kind)
        assertEquals("MENZA", menza.last().buildingId)
        assertFalse(StepKind.ULAZ in menza.kinds())

        val nb = steps(roomId("A1"), "Nastavni blok")
        assertEquals("NB", nb.last().buildingId)
        assertEquals(null, nb.last().name)
    }

    /** Na cilju: već na mestu -> samo cilj. */
    @Test
    fun alreadyThere_onlyGoal() {
        val steps = steps(roomId("204"), "204")
        assertEquals(listOf(StepKind.CILJ), steps.kinds())
    }
}
