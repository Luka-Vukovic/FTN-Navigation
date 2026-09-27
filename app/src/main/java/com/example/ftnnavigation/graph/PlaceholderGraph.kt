package com.example.ftnnavigation.graph

/**
 * Privremeni graf zgrade - samo prizemlje, po planu res/drawable/floor_plan_placeholder.xml
 * (precrtan sa evakuacionog plana Nastavnog bloka): hol levo, uži hodnik desno (Svečana sala
 * gore, Studentska služba dole), stepenište sa liftom u sredini gore, glavni ulaz dole kroz
 * predvorje kod portirnice, spojni prolaz ka Kuli levo.
 *
 * Orijentacija (smeštaj u obris zgrade iz OSM-a, tools/kampus): desno na planu je sever,
 * dole istok (glavni ulaz prema Trgu Dositeja Obradovića), levo jug, gore zapad (Amfiteatri).
 *
 * Dok ne dobijemo stvarne lokacije, sve sale su u prizemlju: učionice nose nazive sala
 * Nastavnog bloka iz rasporeda (brojevi, L… (RC)), ali na izmišljenim mestima. Stepenište i
 * lift postoje kao čvorovi, ali bez viših spratova. Promena podataka zahteva povećanje verzije
 * [GraphDatabase] (baza se tada briše i ponovo puni).
 */
object PlaceholderGraph {
    const val BUILDING_ID = "NB" // Nastavni blok
    private const val FLOOR = 0

    // Koordinate ispod su u pikselima fotografije evakuacionog plana, kao i putanje u
    // drawable-u; viewport plana pokriva x 305..1055, y 305..560.
    private const val ORIGIN_X = 305f
    private const val ORIGIN_Y = 305f
    private const val PLAN_W = 750f
    private const val PLAN_H = 255f

    private const val VESTIBULE = "PREDVORJE"

    /**
     * Tačke hodnika sleva nadesno, naspram vrata sa plana: sredina hola (y 414), pa užeg
     * desnog hodnika (y 423).
     */
    private val corridor = listOf(
        435f to 414f, 452f to 414f, 477f to 414f, 497f to 414f, 525f to 414f, 546f to 414f,
        569f to 414f, 580f to 414f, 596f to 414f, 612f to 414f, 660f to 414f, 677f to 414f,
        722f to 414f, 750f to 414f, 770f to 423f, 822f to 423f, 995f to 423f,
    )

    /**
     * Prostorija: naziv, centar sobe, vrata na zidu i ključ čvora hodnika naspram vrata.
     * Ruta ide centar -> vrata -> hodnik, pa ivice ne seku zidove.
     */
    private data class Room(val name: String, val x: Float, val y: Float, val doorX: Float, val doorY: Float, val attachTo: String)

    private fun hall(x: Float) = "H${x.toInt()}"

    private val rooms = listOf(
        // gornji red, levo od stepeništa (prva je mala soba u uglu hola)
        Room("109", 432f, 384f, 450f, 386f, hall(452f)),
        Room("101", 450f, 340f, 477f, 379f, hall(477f)),
        Room("102", 502f, 345f, 497f, 379f, hall(497f)),
        Room("103", 537f, 345f, 546f, 379f, hall(546f)),
        Room("104", 571f, 345f, 580f, 379f, hall(580f)),
        Room("105", 608f, 345f, 612f, 379f, hall(612f)),
        // desno od stepeništa, svečana sala
        Room("107", 710f, 340f, 722f, 370f, hall(722f)),
        Room("Toalet", 745f, 340f, 750f, 370f, hall(750f)),
        Room("Svečana sala", 900f, 355f, 822f, 400f, hall(822f)),
        // donji red; prve dve sobe dele malo predsoblje sa vratima kod x 525
        Room("L2 (RC)", 453f, 485f, 525f, 449f, hall(525f)),
        Room("L4 (RC)", 540f, 485f, 525f, 449f, hall(525f)),
        Room("108", 573f, 478f, 569f, 449f, hall(569f)),
        Room("L6 (RC)", 608f, 478f, 596f, 449f, hall(596f)),
        Room("Portir", 645f, 490f, 662f, 482f, VESTIBULE),
        Room("Studentska služba", 880f, 475f, 765f, 447f, hall(770f)),
    )

    private val graph by lazy { buildGraph() }
    val nodes: List<Node> get() = graph.first
    val edges: List<Edge> get() = graph.second

    /** Glavni ulaz (dole, kod portirnice). */
    const val ENTRANCE_ID = "$BUILDING_ID-$FLOOR-ULAZ"

    /** Spojni prolaz ka Kuli (levo, južni kraj zgrade). */
    const val PASSAGE_ID = "$BUILDING_ID-$FLOOR-PROLAZ"

    /**
     * Prolaz ka Amfiteatrima: zapadni zid kod stepeništa (gde OSM ima spojni deo zgrade).
     * Pretpostavka - evakuacioni plan ga ne prikazuje, možda je samo na spratu.
     */
    const val AMF_PASSAGE_ID = "$BUILDING_ID-$FLOOR-PROLAZ-AMF"

    /**
     * Veze sa spoljnim grafom kampusa (id-jevi čvorova iz assets/campus.json, tools/kampus):
     * glavni ulaz sa OSM ulazom i oba prolaza sa spojnim delovima zgrada.
     */
    val campusLinks = listOf(
        Edge("K-U-NB-1", ENTRANCE_ID, EdgeType.HOD),
        Edge("K-P-NB-KULA", PASSAGE_ID, EdgeType.HOD),
        Edge("K-P-AMF-NB", AMF_PASSAGE_ID, EdgeType.HOD),
    )

    private fun buildGraph(): Pair<List<Node>, List<Edge>> {
        val nodes = mutableListOf<Node>()
        // Skup: vrata koja dele dve sobe (predsoblje) kače se na hodnik samo jednom.
        val edges = linkedSetOf<Edge>()
        fun id(key: String) = "$BUILDING_ID-$FLOOR-$key"
        fun node(id: String, x: Float, y: Float, type: NodeType, name: String? = null) {
            nodes += Node(id, BUILDING_ID, FLOOR, (x - ORIGIN_X) / PLAN_W, (y - ORIGIN_Y) / PLAN_H, type, name)
        }

        corridor.forEach { (x, y) -> node(id(hall(x)), x, y, NodeType.HODNIK) }
        corridor.zipWithNext { a, b -> edges += Edge(id(hall(a.first)), id(hall(b.first)), EdgeType.HOD) }

        for (room in rooms) {
            val door = id("V${room.doorX.toInt()}_${room.doorY.toInt()}")
            if (nodes.none { it.id == door }) node(door, room.doorX, room.doorY, NodeType.VRATA)
            node(id(room.name), room.x, room.y, NodeType.PROSTORIJA, room.name)
            edges += Edge(id(room.name), door, EdgeType.HOD)
            edges += Edge(door, id(room.attachTo), EdgeType.HOD)
        }

        // Stepenište (desni krak) i lift u sredini stepeništa.
        node(id("S"), 680f, 355f, NodeType.STEPENISTE)
        node(id("L"), 660f, 355f, NodeType.LIFT)
        edges += Edge(id("S"), id(hall(660f)), EdgeType.HOD)
        edges += Edge(id("L"), id(hall(660f)), EdgeType.HOD)

        // Predvorje između hola i glavnog ulaza, spojni prolaz levo od hola, prolaz ka
        // Amfiteatrima kroz stepenišni prostor.
        node(id(VESTIBULE), 677f, 476f, NodeType.HODNIK)
        edges += Edge(id(VESTIBULE), id(hall(677f)), EdgeType.HOD)
        node(ENTRANCE_ID, 677f, 515f, NodeType.ULAZ)
        edges += Edge(ENTRANCE_ID, id(VESTIBULE), EdgeType.HOD)
        node(PASSAGE_ID, 322f, 414f, NodeType.PROLAZ)
        edges += Edge(PASSAGE_ID, id(hall(435f)), EdgeType.HOD)
        node(AMF_PASSAGE_ID, 688f, 311f, NodeType.PROLAZ)
        edges += Edge(AMF_PASSAGE_ID, id(hall(677f)), EdgeType.HOD)

        return nodes to edges.toList()
    }
}
