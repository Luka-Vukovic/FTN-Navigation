package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.Edge
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.FloorScale
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.indoorBuilding
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
    /**
     * FTN zgrade, studentske službe i spojni prolazi. Bez naziva su prolazi i zgrade u kojima
     * je samo deo neka služba (ta služba nema svoj obris).
     */
    val buildings: List<CampusBuilding>,
    /** Okolne zgrade, samo za orijentaciju: svaka je lista prstenova [spoljni, dvorišta...]. */
    val context: List<List<List<CampusPoint>>>,
    val streets: List<List<CampusPoint>>,
    val paths: List<List<CampusPoint>>,
    val nodes: List<CampusNode>,
    val edges: List<List<String>>,
) {
    /** Zgrade sa nazivom (bez spojnih prolaza), FTN i službe. */
    val namedBuildings: List<CampusBuilding> get() = buildings.filter { it.name != null }

    fun named(category: BuildingCategory): List<CampusBuilding> = namedBuildings.filter { it.category == category }

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

/** FTN zgrada ili studentska služba van FTN-a (menza, zdravstvena zaštita...); na mapi se razlikuju. */
enum class BuildingCategory { FTN, SLUZBA }

/**
 * Gde je natpis u odnosu na [CampusBuilding.labelAt]: centriran, ili počinje od tačke ka istoku
 * / završava se na njoj (kad bi se centriran preklapao sa susednim).
 */
enum class LabelSide { CENTER, EAST, WEST }

@Serializable
data class CampusBuilding(
    val id: String,
    val name: String? = null,
    /** Kratak natpis na mapi i tačka unutar zgrade gde stoji. */
    val label: String? = null,
    val labelAt: CampusPoint? = null,
    /** Prazan kad je služba samo deo zgrade - obris te zgrade je poseban unos bez naziva. */
    val outline: List<CampusPoint> = emptyList(),
    /** Unutrašnja dvorišta. */
    val holes: List<List<CampusPoint>> = emptyList(),
    val category: BuildingCategory = BuildingCategory.FTN,
    val labelSide: LabelSide = LabelSide.CENTER,
) {
    /** Čvor grafa koji predstavlja zgradu: glavni ulaz ako ima unutrašnji graf, inače čvor ZGRADA. */
    val nodeId: String
        get() = indoorBuilding(id)?.entranceId ?: "K-Z-$id"
}

@Serializable
data class CampusNode(val id: String, val x: Float, val y: Float, val type: NodeType)

/** Čvorovi i ivice za punjenje baze: unutrašnji grafovi zgrada ([plans]) + kampus + veze. */
fun seedGraph(campus: CampusData, plans: List<IndoorPlan>): Pair<List<Node>, List<Edge>> = Pair(
    plans.flatMap { it.graphNodes() } + campus.graphNodes(),
    plans.flatMap { it.graphEdges() } + campus.graphEdges(),
)
