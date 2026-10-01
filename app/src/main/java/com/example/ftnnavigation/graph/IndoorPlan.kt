package com.example.ftnnavigation.graph

import androidx.annotation.DrawableRes
import com.example.ftnnavigation.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Unutrašnji graf zgrade sa spratovima iz assets-a (nb.json, amf.json, kula.json, ntp.json), generisan
 * skriptama tools/zgrade/build_{nb,amf,kula}.py i tools/ntp/build_ntp.py. Promena formata mora da prati te skripte; posle
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
    /** [čvor ove zgrade, čvor druge zgrade sa planom] - prolaz mimo kampusa (NB - Kula na I spratu). */
    val indoorLinks: List<List<String>> = emptyList(),
) {
    fun graphNodes(): List<Node> = nodes.map { Node(it.id, buildingId, it.floor, it.x, it.y, it.type, it.name) }

    fun graphEdges(): List<Edge> =
        edges.map { (a, b, type) -> Edge(a, b, EdgeType.valueOf(type)) } +
            (campusLinks + indoorLinks).map { (a, b) -> Edge(a, b, EdgeType.HOD) }

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
 * Zgrada sa unutrašnjim planom: podaci za punjenje grafa (assets) i za Mapu (crteži spratova).
 * Zgrade bez plana su u grafu jedan čvor K-Z-<id>.
 */
sealed interface IndoorBuilding {
    val buildingId: String
    val asset: String
    val floors: IntRange

    /** Glavni ulaz - čvor koji predstavlja zgradu u ruti "do zgrade" (isti id kao `entranceId` u JSON-u). */
    val entranceId: String

    /** Visina natpisa sale kao deo visine plana (NB i AMF su niski i široki, NTP skoro kvadratan). */
    val labelHeight: Float

    @DrawableRes
    fun floorDrawable(floor: Int): Int

    /** Natpis sale na planu (npr. "Kula 101" -> "101"; na planu se zna koja je zgrada). */
    fun label(name: String): String = name
}

/** Sve zgrade sa planom, redom kao na Mapi. */
val INDOOR_BUILDINGS: List<IndoorBuilding> by lazy { listOf(NbPlan, AmfPlan, KulaPlan, NtpPlan) }

fun indoorBuilding(buildingId: String): IndoorBuilding? = INDOOR_BUILDINGS.find { it.buildingId == buildingId }

/**
 * Nastavni blok: 7 nivoa (-1 ... V sprat), sale na pravim mestima sa snimaka aplikacije FtnGO.
 * Orijentacija plana: desno sever, dole istok (glavni ulaz), levo jug (Kula), gore zapad (Amfiteatri).
 */
object NbPlan : IndoorBuilding {
    const val BUILDING_ID = "NB"
    const val ASSET = "nb.json"
    val FLOORS = -1..5

    /** Glavni ulaz (dole, kod portirnice). */
    const val ENTRANCE_ID = "NB-0-ULAZ"

    /** Spojni prolaz ka Kuli (levo, južni kraj zgrade). */
    const val PASSAGE_ID = "NB-0-PROLAZ"

    /** Prolaz ka Amfiteatrima: od glavnog stepeništa stepenicama naviše (FtnGO). */
    const val AMF_PASSAGE_ID = "NB-0-PROLAZ-AMF"

    override val buildingId get() = BUILDING_ID
    override val asset get() = ASSET
    override val floors get() = FLOORS
    override val entranceId get() = ENTRANCE_ID
    override val labelHeight get() = 0.03f

    @DrawableRes
    override fun floorDrawable(floor: Int): Int = when (floor) {
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
 * Amfiteatri: suteren (-1: glavni ulaz, Skriptarnica, Biblioteka, GRID), prizemlje (AR0-AR6, A0-A4, INT 1)
 * i nivo 1 (samo L1), sa snimaka FtnGO-a i fotografija virtuelne ture FTN-a (tools/zgrade/build_amf.py).
 */
object AmfPlan : IndoorBuilding {
    const val BUILDING_ID = "AMF"
    const val ENTRANCE_ID = "AMF-m1-ULAZ"
    val FLOORS = -1..1

    override val buildingId get() = BUILDING_ID
    override val asset get() = "amf.json"
    override val floors get() = FLOORS
    override val entranceId get() = ENTRANCE_ID
    override val labelHeight get() = 0.03f

    @DrawableRes
    override fun floorDrawable(floor: Int): Int = when {
        floor < 0 -> R.drawable.floor_plan_amf_m1
        floor > 0 -> R.drawable.floor_plan_amf_1
        else -> R.drawable.floor_plan_amf_0
    }
}

/** Kula: prizemlje ... IX sprat, uglavnom kancelarije; sale su "Kula 101" (brojevi se ponavljaju u NB). */
object KulaPlan : IndoorBuilding {
    const val BUILDING_ID = "KULA"
    const val ENTRANCE_ID = "KULA-0-ULAZ"
    const val ROOM_PREFIX = "Kula "
    val FLOORS = 0..9

    override val buildingId get() = BUILDING_ID
    override val asset get() = "kula.json"
    override val floors get() = FLOORS
    override val entranceId get() = ENTRANCE_ID
    override val labelHeight get() = 0.03f

    override fun label(name: String) = name.removePrefix(ROOM_PREFIX)

    @DrawableRes
    override fun floorDrawable(floor: Int): Int = when (floor) {
        1 -> R.drawable.floor_plan_kula_1
        2 -> R.drawable.floor_plan_kula_2
        3 -> R.drawable.floor_plan_kula_3
        4 -> R.drawable.floor_plan_kula_4
        5 -> R.drawable.floor_plan_kula_5
        6 -> R.drawable.floor_plan_kula_6
        7 -> R.drawable.floor_plan_kula_7
        8 -> R.drawable.floor_plan_kula_8
        9 -> R.drawable.floor_plan_kula_9
        else -> R.drawable.floor_plan_kula_0
    }
}

/**
 * FTN deo Naučno-tehnološkog parka (sa fotografija evakuacionih planova svih 6 nivoa). Sale iz rasporeda
 * su na pravom spratu, ali na izmišljenim mestima (planovi nemaju brojeve).
 */
object NtpPlan : IndoorBuilding {
    const val BUILDING_ID = "NTP"
    const val ASSET = "ntp.json"
    val FLOORS = 0..5

    /** "GLAVNI ULAZ - FTN". */
    const val ENTRANCE_ID = "NTP-0-ULAZ"

    override val buildingId get() = BUILDING_ID
    override val asset get() = ASSET
    override val floors get() = FLOORS
    override val entranceId get() = ENTRANCE_ID
    // Plan obuhvata ceo NTP (i poslovni deo), pa je FTN deo manji deo visine nego ranije (0,014 pri visini 1860 px).
    override val labelHeight get() = 0.01f

    /** Crtež sprata; spratovi I-IV su isti plan. */
    @DrawableRes
    override fun floorDrawable(floor: Int): Int = when (floor) {
        0 -> R.drawable.floor_plan_ntp_0
        5 -> R.drawable.floor_plan_ntp_5
        else -> R.drawable.floor_plan_ntp_typical
    }
}
