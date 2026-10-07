package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.Route

/**
 * Vrsta mesta za "najbliže". Korisnik (07.10.2026): "treba da svi budu jednaki, i onda se pronađe najbliži" - nema
 * razlike M/Ž kod toaleta ni prednosti jednog kioska nad menzom; bira se najkraći hod.
 */
enum class PlaceKind { TOALET, HRANA, UCENJE, SKRIPTARNICA }

/** Oznaka vrste u JSON-u planova (generatori `tools/zgrade`, `tools/ntp`: `TOALET`). */
const val AMENITY_TOALET = "TOALET"

/** Sale sa nazivom koje su mesto neke vrste. */
private val NAMED_PLACES = mapOf(
    "Kiosk" to PlaceKind.HRANA,
    "Biblioteka" to PlaceKind.UCENJE,
    "Čitaonica" to PlaceKind.UCENJE,
    "Skriptarnica" to PlaceKind.SKRIPTARNICA,
)

/** Službe na kampusu (zgrada bez plana, čvor ZGRADA) koje su mesto neke vrste. */
private val SERVICE_PLACES = mapOf("MENZA" to PlaceKind.HRANA)

/** Vrsta mesta čvora: toalet iz generatora, sala po nazivu, služba kampusa; null - nije mesto za "najbliže". */
fun placeKindOf(node: Node, campus: CampusData): PlaceKind? = when {
    node.amenity == AMENITY_TOALET -> PlaceKind.TOALET
    node.type == NodeType.PROSTORIJA -> node.name?.let(NAMED_PLACES::get)
    node.type == NodeType.ZGRADA -> campus.buildings.find { it.nodeId == node.id }?.let { SERVICE_PLACES[it.id] }
    else -> null
}

/** Mesto vrste i ruta do njega. */
data class NearbyPlace(val node: Node, val route: Route)

/**
 * Sva mesta vrste [kind], po vremenu hoda - najbliže prvo. [routeTo] je ruta od trenutnog polaska (kao ruta na Mapi:
 * pozicija, GPS ili glavni ulaz, po podešavanju sprata). Mesta do kojih puta nema se izostavljaju. Po jedan A* do
 * svakog mesta (do ~40 toaleta; ~0,1 ms po ruti na računaru) - isti put kao ruta koja se posle crta.
 */
fun nearestPlaces(kind: PlaceKind, graph: BuildingGraph, campus: CampusData, routeTo: (String) -> Route?): List<NearbyPlace> =
    graph.nodes.filter { placeKindOf(it, campus) == kind }
        .mapNotNull { node -> routeTo(node.id)?.let { NearbyPlace(node, it) } }
        .sortedBy { it.route.durationSec }
