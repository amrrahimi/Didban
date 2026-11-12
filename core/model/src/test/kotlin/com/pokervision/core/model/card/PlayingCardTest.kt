package com.pokervision.core.model.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayingCardTest {

    @Test
    fun `parse valid codes`() {
        assertEquals(PlayingCard(Rank.ACE, Suit.SPADES), PlayingCard.parse("As"))
        assertEquals(PlayingCard(Rank.TEN, Suit.DIAMONDS), PlayingCard.parse("Td"))
        assertEquals(PlayingCard(Rank.TEN, Suit.HEARTS), PlayingCard.parse("10h"))
        assertEquals(PlayingCard(Rank.SEVEN, Suit.CLUBS), PlayingCard.parse(" 7C "))
        assertEquals(PlayingCard(Rank.KING, Suit.HEARTS), PlayingCard.parse("kh"))
    }

    @Test
    fun `parse rejects invalid codes`() {
        listOf("", "A", "Ax", "1s", "AsK", "11h", "ZZ").forEach {
            assertNull("'$it' should be invalid", PlayingCard.parseOrNull(it))
        }
    }

    @Test
    fun `code round trips for all 52 cards`() {
        DeckType.STANDARD_52.cards.forEach { assertEquals(it, PlayingCard.parse(it.code)) }
    }

    @Test
    fun `indices are unique and cover 0 to 51`() {
        val indices = DeckType.STANDARD_52.cards.map { it.index }
        assertEquals((0..51).toList(), indices.sorted())
        indices.forEach { assertEquals(it, PlayingCard.fromIndex(it).index) }
    }

    @Test
    fun `deck sizes`() {
        assertEquals(52, DeckType.STANDARD_52.size)
        assertEquals(52, DeckType.STANDARD_52.cards.toSet().size)
        assertEquals(36, DeckType.SHORT_DECK_36.size)
        assertFalse(DeckType.SHORT_DECK_36.contains(PlayingCard.parse("5h")))
        assertTrue(DeckType.SHORT_DECK_36.contains(PlayingCard.parse("6h")))
    }

    @Test
    fun `red suits`() {
        assertTrue(Suit.HEARTS.isRed)
        assertTrue(Suit.DIAMONDS.isRed)
        assertFalse(Suit.SPADES.isRed)
        assertFalse(Suit.CLUBS.isRed)
    }

    @Test
    fun `codec round trip and malformed input`() {
        val cards = listOf("As", "Kd", "7h", "Tc").map(PlayingCard::parse)
        assertEquals("AsKd7hTc", CardCodec.encode(cards))
        assertEquals(cards, CardCodec.decodeOrNull("AsKd7hTc"))
        assertEquals(emptyList<PlayingCard>(), CardCodec.decodeOrNull(""))
        assertNull(CardCodec.decodeOrNull("AsK"))
        assertNull(CardCodec.decodeOrNull("AsKx"))
    }
}
