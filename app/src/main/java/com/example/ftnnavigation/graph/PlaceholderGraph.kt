package com.example.ftnnavigation.graph

/**
 * Privremeni graf zgrade. Prizemlje prati plan res/drawable/floor_plan_placeholder.xml,
 * precrtan sa evakuacionog plana Nastavnog bloka: hol levo, uži hodnik desno (Svečana sala
 * gore, Studentska služba dole), stepenište sa liftom u sredini gore, glavni ulaz dole kroz
 * predvorje kod portirnice, spojni prolaz ka susednoj zgradi levo.
 *
 * Viši spratovi nemaju plan, pa koriste isti raspored hodnika i soba (bez ulaza).
 * Nazivi učionica su uzeti iz rasporeda (NTP-xxx, sprat = prva cifra) samo kao
 * placeholderi - ne odgovaraju stvarnom rasporedu prostorija. Promena podataka zahteva
 * povećanje verzije [GraphDatabase] (baza se tada briše i ponovo puni).
 */
object PlaceholderGraph {
    const val BUILDING_ID = "NB" // Nastavni blok

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
        435f to 414f, 477f to 414f, 497f to 414f, 525f to 414f, 546f to 414f, 569f to 414f,
        580f to 414f, 596f to 414f, 612f to 414f, 660f to 414f, 677f to 414f, 750f to 414f,
        770f to 423f, 822f to 423f, 995f to 423f,
    )

    /**
     * Mesto za prostoriju: centar sobe, vrata na zidu i ključ čvora hodnika naspram vrata.
     * Ruta ide centar -> vrata -> hodnik, pa ivice ne seku zidove.
     */
    private data class RoomSlot(val x: Float, val y: Float, val doorX: Float, val doorY: Float, val attachTo: String)

    private fun hall(x: Float) = "H${x.toInt()}"

    private val slots = mapOf(
        // gornji red, levo od stepeništa
        "T1" to RoomSlot(450f, 340f, 477f, 379f, hall(477f)),
        "T2" to RoomSlot(502f, 345f, 497f, 379f, hall(497f)),
        "T3" to RoomSlot(537f, 345f, 546f, 379f, hall(546f)),
        "T4" to RoomSlot(571f, 345f, 580f, 379f, hall(580f)),
        "T5" to RoomSlot(608f, 345f, 612f, 379f, hall(612f)),
        // desno od stepeništa (WC), svečana sala
        "WC" to RoomSlot(745f, 340f, 750f, 370f, hall(750f)),
        "SVECANA" to RoomSlot(900f, 355f, 822f, 400f, hall(822f)),
        // donji red; B1 i B3 dele malo predsoblje sa vratima kod x 525
        "B1" to RoomSlot(453f, 485f, 525f, 449f, hall(525f)),
        "B3" to RoomSlot(540f, 485f, 525f, 449f, hall(525f)),
        "B4" to RoomSlot(573f, 478f, 569f, 449f, hall(569f)),
        "B5" to RoomSlot(608f, 478f, 596f, 449f, hall(596f)),
        "PORTIR" to RoomSlot(645f, 490f, 662f, 482f, VESTIBULE),
        "SSLUZBA" to RoomSlot(880f, 475f, 765f, 447f, hall(770f)),
    )

    private val groundFloorRooms = mapOf(
        "T1" to "NTP-001",
        "B1" to "NTP-004",
        "B4" to "NTP-A",
        "WC" to "Toalet",
        "PORTIR" to "Portir",
        "SVECANA" to "Svečana sala",
        "SSLUZBA" to "Studentska služba",
    )

    /** Učionice viših spratova se redom raspoređuju duž hodnika. */
    private val upperSlotOrder = listOf("T1", "B1", "T2", "T3", "B3", "T4", "B4", "T5", "B5")
    private val upperFloorRooms = listOf(
        listOf("NTP-115", "NTP-116"),
        listOf("NTP-222"),
        listOf("NTP-307", "NTP-309", "NTP-311", "NTP-316", "NTP-317"),
        listOf("NTP-408", "NTP-410", "NTP-411", "NTP-415", "NTP-417", "NTP-418"),
        listOf("NTP-504", "NTP-505", "NTP-506", "NTP-507", "NTP-508"),
    )

    private const val STAIRS_X = 680f // desni krak stepeništa
    private const val LIFT_X = 660f
    private const val STAIRS_LIFT_Y = 355f
    private val stairsLiftHall = hall(660f)

    val floors: IntRange get() = 0..upperFloorRooms.size

    private val graph by lazy { buildGraph() }
    val nodes: List<Node> get() = graph.first
    val edges: List<Edge> get() = graph.second

    fun stairsId(floor: Int) = "$BUILDING_ID-$floor-S"
    fun liftId(floor: Int) = "$BUILDING_ID-$floor-L"

    /** Glavni ulaz (dole, kod portirnice). */
    const val ENTRANCE_ID = "$BUILDING_ID-0-ULAZ"

    /** Spojni prolaz ka susednoj zgradi (levo). */
    const val PASSAGE_ID = "$BUILDING_ID-0-PROLAZ"

    private fun buildGraph(): Pair<List<Node>, List<Edge>> {
        val nodes = mutableListOf<Node>()
        // Skup: vrata koja dele dve sobe (predsoblje B1/B3) kače se na hodnik samo jednom.
        val edges = linkedSetOf<Edge>()
        fun id(floor: Int, key: String) = "$BUILDING_ID-$floor-$key"
        fun node(id: String, floor: Int, x: Float, y: Float, type: NodeType, name: String? = null) {
            nodes += Node(id, BUILDING_ID, floor, (x - ORIGIN_X) / PLAN_W, (y - ORIGIN_Y) / PLAN_H, type, name)
        }

        for (floor in floors) {
            corridor.forEach { (x, y) -> node(id(floor, hall(x)), floor, x, y, NodeType.HODNIK) }
            corridor.zipWithNext { a, b -> edges += Edge(id(floor, hall(a.first)), id(floor, hall(b.first)), EdgeType.HOD) }

            val rooms = if (floor == 0) groundFloorRooms else upperSlotOrder.zip(upperFloorRooms[floor - 1]).toMap()
            for ((slotKey, name) in rooms) {
                val slot = slots.getValue(slotKey)
                val door = id(floor, "V${slot.doorX.toInt()}_${slot.doorY.toInt()}")
                if (nodes.none { it.id == door }) node(door, floor, slot.doorX, slot.doorY, NodeType.VRATA)
                node(id(floor, name), floor, slot.x, slot.y, NodeType.PROSTORIJA, name)
                edges += Edge(id(floor, name), door, EdgeType.HOD)
                edges += Edge(door, id(floor, slot.attachTo), EdgeType.HOD)
            }

            node(stairsId(floor), floor, STAIRS_X, STAIRS_LIFT_Y, NodeType.STEPENISTE)
            node(liftId(floor), floor, LIFT_X, STAIRS_LIFT_Y, NodeType.LIFT)
            edges += Edge(stairsId(floor), id(floor, stairsLiftHall), EdgeType.HOD)
            edges += Edge(liftId(floor), id(floor, stairsLiftHall), EdgeType.HOD)
        }

        // Prizemlje: predvorje između hola i glavnog ulaza, spojni prolaz levo od hola.
        node(id(0, VESTIBULE), 0, 677f, 476f, NodeType.HODNIK)
        edges += Edge(id(0, VESTIBULE), id(0, hall(677f)), EdgeType.HOD)
        node(ENTRANCE_ID, 0, 677f, 515f, NodeType.ULAZ)
        edges += Edge(ENTRANCE_ID, id(0, VESTIBULE), EdgeType.HOD)
        node(PASSAGE_ID, 0, 322f, 414f, NodeType.ULAZ)
        edges += Edge(PASSAGE_ID, id(0, hall(435f)), EdgeType.HOD)

        // Stepenice vode samo na susedni sprat; lift vozi između bilo koja dva
        // (čekanje se plaća jednom po vožnji, ne po spratu).
        for (floor in floors) {
            if (floor + 1 in floors) edges += Edge(stairsId(floor), stairsId(floor + 1), EdgeType.STEPENICE)
            for (other in floor + 1..floors.last) edges += Edge(liftId(floor), liftId(other), EdgeType.LIFT)
        }
        return nodes to edges.toList()
    }
}
