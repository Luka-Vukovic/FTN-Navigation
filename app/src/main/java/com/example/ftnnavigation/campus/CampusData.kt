package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.Edge
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.FloorScale
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.PlaceholderGraph
import com.example.ftnnavigation.graph.PlanPlacement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** [Node.buildingId] čvorova spoljnog grafa; njihov "plan" je cela mapa kampusa. */
const val CAMPUS_ID = "KAMPUS"

/** Tačka u metrima mape kampusa: [x, y], x ka istoku, y ka jugu, (0, 0) = gornji levi ugao. */
typealias CampusPoint = List<Float>

/**
 * Spoljna mapa kampusa iz assets/campus.json, generisana iz OpenStreetMap-a skriptom
 * tools/kampus/build_campus.py. Promena formata mora da prati tu skriptu.
 */
@Serializable
data class CampusData(
    /** Obavezan natpis izvora podataka (ODbL). */
    val attribution: String,
    val widthM: Float,
    val heightM: Float,
    /** Smeštaj unutrašnjih planova zgrada u mapu kampusa, po id-ju zgrade. */
    val plans: Map<String, PlanJson>,
    /** FTN zgrade i spojni prolazi između njih (prolazi nemaju naziv). */
    val buildings: List<CampusBuilding>,
    /** Obrisi okolnih zgrada, samo za orijentaciju. */
    val context: List<List<CampusPoint>>,
    val streets: List<List<CampusPoint>>,
    val paths: List<List<CampusPoint>>,
    val nodes: List<CampusNode>,
    val edges: List<List<String>>,
) {
    /** Zgrade sa nazivom (bez spojnih prolaza). */
    val namedBuildings: List<CampusBuilding> get() = buildings.filter { it.name != null }

    val placement: PlanPlacement get() = PlanPlacement(FloorScale(widthM, heightM))

    fun building(id: String): CampusBuilding? = buildings.find { it.id == id }

    fun buildingByName(name: String): CampusBuilding? = buildings.find { it.name == name }

    /** Smeštaj svih planova: kampus i zgrade sa unutrašnjim grafom. */
    fun placements(): Map<String, PlanPlacement> =
        plans.mapValues { it.value.toPlacement() } + (CAMPUS_ID to placement)

    fun graphNodes(): List<Node> = nodes.map {
        Node(it.id, CAMPUS_ID, floor = 0, x = it.x / widthM, y = it.y / heightM, type = it.type)
    }

    fun graphEdges(): List<Edge> = edges.map { (a, b) -> Edge(a, b, EdgeType.HOD) }

    companion object {
        const val ASSET = "campus.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CampusData = json.decodeFromString(text)
    }
}

@Serializable
data class PlanJson(
    val originX: Double,
    val originY: Double,
    val rotationDeg: Double,
    val widthM: Float,
    val heightM: Float,
) {
    fun toPlacement() = PlanPlacement(FloorScale(widthM, heightM), originX, originY, rotationDeg)
}

@Serializable
data class CampusBuilding(
    val id: String,
    val name: String? = null,
    /** Kratak natpis na mapi i tačka unutar zgrade gde stoji. */
    val label: String? = null,
    val labelAt: CampusPoint? = null,
    val outline: List<CampusPoint>,
) {
    /** Čvor grafa koji predstavlja zgradu: ulaz ako ima unutrašnji graf, inače čvor ZGRADA. */
    val nodeId: String
        get() = if (id == PlaceholderGraph.BUILDING_ID) PlaceholderGraph.ENTRANCE_ID else "K-Z-$id"
}

@Serializable
data class CampusNode(val id: String, val x: Float, val y: Float, val type: NodeType)

/** Čvorovi i ivice za punjenje baze: unutrašnji graf Nastavnog bloka + kampus + veze. */
fun seedGraph(campus: CampusData): Pair<List<Node>, List<Edge>> = Pair(
    PlaceholderGraph.nodes + campus.graphNodes(),
    PlaceholderGraph.edges + campus.graphEdges() + PlaceholderGraph.campusLinks,
)
