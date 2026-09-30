package com.example.ftnnavigation.graph

import androidx.annotation.DrawableRes
import com.example.ftnnavigation.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Unutrašnji graf zgrade sa spratovima iz assets-a (nb.json, ntp.json), generisan skriptama
 * tools/nb/build_nb.py i tools/ntp/build_ntp.py. Promena formata mora da prati te skripte; posle
 * promene JSON-a povećati verziju [GraphDatabase].
 *
 * Stepeništa su ivice između susednih spratova, liftovi ivice između svaka dva sprata.
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
    /** [čvor kampusa (ulaz K-U-... ili prolaz K-P-...), čvor zgrade]. */
    val campusLinks: List<List<String>>,
) {
    fun graphNodes(): List<Node> = nodes.map { Node(it.id, buildingId, it.floor, it.x, it.y, it.type, it.name) }

    fun graphEdges(): List<Edge> =
        edges.map { (a, b, type) -> Edge(a, b, EdgeType.valueOf(type)) } +
            campusLinks.map { (campus, entrance) -> Edge(campus, entrance, EdgeType.HOD) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): IndoorPlan = json.decodeFromString(text)
    }
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

/**
 * Nastavni blok: 7 nivoa (-1 ... V sprat), sale na pravim mestima sa snimaka aplikacije FtnGO.
 * Orijentacija plana: desno sever, dole istok (glavni ulaz), levo jug (Kula), gore zapad (Amfiteatri).
 */
object NbPlan {
    const val BUILDING_ID = "NB"
    const val ASSET = "nb.json"
    val FLOORS = -1..5

    /** Glavni ulaz (dole, kod portirnice; isti id kao `entranceId` u nb.json - proverava test). */
    const val ENTRANCE_ID = "NB-0-ULAZ"

    /** Spojni prolaz ka Kuli (levo, južni kraj zgrade). */
    const val PASSAGE_ID = "NB-0-PROLAZ"

    /** Prolaz ka Amfiteatrima: od glavnog stepeništa stepenicama naviše (FtnGO). */
    const val AMF_PASSAGE_ID = "NB-0-PROLAZ-AMF"

    @DrawableRes
    fun floorDrawable(floor: Int): Int = when (floor) {
        -1 -> R.drawable.floor_plan_nb_m1
        1 -> R.drawable.floor_plan_nb_1
        2 -> R.drawable.floor_plan_nb_2
        3 -> R.drawable.floor_plan_nb_3
        4 -> R.drawable.floor_plan_nb_4
        5 -> R.drawable.floor_plan_nb_5
        else -> R.drawable.floor_plan_nb_0
    }
}

/**
 * FTN deo Naučno-tehnološkog parka (sa fotografija evakuacionih planova svih 6 nivoa). Sale iz rasporeda
 * su na pravom spratu, ali na izmišljenim mestima (planovi nemaju brojeve).
 */
object NtpPlan {
    const val BUILDING_ID = "NTP"
    const val ASSET = "ntp.json"
    val FLOORS = 0..5

    /** "GLAVNI ULAZ - FTN" (isti id kao `entranceId` u ntp.json - proverava test). */
    const val ENTRANCE_ID = "NTP-0-ULAZ"

    /** Crtež sprata; spratovi I-IV su isti plan. */
    @DrawableRes
    fun floorDrawable(floor: Int): Int = when (floor) {
        0 -> R.drawable.floor_plan_ntp_0
        5 -> R.drawable.floor_plan_ntp_5
        else -> R.drawable.floor_plan_ntp_typical
    }
}
