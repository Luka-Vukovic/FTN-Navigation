package com.example.ftnnavigation.campus

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pretraga odredišta ([searchDestinations]) nad nazivima kakvi su u izboru odredišta. */
class DestinationSearchTest {

    private val names = listOf(
        "012", "101", "204", "AH9", "Kula 101", "L1", "L1 (RC)", "NTP-101", "Scen-LAB", "Svečana sala",
        "Studentska služba", "Čitaonica",
    )

    private fun search(query: String) = searchDestinations(names, query)

    @Test
    fun emptyQuery_returnsAll() {
        assertEquals(names, search(""))
        assertEquals(names, search("   "))
    }

    @Test
    fun exactThenPrefixThenContained() {
        assertEquals(listOf("101", "Kula 101", "NTP-101"), search("101"))
        assertEquals(listOf("L1", "L1 (RC)"), search("l1"))
    }

    @Test
    fun ignoresCaseDiacriticsSpacesAndDashes() {
        assertEquals(listOf("Svečana sala"), search("SVECANA"))
        assertEquals(listOf("Čitaonica"), search("citaonica"))
        assertEquals(listOf("AH9"), search("ah 9"))
        assertEquals(listOf("Scen-LAB"), search("scenlab"))
        assertEquals(listOf("Scen-LAB"), search("scen lab"))
        assertEquals(listOf("L1 (RC)"), search("l1 rc"))
        assertEquals(listOf("Studentska služba"), search("sluzba"))
    }

    @Test
    fun wordsInAnyOrder() {
        assertEquals(listOf("Svečana sala"), search("sala svečana"))
        assertEquals(listOf("Kula 101"), search("101 kula"))
    }

    @Test
    fun roomFoundByOtherLabel() {
        assertEquals(listOf("012"), search("O12"))
        assertEquals(listOf("204"), search("204a"))
    }

    @Test
    fun noMatch_isEmpty() {
        assertEquals(emptyList<String>(), search("menza"))
    }

    private val buildings = mapOf(
        "012" to listOf("Nastavni blok", "NB"), "101" to listOf("Nastavni blok", "NB"),
        "204" to listOf("Nastavni blok", "NB"), "AH9" to listOf("Nastavni blok", "NB"),
        "Kula 101" to listOf("Kula", "KULA"), "L1" to listOf("Amfiteatri", "AMF"),
        "L1 (RC)" to listOf("Nastavni blok", "NB"), "NTP-101" to listOf("Naučno-tehnološki park", "NTP"),
        "Scen-LAB" to listOf("Amfiteatri", "AMF"), "Svečana sala" to listOf("Nastavni blok", "NB"),
    )

    private fun searchRooms(query: String) = searchDestinations(names, query) { buildings[it].orEmpty() }

    @Test
    fun roomsByBuilding() {
        assertEquals(listOf("012", "101", "204", "AH9", "L1 (RC)", "Svečana sala"), searchRooms("nastavni"))
        assertEquals(listOf("012", "101", "204", "AH9", "L1 (RC)", "Svečana sala"), searchRooms("nastavni blok"))
        assertEquals(listOf("L1", "Scen-LAB"), searchRooms("amfiteatri"))
        assertEquals(listOf("NTP-101"), searchRooms("tehnoloski"))
        assertEquals(listOf("012", "101", "204", "AH9", "L1 (RC)", "Svečana sala"), searchRooms("nb"))
    }

    @Test
    fun roomAndBuilding_together() {
        assertEquals(listOf("101"), searchRooms("nastavni 101"))
        assertEquals(listOf("101"), searchRooms("101 nb"))
        assertEquals(listOf("L1"), searchRooms("l1 amf"))
        assertEquals(listOf("204"), searchRooms("204a nastavni"))
    }

    @Test
    fun nameMatchesFirst_thenBuilding() {
        // "Kula 101" po nazivu; nijedna druga sala nije u Kuli
        assertEquals(listOf("Kula 101"), searchRooms("kula"))
        // "sala" je u nazivu Svečane sale; zgrada se ne gleda kad reč nije početak reči naziva zgrade
        assertEquals(listOf("Svečana sala"), searchRooms("sala"))
        // nazivi sa "a" prvi (204 po oznaci 204A), pa sala Amfiteatara koja nema "a" u nazivu
        assertEquals(
            listOf("AH9", "204", "Kula 101", "Scen-LAB", "Svečana sala", "Studentska služba", "Čitaonica", "L1"),
            searchRooms("a"),
        )
    }
}
