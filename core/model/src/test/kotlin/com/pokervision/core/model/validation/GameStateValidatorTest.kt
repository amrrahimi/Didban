package com.pokervision.core.model.validation

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.confidence.Confidence
import com.pokervision.core.model.confidence.ObservationSource
import com.pokervision.core.model.confidence.Observed
import com.pokervision.core.model.game.BettingStreet
import com.pokervision.core.model.game.CardCandidate
import com.pokervision.core.model.game.CardSlot
import com.pokervision.core.model.game.GameState
import com.pokervision.core.model.game.Player
import com.pokervision.core.model.game.PokerTable
import com.pokervision.core.model.game.PokerVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateValidatorTest {

    private fun c(code: String): CardSlot = CardSlot.Confirmed(PlayingCard.parse(code), ObservationSource.MANUAL)

    private fun uncertain(code: String, conf: Float = 0.6f): CardSlot =
        CardSlot.Uncertain(listOf(CardCandidate(PlayingCard.parse(code), Confidence(conf))))

    private fun state(
        variant: PokerVariant = PokerVariant.TEXAS_HOLDEM,
        street: BettingStreet? = null,
        board: List<CardSlot> = emptyList(),
        players: List<Player> = listOf(Player("p1", 0), Player("p2", 1)),
        seats: Int = 6,
    ) = GameState(
        gameId = "g",
        handNumber = 1,
        variant = variant,
        table = PokerTable(seats),
        street = street?.let { Observed.manual(it) },
        players = players,
        communityCards = board,
        updatedAtMillis = 0,
    )

    private inline fun <reified T : ValidationIssue> ValidationReport.has(): Boolean = issues.any { it is T }

    @Test
    fun `valid flop state has no issues`() {
        val report = GameStateValidator.validate(
            state(
                street = BettingStreet.FLOP,
                board = listOf(c("As"), c("Kd"), c("7h")),
                players = listOf(Player("p1", 0, cards = listOf(c("Qc"), c("Qd"))), Player("p2", 1)),
            ),
        )
        assertTrue(report.issues.toString(), report.issues.isEmpty())
        assertTrue(report.isValid)
    }

    @Test
    fun `duplicate card across board and hand is an error`() {
        val report = GameStateValidator.validate(
            state(
                board = listOf(c("As"), c("Kd"), c("7h")),
                players = listOf(Player("p1", 0, cards = listOf(c("As"), c("2c"))), Player("p2", 1)),
            ),
        )
        assertFalse(report.isValid)
        val dup = report.issues.filterIsInstance<ValidationIssue.DuplicateCard>().single()
        assertEquals(PlayingCard.parse("As"), dup.card)
        assertEquals(2, dup.locations.size)
    }

    @Test
    fun `uncertain duplicate is not an error but needs confirmation`() {
        val report = GameStateValidator.validate(
            state(
                board = listOf(c("As"), uncertain("As"), c("7h")),
            ),
        )
        assertTrue(report.isValid)
        assertEquals(1, report.issues.filterIsInstance<ValidationIssue.UnconfirmedCards>().single().count)
    }

    @Test
    fun `board ahead of street is an error`() {
        val report = GameStateValidator.validate(
            state(street = BettingStreet.FLOP, board = listOf(c("As"), c("Kd"), c("7h"), c("2c"))),
        )
        assertTrue(report.has<ValidationIssue.BoardAheadOfStreet>())
        assertFalse(report.isValid)
    }

    @Test
    fun `board behind street is a warning only`() {
        val report = GameStateValidator.validate(
            state(street = BettingStreet.RIVER, board = listOf(c("As"), c("Kd"), c("7h"), CardSlot.Unknown)),
        )
        assertTrue(report.has<ValidationIssue.BoardBehindStreet>())
        assertTrue(report.isValid)
    }

    @Test
    fun `more than five board slots is an error`() {
        val board = listOf("As", "Kd", "7h", "2c", "3c", "4c").map(::c)
        assertTrue(GameStateValidator.validate(state(board = board)).has<ValidationIssue.TooManyCommunityCards>())
    }

    @Test
    fun `stud has no community cards`() {
        val report = GameStateValidator.validate(state(variant = PokerVariant.SEVEN_CARD_STUD, board = listOf(c("As"))))
        assertTrue(report.has<ValidationIssue.CommunityCardsOnNonBoardVariant>())
    }

    @Test
    fun `street from another variant is rejected`() {
        val report = GameStateValidator.validate(state(variant = PokerVariant.TEXAS_HOLDEM, street = BettingStreet.THIRD_STREET))
        assertTrue(report.has<ValidationIssue.StreetNotInVariant>())
    }

    @Test
    fun `holdem player with three cards is an error, omaha with four is fine`() {
        val four = listOf("As", "Ks", "Qs", "Js").map(::c)
        val holdem = GameStateValidator.validate(state(players = listOf(Player("p1", 0, cards = four.take(3)), Player("p2", 1))))
        assertTrue(holdem.has<ValidationIssue.TooManyPlayerCards>())
        val omaha = GameStateValidator.validate(
            state(variant = PokerVariant.OMAHA, players = listOf(Player("p1", 0, cards = four), Player("p2", 1))),
        )
        assertTrue(omaha.issues.toString(), omaha.isValid)
    }

    @Test
    fun `short deck rejects low cards`() {
        val report = GameStateValidator.validate(
            state(variant = PokerVariant.SHORT_DECK, board = listOf(c("As"), c("5d"), c("7h"))),
        )
        assertEquals(PlayingCard.parse("5d"), report.issues.filterIsInstance<ValidationIssue.CardNotInDeck>().single().card)
    }

    @Test
    fun `seat problems are detected`() {
        val report = GameStateValidator.validate(
            state(players = listOf(Player("p1", 0), Player("p2", 0), Player("p3", 9)), seats = 6),
        )
        assertTrue(report.has<ValidationIssue.DuplicateSeat>())
        assertTrue(report.has<ValidationIssue.SeatOutOfRange>())
    }

    @Test
    fun `player count limits per variant`() {
        val players = (0 until 7).map { Player("p$it", it) }
        val draw = GameStateValidator.validate(state(variant = PokerVariant.FIVE_CARD_DRAW, players = players, seats = 9))
        assertTrue(draw.has<ValidationIssue.PlayerCountOutOfRange>())
        val single = GameStateValidator.validate(state(players = listOf(Player("p1", 0))))
        assertTrue(single.has<ValidationIssue.PlayerCountOutOfRange>())
    }

    @Test
    fun `duplicate player ids are detected`() {
        val report = GameStateValidator.validate(state(players = listOf(Player("p1", 0), Player("p1", 1))))
        assertTrue(report.has<ValidationIssue.DuplicatePlayerId>())
    }
}
