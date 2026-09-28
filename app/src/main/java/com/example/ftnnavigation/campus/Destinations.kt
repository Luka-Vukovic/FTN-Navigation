package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.PlaceholderGraph
import com.example.ftnnavigation.graph.Route

private val F_BLOCK_ROOM = Regex("""F \d+""")
private val AMPHITHEATRE = Regex("""A\d""")
private val COMPUTER_LAB = Regex("""L\d( \(RC\))?""")
private val NUMBERED_ROOM = Regex("""\d{3}[A-Z]?""")

/**
 * Zgrada sale po oznaci iz rasporeda (podaci sa terena), ili null ako nije poznata
 * (AR…, LG…, Scen-LAB, Fizika, Hemija).
 */
fun buildingOfRoom(room: String): String? {
    val name = room.trim()
    return when {
        name.startsWith("NTP") -> "NTP"
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
    graph.room(destination)?.let { room ->
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
    val from = if (fromRoom == null) PlaceholderGraph.ENTRANCE_ID else resolveTarget(fromRoom, graph, campus)?.node?.id
    return graph.route(from ?: return null, to.node.id)
}
