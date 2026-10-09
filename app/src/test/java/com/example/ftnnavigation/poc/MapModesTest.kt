package com.example.ftnnavigation.poc

import com.example.ftnnavigation.campus.CampusData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Prekidač Mape: kampus, zgrada u kojoj je korisnik po GPS-u i ono što je prikazano; ostalo u meniju. */
class MapModesTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    /** Korisnik (04.10.2026): van zgrada "kampus i ...", zgrade u padajućem meniju (ranije sve - natpisi odsečeni). */
    @Test
    fun outsideOrUnknown_onlyCampus() {
        assertEquals(listOf(MapMode.KAMPUS), shownModes(MapMode.KAMPUS, here = null))
    }

    /** Zgrada izabrana iz menija ostaje na prekidaču dok je prikazana (da se vidi šta je izabrano). */
    @Test
    fun outside_shownBuildingStays() {
        assertEquals(listOf(MapMode.KAMPUS, MapMode.NB), shownModes(MapMode.NB, here = null))
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

    /** Zgrada bez plana (Građevinarstvo; do 09.10.2026 ITC, do 02.10.2026 i F-blok): od planova ništa, ostaje kampus (ostalo u meniju). */
    @Test
    fun inBuildingWithoutPlan_onlyCampus() {
        assertEquals(listOf(MapMode.KAMPUS), shownModes(MapMode.KAMPUS, campus.building("DGG")))
    }
}
