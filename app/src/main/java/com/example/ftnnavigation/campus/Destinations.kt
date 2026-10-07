package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.IndoorBuilding
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.indoorBuilding
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.graph.RoutingProfile
import com.example.ftnnavigation.graph.routeOrFallback

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
 * "O12" iz rasporeda (slovo O) je 012 u prizemlju NB-a (teren 01.10.2026); "F-208" je u rasporedu napisano sa
 * crticom, a ostale sale F-bloka razmakom ("F 315") - na planu su sve sa razmakom. NTP (teren 03.10.2026): na
 * vratima jedne sobe na II spratu piše 221 i 222 (119-123 na I spratu su od 04.10.2026 posebne sobe). F-blok (table
 * na spratovima): 224 i 225 su jedna soba; 202 i 203 su na tabli dve sobe, a na crtežu jedna. "MI A20" iz rasporeda je
 * A2-0 sa liste lamele A.
 */
private val ROOM_ALIASES = mapOf(
    "204A" to "204", "205A" to "205", "208A" to "208", "O12" to "012", "F-208" to "F 208", "NTP-222" to "NTP-221",
    "F 203" to "F 202", "F 225" to "F 224", "MI A20" to "MI A2-0",
)

/** Oznaka sale iz rasporeda kako je ucrtana na planu (O12 -> 012, 204A -> 204), inače ista oznaka. */
fun canonicalRoom(room: String): String = room.trim().let { ROOM_ALIASES[it] ?: it }

/**
 * Odredišta iz [names] koja odgovaraju pretrazi [query], bolja poklapanja prva: ceo naziv, pa naziv koji
 * počinje upitom, pa ostali, pa odredišta nađena tek uz [contextOf] (u istom redosledu kao [names]). Prazan
 * upit vraća sve.
 *
 * Bez obzira na velika slova, kvačice (svecana -> Svečana), razmake i crtice (ah9 -> AH9, scenlab ->
 * Scen-LAB); reči upita mogu biti bilo kojim redom (sala svecana). Sala se nalazi i po drugoj oznaci
 * (O12 -> 012, 204A -> 204), i po zgradi: [contextOf] daje nazive zgrade sale ("Nastavni blok", "NB"), a reč
 * upita koja nije u nazivu sale sme da bude početak reči naziva zgrade (nastavni -> sve sale NB-a,
 * "nastavni 101" -> 101, ne Kula 101).
 */
fun searchDestinations(
    names: List<String>,
    query: String,
    contextOf: (String) -> List<String> = { emptyList() },
): List<String> {
    val words = query.split(' ').map(::searchKey).filter { it.isNotEmpty() }
    if (words.isEmpty()) return names
    val whole = words.joinToString("")
    val aliases = ROOM_ALIASES.entries.groupBy({ it.value }, { searchKey(it.key) })
    return names
        .mapNotNull { name ->
            val keys = listOf(searchKey(name)) + aliases[name].orEmpty()
            val rank = keys.minOf { key ->
                when {
                    key == whole -> 0
                    key.startsWith(whole) -> 1
                    words.all { it in key } -> 2
                    else -> 3
                }
            }
            when {
                rank < 3 -> name to rank
                matchesWithContext(words, keys, contextOf(name)) -> name to 3
                else -> null
            }
        }
        .sortedBy { it.second }
        .map { it.first }
}

/** Sala iz grafa -> naziv i oznaka njene zgrade ("Nastavni blok", "NB"), za pretragu sala po zgradi. */
fun roomBuildingNames(graph: BuildingGraph, campus: CampusData): Map<String, List<String>> =
    graph.rooms.mapNotNull { room ->
        val name = room.name ?: return@mapNotNull null
        name to listOfNotNull(campus.building(room.buildingId)?.name, room.buildingId)
    }.toMap()

/** Koliko nedavnih odredišta se pamti (i prikazuje na vrhu izbora odredišta). */
const val MAX_RECENT_DESTINATIONS = 5

/** Nedavna odredišta posle izbora [name]: ono na vrh (bez ponavljanja), najviše [max]. */
fun List<String>.withRecent(name: String, max: Int = MAX_RECENT_DESTINATIONS): List<String> =
    (listOf(name) + filter { it != name }).take(max)

/** Omiljena posle dodira zvezdice za [name]: izbacuje ako je već tu, inače dodaje na kraj. */
fun List<String>.toggled(name: String): List<String> = if (name in this) this - name else this + name

/**
 * Brzi izbor na vrhu izbora odredišta: omiljena, pa nedavna koja nisu među omiljenima. Samo nazivi koji i dalje postoje
 * među odredištima ([available]) - sala može da nestane ili promeni naziv posle izmene plana.
 */
fun quickDestinations(favorites: List<String>, recents: List<String>, available: Set<String>): Pair<List<String>, List<String>> {
    val favs = favorites.filter { it in available }
    return favs to recents.filter { it in available && it !in favs }
}

/** Svaka reč upita je u nekoj oznaci ([keys]) ili je početak reči nekog naziva iz [context] (ili celog naziva). */
private fun matchesWithContext(words: List<String>, keys: List<String>, context: List<String>): Boolean {
    if (context.isEmpty()) return false
    val contextWords = context.flatMap { text ->
        text.split(' ', '-').map(::searchKey).filter { it.isNotEmpty() } + searchKey(text)
    }
    return words.all { word -> keys.any { word in it } || contextWords.any { it.startsWith(word) } }
}

/** Naziv za poređenje u pretrazi: mala slova, bez kvačica, razmaka i znakova (Scen-LAB -> scenlab). */
private fun searchKey(text: String): String = buildString {
    for (c in text.lowercase()) {
        when (c) {
            'č', 'ć' -> append('c')
            'š' -> append('s')
            'ž' -> append('z')
            'đ' -> append("dj")
            else -> if (c.isLetterOrDigit()) append(c)
        }
    }
}

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
    (graph.room(destination) ?: graph.room(canonicalRoom(destination)))?.let { room ->
        return RouteTarget(room, campus.building(room.buildingId), approximate = false)
    }
    campus.buildingByName(destination)?.let { building ->
        return graph.node(building.nodeId)?.let { RouteTarget(it, building, approximate = false) }
    }
    val building = buildingOfRoom(destination)?.let(campus::building) ?: return null
    return graph.node(building.nodeId)?.let { RouteTarget(it, building, approximate = true) }
}

/** Gde je mesto stavke: ucrtana sala ([Room] - zgrada sa planom i sprat) ili samo naziv ([Named]). */
sealed interface PlaceLocation {
    data class Room(val plan: IndoorBuilding, val floor: Int) : PlaceLocation
    data class Named(val name: String) : PlaceLocation
}

/**
 * Gde je [place] (sala ili zgrada iz izbora odredišta), za Početnu i obaveštenje: ucrtana sala -> zgrada i sprat;
 * zgrada ili sala koja nije ucrtana -> naziv zgrade; van kampusa -> "Medicinski fakultet"; null ako se ne zna.
 */
fun placeLocation(place: String, graph: BuildingGraph, campus: CampusData): PlaceLocation? {
    offCampusPlaceOf(place)?.let { return PlaceLocation.Named(it) }
    val target = resolveTarget(place, graph, campus)
    val plan = target?.node?.let { indoorBuilding(it.buildingId) }
    if (target != null && target.node.type == NodeType.PROSTORIJA && plan != null) {
        return PlaceLocation.Room(plan, target.node.floor)
    }
    val building = target?.building ?: buildingOfRoom(place)?.let(campus::building)
    return building?.name?.let { PlaceLocation.Named(it) }
}

/**
 * Ruta između dve sale iz rasporeda ([fromRoom] null = od glavnog ulaza Nastavnog bloka),
 * ili null ako se ne zna gde je neka od njih. Neucrtana sala se zamenjuje svojom zgradom. Promena sprata po [profile]
 * (podešavanje), a gde po njemu puta nema - bez izbegavanja ([routeOrFallback]).
 */
fun routeBetween(
    graph: BuildingGraph,
    campus: CampusData,
    fromRoom: String?,
    toRoom: String,
    profile: RoutingProfile = RoutingProfile(),
): Route? {
    val to = resolveTarget(toRoom, graph, campus) ?: return null
    val from = if (fromRoom == null) NbPlan.ENTRANCE_ID else resolveTarget(fromRoom, graph, campus)?.node?.id
    return routeOrFallback(profile) { graph.route(from ?: return null, to.node.id, it) }
}
