package com.example.ftnnavigation.poc

import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.PlanPlacement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Smer na planu (NB, NTP...) iz magnetskog azimuta, preko pravog smeštaja planova iz campus.json. */
class PlanHeadingTest {

    private val placements = CampusData.parse(File("src/main/assets/campus.json").readText()).placements()

    private val placement = placements.getValue(NbPlan.BUILDING_ID)

    private val declination = 5.5f

    /** Korak kao u [PocViewModel.onStep], preveden u metre kampusa -> geografski azimut. */
    private fun trueAzimuthOfStep(magneticAzimuth: Float, placement: PlanPlacement = this.placement): Float {
        val planUp = planUpMagneticAzimuthDeg(placement, declination)
        val rad = Math.toRadians((magneticAzimuth - planUp).toDouble())
        val dxM = sin(rad)
        val dyM = -cos(rad)
        val from = placement.toMeters(0.5f, 0.5f)
        val to = placement.toMeters(
            (0.5 + dxM / placement.scale.widthM).toFloat(),
            (0.5 + dyM / placement.scale.heightM).toFloat(),
        )
        // Kampus: x istok, y jug.
        return normalizeDeg(Math.toDegrees(atan2(to.x - from.x, from.y - to.y)).toFloat())
    }

    @Test
    fun stepOnPlan_goesInTrueDirection() {
        for (magnetic in 0 until 360 step 15) {
            val expected = normalizeDeg(magnetic + declination)
            assertEquals("magnetski $magnetic", 0f, angleDiffDeg(trueAzimuthOfStep(magnetic.toFloat()), expected), 0.1f)
        }
    }

    /** PDR radi na planu bilo koje zgrade sa planom (NTP od 01.10.2026) - smer mora da važi za svaki smeštaj. */
    @Test
    fun stepOnEveryPlan_goesInTrueDirection() {
        for (building in INDOOR_BUILDINGS) {
            val placement = placements.getValue(building.buildingId)
            for (magnetic in 0 until 360 step 15) {
                val expected = normalizeDeg(magnetic + declination)
                val actual = trueAzimuthOfStep(magnetic.toFloat(), placement)
                assertEquals("${building.buildingId} magnetski $magnetic", 0f, angleDiffDeg(actual, expected), 0.1f)
            }
        }
    }

    /** "Gore" na planu NB je ka Amfiteatrima, zapad-jugozapad (~246° geografski). */
    @Test
    fun planUp_isWestSouthWest() {
        assertEquals(246f - declination, planUpMagneticAzimuthDeg(placement, declination), 1f)
    }
}
