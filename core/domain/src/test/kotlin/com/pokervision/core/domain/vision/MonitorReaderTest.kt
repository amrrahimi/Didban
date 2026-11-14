package com.pokervision.core.domain.vision

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.detection.BoundingBox
import com.pokervision.core.model.game.PokerVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorReaderTest {

    private fun c(s: String) = PlayingCard.parse(s)
    private fun s(code: String, x: Float = 0.1f, score: Float = 0.8f, pips: Int = 2) =
        CardSighting(c(code), score, BoundingBox(x, 0.1f, x + 0.05f, 0.2f), pips)

    @Test
    fun `cards appear after confirmation and are ordered left to right`() {
        val r = MonitorReader()
        val mine = listOf(s("Kh", x = 0.5f), s("As", x = 0.4f))
        assertTrue(r.update(mine, emptyList()).mine.isEmpty()) // weight 2 < 3
        val reading = r.update(mine, emptyList())
        assertEquals(listOf(c("As"), c("Kh")), reading.mine)
    }

    @Test
    fun `board fills as cards are dealt`() {
        val r = MonitorReader()
        val mine = listOf(s("As"), s("Kh"))
        repeat(2) { r.update(mine, listOf(s("2c", 0.1f), s("7d", 0.2f), s("9s", 0.3f))) }
        assertEquals(3, r.update(mine, listOf(s("2c", 0.1f), s("7d", 0.2f), s("9s", 0.3f))).board.size)
        repeat(2) { r.update(mine, listOf(s("2c", 0.1f), s("7d", 0.2f), s("9s", 0.3f), s("Jh", 0.4f))) }
        assertEquals(listOf(c("2c"), c("7d"), c("9s"), c("Jh")), r.update(mine, listOf(s("2c", 0.1f), s("7d", 0.2f), s("9s", 0.3f), s("Jh", 0.4f))).board)
    }

    @Test
    fun `next hand replaces old cards without any button`() {
        val r = MonitorReader()
        repeat(3) { r.update(listOf(s("As"), s("Kh")), listOf(s("2c"))) }
        var reading = r.update(listOf(s("Qd"), s("Qc")), emptyList())
        repeat(5) { reading = r.update(listOf(s("Qd"), s("Qc")), emptyList()) }
        assertEquals(setOf(c("Qd"), c("Qc")), reading.mine.toSet())
        assertTrue(reading.board.isEmpty())
    }

    @Test
    fun `one missed frame does not drop a confirmed card`() {
        val r = MonitorReader()
        repeat(3) { r.update(listOf(s("As")), emptyList()) }
        assertEquals(listOf(c("As")), r.update(emptyList(), emptyList()).mine)
    }

    @Test
    fun `card seen in both areas is kept where it is stronger`() {
        val r = MonitorReader()
        var reading = r.update(listOf(s("As", score = 0.9f)), listOf(s("As", score = 0.6f)))
        reading = r.update(listOf(s("As", score = 0.9f)), listOf(s("As", score = 0.6f)))
        assertEquals(listOf(c("As")), reading.mine)
        assertTrue(reading.board.isEmpty())
    }

    @Test
    fun `reading maps onto a table state with variant capacities`() {
        val holdem = TableState(variant = PokerVariant.TEXAS_HOLDEM)
        val reading = MonitorReading(listOf(c("As"), c("Kh"), c("Qd")), listOf(c("2c"), c("3c"), c("4c")), emptySet())
        val withMine = TableRules.applyScan(holdem, ScanTarget.MINE, reading.mine).state
        val state = TableRules.applyScan(withMine, ScanTarget.TABLE, reading.board).state
        assertEquals(2, state.mine.size) // Hold'em keeps two hole cards
        assertEquals(3, state.board.size)
    }
}
