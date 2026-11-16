package com.pokervision.shared

import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveSessionTest {
    private fun session() = LiveSession(MemoryStore(NSTemporaryDirectory() + "test_memory.bin").also { it.forgetAll() }, "TEXAS_HOLDEM")

    @Test
    fun manualCardsGiveAdvice() {
        val s = session()
        assertEquals("need_mine", Advisor.analyze(s.adviceRequest()).issue)
        s.addCards(listOf("AS", "AH"), LiveSession.MINE)
        assertEquals(listOf("As", "Ah"), s.mine.map { it.code })
        val advice = Advisor.analyze(s.adviceRequest())
        assertNull(advice.issue)
        assertEquals("bet", advice.action)
        assertTrue(advice.equityPercent in 80..90, "AA heads-up: ${advice.equityPercent}")
    }

    @Test
    fun boardAndCapacity() {
        val s = session()
        assertEquals(2, s.freeSlots(LiveSession.MINE))
        s.addCards(listOf("KS", "KH", "2C"), LiveSession.MINE)
        assertEquals(2, s.mine.size)
        s.addCards(listOf("KD", "7C", "2D"), LiveSession.TABLE)
        assertEquals(3, s.board.size)
        assertEquals(2, s.freeSlots(LiveSession.TABLE))
        assertEquals("three_of_a_kind", Advisor.analyze(s.adviceRequest()).handKey)
        s.newHand()
        assertTrue(s.mine.isEmpty() && s.board.isEmpty())
    }

    @Test
    fun variantsAndDeckRows() {
        assertEquals(6, LiveSession.variants().size)
        val s = session()
        s.setVariant("SHORT_DECK")
        assertEquals(9, s.deckRows().first().size)
        assertEquals("SHORT_DECK", s.variant.id)
    }

    @Test
    fun drawAdvice() {
        val s = session()
        s.setVariant("FIVE_CARD_DRAW")
        s.addCards(listOf("AS", "AD", "7C", "4H", "2S"), LiveSession.MINE)
        val a = Advisor.analyze(s.adviceRequest())
        assertNotNull(a.drawKeep)
        assertEquals(setOf("As", "Ad"), a.drawKeep!!.map { it.code }.toSet())
    }

    @Test
    fun emptyScanReadingHasNoCards() {
        val scan = PhotoScan(2048, 1536, emptyList())
        assertTrue(scan.regions().size > 1)
        assertEquals(640, scan.letterbox(0).size)
        val reading = scan.finish(null, 0)
        assertTrue(reading.cards.isEmpty())
        val s = session()
        s.applyReading(LiveSession.MINE, reading, 2048, 1536)
        assertTrue(s.hasResult)
        s.closeResult()
        assertTrue(!s.hasResult)
    }
}
