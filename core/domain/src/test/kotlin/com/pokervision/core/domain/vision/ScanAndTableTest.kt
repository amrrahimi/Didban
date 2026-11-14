package com.pokervision.core.domain.vision

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.detection.BoundingBox
import com.pokervision.core.model.game.PokerVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAndTableTest {

    private fun cards(s: String) = if (s.isBlank()) emptyList() else s.split(" ").map(PlayingCard::parse)

    @Test
    fun `tile plan covers the photo with overlap`() {
        val landscape = TilePlan.tiles(2048, 1536)
        assertEquals(6, landscape.size)
        assertEquals(listOf(0, 512, 1024), landscape.map { it.left }.distinct())
        assertEquals(listOf(0, 512), landscape.map { it.top }.distinct())
        assertTrue(landscape.all { it.left + it.width <= 2048 && it.top + it.height <= 1536 })
        assertEquals(6, TilePlan.tiles(1536, 2048).size)
        assertEquals(listOf(CropRect(0, 0, 500, 400)), TilePlan.tiles(500, 400))
    }

    private fun sighting(score: Float, pips: Int) =
        CardSighting(PlayingCard.parse("As"), score, BoundingBox(0f, 0f, 0.1f, 0.1f), pips)

    @Test
    fun `single photo acceptance`() {
        assertTrue(ScanAcceptance.isConfident(sighting(0.5f, 2)))
        assertFalse(ScanAcceptance.isConfident(sighting(0.5f, 1)))
        assertTrue(ScanAcceptance.isConfident(sighting(0.75f, 1)))
        assertFalse(ScanAcceptance.isConfident(sighting(0.4f, 2)))
    }

    @Test
    fun `holdem scans replace mine and board`() {
        var s = TableState()
        s = TableRules.applyScan(s, ScanTarget.MINE, cards("As Kd Qh")).state
        assertEquals(cards("As Kd"), s.mine) // capacity 2
        val board = TableRules.applyScan(s, ScanTarget.TABLE, cards("As 2c 7h 9s"))
        assertEquals(cards("2c 7h 9s"), board.state.board) // own card ignored on the table
        val turn = TableRules.applyScan(board.state, ScanTarget.TABLE, cards("2c 7h 9s Td"))
        assertEquals(4, turn.state.board.size)
    }

    @Test
    fun `omaha takes four cards and short deck ignores low cards`() {
        val omaha = TableRules.applyScan(TableState(PokerVariant.OMAHA), ScanTarget.MINE, cards("As Ah Ks Kh")).state
        assertEquals(4, omaha.mine.size)
        val sd = TableRules.applyScan(TableState(PokerVariant.SHORT_DECK), ScanTarget.MINE, cards("As 4d Kh"))
        assertEquals(cards("As Kh"), sd.state.mine)
        assertEquals(cards("4d"), sd.ignored)
    }

    @Test
    fun `stud table scan fills seen cards`() {
        var s = TableState(PokerVariant.SEVEN_CARD_STUD)
        s = TableRules.applyScan(s, ScanTarget.MINE, cards("As Ad 7c")).state
        s = TableRules.applyScan(s, ScanTarget.TABLE, cards("Kc 9h 2d As")).state
        assertEquals(cards("Kc 9h 2d"), s.seen)
        assertTrue(s.board.isEmpty())
        assertFalse(TableRules.tableScanAvailable(PokerVariant.FIVE_CARD_DRAW))
    }

    @Test
    fun `manual add remove and limits`() {
        var s = TableRules.add(TableState(), PlayingCard.parse("As"), ScanTarget.MINE)
        s = TableRules.add(s, PlayingCard.parse("As"), ScanTarget.TABLE) // duplicate ignored
        assertTrue(s.board.isEmpty())
        s = TableRules.remove(s, PlayingCard.parse("As"))
        assertTrue(s.mine.isEmpty())
        assertEquals(7, TableRules.setOpponents(TableState(PokerVariant.SEVEN_CARD_STUD), 20).opponents)
        assertEquals(5, TableRules.setOpponents(TableState(PokerVariant.FIVE_CARD_DRAW), 9).opponents)
    }

    @Test
    fun `variant change starts a new hand`() {
        val s = TableState(mine = cards("As Kd"), opponents = 9)
        val draw = TableRules.setVariant(s, PokerVariant.FIVE_CARD_DRAW)
        assertTrue(draw.mine.isEmpty())
        assertEquals(5, draw.opponents)
        assertEquals(s.handNumber + 1, draw.handNumber)
    }
}
