package com.example.ftnnavigation.poc

import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.graph.PlaceholderGraph
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Smer na planu NB iz magnetskog azimuta, preko pravog smeštaja plana iz campus.json. */
class PlanHeadingTest {

    private val placement = CampusData.parse(File("src/main/assets/campus.json").readText())
        .placements().getValue(PlaceholderGraph.BUILDING_ID)

    private val declination = 5.5f

    /** Korak kao u [PocViewModel.onStep], preveden u metre kampusa -> geografski azimut. */
    private fun trueAzimuthOfStep(magneticAzimuth: Float): Float {
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

    /** "Gore" na planu NB je ka Amfiteatrima, zapad-jugozapad (~246° geografski). */
    @Test
    fun planUp_isWestSouthWest() {
        assertEquals(246f - declination, planUpMagneticAzimuthDeg(placement, declination), 1f)
    }
}
