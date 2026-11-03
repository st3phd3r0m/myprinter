package com.example.myprinter.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictatedFileNameTest {

    @Test
    fun spacesBecomeUnderscores() {
        assertEquals("facture_EDF_novembre", dictatedFileName("facture EDF novembre"))
        assertEquals("relevé_de_compte", dictatedFileName("  relevé   de\tcompte "))
    }

    @Test
    fun dropsWhatFileNamesCantHold() {
        assertEquals("impôts_2026_avis", dictatedFileName("impôts 2026/avis"))
        assertEquals("question", dictatedFileName("question ?"))
        assertEquals("cache", dictatedFileName(".cache"))
    }

    @Test
    fun freeNameNumbersTakenNames() {
        val taken = mutableSetOf<String>()
        repeat(3) { taken += freeName("facture_EDF", "jpg") { it in taken } }
        assertEquals(setOf("facture_EDF.jpg", "facture_EDF_1.jpg", "facture_EDF_2.jpg"), taken)
        assertEquals("scan.pdf", freeName("scan", "pdf") { false })
    }

    @Test
    fun nothingLeftGivesNull() {
        assertNull(dictatedFileName(""))
        assertNull(dictatedFileName(" ? * "))
    }
}
