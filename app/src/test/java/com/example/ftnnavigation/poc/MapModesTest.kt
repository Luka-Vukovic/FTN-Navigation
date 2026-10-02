package com.example.ftnnavigation.poc

import com.example.ftnnavigation.campus.CampusData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Prekidač Mape: kad se po GPS-u zna zgrada, samo kampus, ta zgrada i ono što je prikazano. */
class MapModesTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    @Test
    fun unknownOrOutside_allModes() {
        assertEquals(MapMode.entries, shownModes(MapMode.NB, here = null))
    }

    @Test
    fun inBuildingWithPlan_campusAndThatBuilding() {
        assertEquals(listOf(MapMode.KAMPUS, MapMode.KULA), shownModes(MapMode.KAMPUS, campus.building("KULA")))
    }

    /** Prikazana zgrada (npr. izabrana sala u NTP-u) ostaje na prekidaču. */
    @Test
    fun shownBuildingStays() {
        assertEquals(listOf(MapMode.KAMPUS, MapMode.NB, MapMode.NTP), shownModes(MapMode.NTP, campus.building("NB")))
    }

    /** Zgrada bez plana (ITC; do 02.10.2026 i F-blok): od planova ništa, ostaje kampus (ostalo u meniju). */
    @Test
    fun inBuildingWithoutPlan_onlyCampus() {
        assertEquals(listOf(MapMode.KAMPUS), shownModes(MapMode.KAMPUS, campus.building("ITC")))
    }
}
