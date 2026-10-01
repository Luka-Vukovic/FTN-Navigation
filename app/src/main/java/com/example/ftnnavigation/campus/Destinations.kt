package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.Route

private val F_BLOCK_ROOM = Regex("""F[ -]\d+""") // F 315, F-208 (ne "Fizika")
private val AMPHITHEATRE = Regex("""A\d""")
private val COMPUTER_LAB = Regex("""L\d( \(RC\))?""")
private val NUMBERED_ROOM = Regex("""\d{3}[A-Z]?""")
private val AR_ROOM = Regex("""AR\d""") // AR0...AR6: prizemlje Amfiteatara, zapadna strana (FtnGO)

/**
 * Privremeno u Nastavnom bloku dok se ne sazna tačno (korisnik, 29.09.2026): "neka bude nastavni
 * blok dok ne saznam". "Hemija 2" je uz "Hemija".
 */
private val PROVISIONAL_NB = listOf("Fizika", "Hemija")

/** Mesta van kampusa, predaleko da bi bila na mapi (korisnik): prefiks oznake sale -> naziv. */
private val OFF_CAMPUS = mapOf("MF-" to "Medicinski fakultet")

/**
 * Oznake koje su druga oznaka ucrtane sale: 204A, 205A i 208A su drugi ulazi učionica 204, 205 i 208 (korisnik);
 * "O12" iz rasporeda (slovo O) je 012 u prizemlju NB-a (teren 01.10.2026).
 */
private val ROOM_ALIASES = mapOf("204A" to "204", "205A" to "205", "208A" to "208", "O12" to "012")

/** Naziv mesta van kampusa za salu (MF-27 -> Medicinski fakultet), ili null. */
fun offCampusPlaceOf(room: String): String? = OFF_CAMPUS.entries.find { room.trim().startsWith(it.key) }?.value

/**
 * Zgrada sale po oznaci iz rasporeda (podaci sa terena), ili null ako nije poznata ili je van
 * kampusa ([offCampusPlaceOf]).
 */
fun buildingOfRoom(room: String): String? {
    val name = room.trim()
    return when {
        name == "O12" -> "NB" // korisnik; u PDF-u sa slovom O
        // "L1" (bez "(RC)") nije računarski centar NB-a: stepenice naviše sa kraja prolaza iz NB-a vode u L1
        // (korisnik, po oznaci na vratima) - iznad hodnika iza amfiteatara.
        name == "L1" -> "AMF"
        name == "Scen-LAB" -> "AMF" // FtnGO: suteren Amfiteatara (ranije NB - korisnik prihvatio ispravku)
        PROVISIONAL_NB.any { name.startsWith(it) } -> "NB"
        name.startsWith("GRID-") -> "AMF" // suteren Amfiteatara, sa svojim ulazom sa zapada
        AR_ROOM.matches(name) -> "AMF"
        name.startsWith("NTP") -> "NTP"
        name.startsWith("ITC") -> "ITC" // ITC03, ITCA1, ITCS-01, ITCS-RC...
        name.startsWith("LG ") -> "DGG" // LG 001...LG 107 - korisnik našao izvor (30.09.2026)
        name.startsWith("MI ") -> "MI"
        F_BLOCK_ROOM.matches(name) -> "F"
        AMPHITHEATRE.matches(name) || name.startsWith("INT") -> "AMF"
        name.startsWith("AH") || COMPUTER_LAB.matches(name) || NUMBERED_ROOM.matches(name) -> "NB"
        else -> null
    }
}

/**
 * Cilj rute: [node] je ucrtana sala ili zgrada. [approximate] = sala postoji u rasporedu, ali
 * nije ucrtana, pa ruta vodi samo do njene zgrade ([building]).
 */
data class RouteTarget(val node: Node, val building: CampusBuilding?, val approximate: Boolean)

/**
 * Odredište (naziv sale iz rasporeda ili naziv zgrade) u čvor grafa: sala iz grafa, zgrada po
 * nazivu, ili zgrada sale po oznaci ([buildingOfRoom]). Null ako se ne zna gde je.
 */
fun resolveTarget(destination: String, graph: BuildingGraph, campus: CampusData): RouteTarget? {
    (graph.room(destination) ?: ROOM_ALIASES[destination.trim()]?.let(graph::room))?.let { room ->
        return RouteTarget(room, campus.building(room.buildingId), approximate = false)
    }
    campus.buildingByName(destination)?.let { building ->
        return graph.node(building.nodeId)?.let { RouteTarget(it, building, approximate = false) }
    }
    val building = buildingOfRoom(destination)?.let(campus::building) ?: return null
    return graph.node(building.nodeId)?.let { RouteTarget(it, building, approximate = true) }
}

/**
 * Ruta između dve sale iz rasporeda ([fromRoom] null = od glavnog ulaza Nastavnog bloka),
 * ili null ako se ne zna gde je neka od njih. Neucrtana sala se zamenjuje svojom zgradom.
 */
fun routeBetween(graph: BuildingGraph, campus: CampusData, fromRoom: String?, toRoom: String): Route? {
    val to = resolveTarget(toRoom, graph, campus) ?: return null
    val from = if (fromRoom == null) NbPlan.ENTRANCE_ID else resolveTarget(fromRoom, graph, campus)?.node?.id
    return graph.route(from ?: return null, to.node.id)
}
