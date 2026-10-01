package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.PointM
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Geografske koordinate (GPS) u metre mape kampusa. Ista projekcija kao `to_xy` u
 * tools/kampus/build_campus.py - promena tamo (referentna tačka, oblast mape) mora i ovde.
 */
object CampusGeo {
    const val REF_LAT = 45.2455
    const val REF_LON = 19.85
    private const val M_PER_DEG_LAT = 110540.0
    private val M_PER_DEG_LON = 111320.0 * cos(Math.toRadians(REF_LAT))

    // Gornji levi ugao mape u metrima od referentne tačke (X_MIN, Y_MIN u skripti).
    private const val X_MIN = -235.0
    private const val Y_MIN = -195.0

    /** Tačka u metrima kampusa: x ka istoku, y ka jugu, (0, 0) = gornji levi ugao mape. */
    fun toCampus(lat: Double, lon: Double): PointM =
        PointM((lon - REF_LON) * M_PER_DEG_LON - X_MIN, (REF_LAT - lat) * M_PER_DEG_LAT - Y_MIN)
}

/** GPS lokacija u metrima kampusa; [accuracyM] = poluprečnik procene (68 %), kao `Location.accuracy`. */
data class GpsFix(val point: PointM, val accuracyM: Float)

/** Da li je tačka u obrisu zgrade (van dvorišta). Služba bez svog obrisa nije nigde. */
fun CampusBuilding.contains(p: PointM): Boolean =
    outline.isNotEmpty() && ringContains(outline, p) && holes.none { ringContains(it, p) }

/** Najmanja udaljenost tačke od zida (spoljnog ili dvorišta) u metrima; beskonačno bez obrisa. */
fun CampusBuilding.distanceToWallM(p: PointM): Double =
    (listOf(outline) + holes).filter { it.size >= 2 }.minOfOrNull { ring ->
        ring.indices.minOf { i -> segmentDistance(p, ring[i], ring[(i + 1) % ring.size]) }
    } ?: Double.POSITIVE_INFINITY

// Ray casting: broj preseka polu-prave ka istoku sa ivicama prstena.
private fun ringContains(ring: List<CampusPoint>, p: PointM): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val (xi, yi) = ring[i]
        val (xj, yj) = ring[j]
        if ((yi > p.y) != (yj > p.y) && p.x < (xj - xi) * (p.y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

private fun segmentDistance(p: PointM, a: CampusPoint, b: CampusPoint): Double {
    val ax = a[0].toDouble()
    val ay = a[1].toDouble()
    val dx = b[0] - ax
    val dy = b[1] - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((p.x - ax) * dx + (p.y - ay) * dy) / len2).coerceIn(0.0, 1.0)
    return hypot(p.x - (ax + t * dx), p.y - (ay + t * dy))
}

/**
 * U kojoj je zgradi korisnik, po GPS-u. GPS u zgradi je slab (lokacija luta 10-30 m, ili je
 * nema), pa se zgrada menja tek kad se [CONFIRM_FIXES] uzastopnih pouzdanih lokacija složi:
 * - u zgradi: tačka u obrisu zgrade sa nazivom, tačnost do [MAX_INSIDE_ACCURACY_M];
 * - napolju: tačka van svih obrisa, dalje od zida nego što je tačnost, tačnost do [MAX_OUTSIDE_ACCURACY_M].
 * Ostale lokacije (lošija tačnost, uz zid, spojni prolaz) ništa ne menjaju. Kad lokacije nestane,
 * zgrada ostaje poslednja poznata.
 */
class BuildingDetector(campus: CampusData) {
    private val named = campus.namedBuildings.filter { it.outline.isNotEmpty() }
    private val all = campus.buildings.filter { it.outline.isNotEmpty() }

    /** Zgrada u kojoj je korisnik; null = napolju ili se još ne zna ([isKnown]). */
    var current: CampusBuilding? = null
        private set

    /** Da li je bar jednom potvrđeno gde je korisnik (u zgradi ili napolju). */
    var isKnown = false
        private set

    // Kandidat se pamti po id-ju; OUTSIDE = napolju.
    private var candidate: String? = null
    private var count = 0

    /** Nova lokacija; true ako se [current] / [isKnown] promenio. */
    fun onFix(fix: GpsFix): Boolean {
        val vote = vote(fix) ?: return false
        if (vote == candidate) count++ else {
            candidate = vote
            count = 1
        }
        if (count < CONFIRM_FIXES) return false
        val building = named.find { it.id == vote }
        if (isKnown && building == current) return false
        current = building
        isKnown = true
        return true
    }

    /** Id zgrade, [OUTSIDE], ili null ako lokacija ništa ne govori. */
    private fun vote(fix: GpsFix): String? {
        val p = fix.point
        named.find { it.contains(p) }?.let { return if (fix.accuracyM <= MAX_INSIDE_ACCURACY_M) it.id else null }
        if (fix.accuracyM > MAX_OUTSIDE_ACCURACY_M || all.any { it.contains(p) }) return null
        return OUTSIDE.takeIf { all.all { it.distanceToWallM(p) > fix.accuracyM } }
    }

    companion object {
        const val CONFIRM_FIXES = 3
        const val MAX_INSIDE_ACCURACY_M = 30f
        const val MAX_OUTSIDE_ACCURACY_M = 20f
        private const val OUTSIDE = ""
    }
}
