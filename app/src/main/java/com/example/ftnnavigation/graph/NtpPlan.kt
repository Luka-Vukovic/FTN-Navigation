package com.example.ftnnavigation.graph

import androidx.annotation.DrawableRes
import com.example.ftnnavigation.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Unutrašnji graf FTN dela Naučno-tehnološkog parka iz assets/ntp.json, generisan skriptom
 * tools/ntp/build_ntp.py (sa fotografija evakuacionih planova svih 6 nivoa). Promena formata mora da
 * prati tu skriptu; posle promene ntp.json povećati verziju [GraphDatabase].
 *
 * Za razliku od [PlaceholderGraph] ima spratove: tri stepeništa (S1-S3, ivice između susednih
 * spratova) i tri lifta (L1-L3, ivice između svaka dva sprata) na istom mestu na svakom spratu.
 * Sale iz rasporeda su na pravom spratu, ali na izmišljenim mestima (planovi nemaju brojeve).
 */
@Serializable
data class IndoorPlan(
    val buildingId: String,
    val floors: List<Int>,
    /** Glavni ulaz (u prizemlju) - čvor koji predstavlja zgradu u ruti "do zgrade". */
    val entranceId: String,
    val nodes: List<IndoorNode>,
    /** [od, do, EdgeType]. */
    val edges: List<List<String>>,
    /** [čvor kampusa (ulaz K-U-...), ulaz zgrade]. */
    val campusLinks: List<List<String>>,
) {
    fun graphNodes(): List<Node> = nodes.map { Node(it.id, buildingId, it.floor, it.x, it.y, it.type, it.name) }

    fun graphEdges(): List<Edge> =
        edges.map { (a, b, type) -> Edge(a, b, EdgeType.valueOf(type)) } +
            campusLinks.map { (campus, entrance) -> Edge(campus, entrance, EdgeType.HOD) }
}

@Serializable
data class IndoorNode(
    val id: String,
    val floor: Int,
    val x: Float,
    val y: Float,
    val type: NodeType,
    val name: String? = null,
)

object NtpPlan {
    const val BUILDING_ID = "NTP"

    /** "GLAVNI ULAZ - FTN" (isti id kao `entranceId` u ntp.json - proverava test). */
    const val ENTRANCE_ID = "NTP-0-ULAZ"
    const val ASSET = "ntp.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): IndoorPlan = json.decodeFromString(text)

    /** Crtež sprata; spratovi I-IV su isti plan. */
    @DrawableRes
    fun floorDrawable(floor: Int): Int = when (floor) {
        0 -> R.drawable.floor_plan_ntp_0
        5 -> R.drawable.floor_plan_ntp_5
        else -> R.drawable.floor_plan_ntp_typical
    }
}
