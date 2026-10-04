package com.example.ftnnavigation.poc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * Natpis koji treba smestiti: [anchor] je tačka natpisa (px mape), [size] veličina na ekranu, a [candidates] gornji
 * levi uglovi u ravni natpisa (mapa zarotirana oko [anchor] nazad za rotaciju mape), redom po prednosti.
 */
internal class LabelRequest(val anchor: Offset, val size: Size, val candidates: List<Offset>)

/**
 * Kandidati za natpis veličine [size] uz tačku [anchor]: na sredini (natpis zgrade sa obrisom - [centered]), pa sa
 * strane - prvo [preferEast] strana, pa druga - pa iznad i ispod. Natpis sa strane počinje [gap] od tačke.
 */
internal fun labelCandidates(anchor: Offset, size: Size, centered: Boolean, preferEast: Boolean, gap: Float): List<Offset> {
    val top = anchor.y - size.height / 2f
    val left = anchor.x - size.width / 2f
    val east = Offset(anchor.x + gap, top)
    val west = Offset(anchor.x - gap - size.width, top)
    val sides = if (preferEast) listOf(east, west) else listOf(west, east)
    val center = listOf(Offset(left, top)).takeIf { centered }.orEmpty()
    return center + sides + Offset(left, anchor.y - gap - size.height) + Offset(left, anchor.y + gap)
}

/**
 * Za svaki natpis (raniji imaju prednost) prvi kandidat koji se na ekranu ne preklapa sa već smeštenim natpisima
 * (uz razmak [padding]) ni sa tačkama [obstacles] (px mape, poluprečnik [obstacleRadius]); null = nema mesta, natpis
 * se ne crta (pojavi se na većem zumu, jer natpisi ostaju iste veličine na ekranu, a mapa raste).
 *
 * Natpisi su na ekranu uspravni, a mapa je zarotirana za [rotationDeg]: poredi se u ravni ekrana - tačka natpisa
 * zarotirana sa mapom, natpis oko nje neokrenut. Zato strane izabrane za mapu sa severom gore posle okreta mape
 * mogu da se preklope (teren 04.10.2026: "Služba smeštaja" preko "Zdravstvene zaštite" na mapi okrenutoj za 90°).
 */
internal fun placeWithoutOverlap(
    requests: List<LabelRequest>,
    obstacles: List<Offset>,
    obstacleRadius: Float,
    padding: Float,
    rotationDeg: Float,
): List<Offset?> {
    val taken = obstacles.map { Rect(it.rotated(rotationDeg), obstacleRadius) }.toMutableList()
    return requests.map { request ->
        // Pomeraj iz ravni natpisa u ravan ekrana (bez zajedničkog pomeraja i zuma, nebitnih za preklapanje).
        val shift = request.anchor.rotated(rotationDeg) - request.anchor
        request.candidates.firstOrNull { candidate ->
            val rect = Rect(candidate + shift, request.size).inflate(padding)
            taken.none { it.overlaps(rect) }
        }?.also { taken += Rect(it + shift, request.size).inflate(padding) }
    }
}
