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
}
