package com.pokervision.core.model.validation

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.game.BettingStreet
import com.pokervision.core.model.game.CardLocation
import com.pokervision.core.model.game.CardSlot
import com.pokervision.core.model.game.GameState

enum class Severity {
    /** State is impossible; calculations must not run. */
    ERROR,

    /** State is possible but incomplete or needs user review. */
    WARNING,
}

sealed interface ValidationIssue {
    val severity: Severity

    data class DuplicateCard(val card: PlayingCard, val locations: List<CardLocation>) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    data class CardNotInDeck(val card: PlayingCard, val location: CardLocation) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    data class TooManyCommunityCards(val count: Int, val max: Int) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    data class CommunityCardsOnNonBoardVariant(val count: Int) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    /** Board has more known cards than the current street allows (e.g. 4 cards on the flop). */
    data class BoardAheadOfStreet(val street: BettingStreet, val boardCount: Int) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    /** Street implies more board cards than are filled in (some may be unknown or uncertain). */
    data class BoardBehindStreet(val street: BettingStreet, val boardCount: Int) : ValidationIssue {
        override val severity = Severity.WARNING
    }

    data class StreetNotInVariant(val street: BettingStreet) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    data class TooManyPlayerCards(val playerId: String, val count: Int, val max: Int) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    data class PlayerCountOutOfRange(val count: Int, val min: Int, val max: Int) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    data class PlayersExceedSeats(val count: Int, val seats: Int) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    data class DuplicateSeat(val seatIndex: Int, val playerIds: List<String>) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    data class SeatOutOfRange(val playerId: String, val seatIndex: Int, val seats: Int) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    data class DuplicatePlayerId(val playerId: String) : ValidationIssue {
        override val severity = Severity.ERROR
    }

    /** Uncertain detections exist and need confirmation or correction by the user. */
    data class UnconfirmedCards(val count: Int) : ValidationIssue {
        override val severity = Severity.WARNING
    }
}

data class ValidationReport(val issues: List<ValidationIssue>) {
    val isValid: Boolean get() = issues.none { it.severity == Severity.ERROR }
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == Severity.WARNING }
}

/**
 * Checks structural consistency of a [GameState]. Only confirmed cards participate in uniqueness and
 * deck checks; uncertain cards are reported as a warning so the UI can request manual confirmation.
 * Pure and deterministic.
 */
object GameStateValidator {

    fun validate(state: GameState): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        val variant = state.variant

        // Players
        val playerCount = state.players.size
        if (playerCount > 0 && playerCount !in variant.minPlayers..variant.maxPlayers) {
            issues += ValidationIssue.PlayerCountOutOfRange(playerCount, variant.minPlayers, variant.maxPlayers)
        }
        if (playerCount > state.table.seatCount) {
            issues += ValidationIssue.PlayersExceedSeats(playerCount, state.table.seatCount)
        }
        state.players.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
            issues += ValidationIssue.DuplicatePlayerId(it)
        }
        state.players.groupBy { it.seatIndex }.filterValues { it.size > 1 }.forEach { (seat, ps) ->
            issues += ValidationIssue.DuplicateSeat(seat, ps.map { it.id })
        }
        state.players.filter { it.seatIndex !in 0 until state.table.seatCount }.forEach {
            issues += ValidationIssue.SeatOutOfRange(it.id, it.seatIndex, state.table.seatCount)
        }
        state.players.filter { it.cards.size > variant.cardsPerPlayer }.forEach {
            issues += ValidationIssue.TooManyPlayerCards(it.id, it.cards.size, variant.cardsPerPlayer)
        }

        // Board
        val boardKnown = state.communityCards.count { it !is CardSlot.Unknown }
        if (!variant.usesCommunityCards) {
            if (boardKnown > 0) issues += ValidationIssue.CommunityCardsOnNonBoardVariant(boardKnown)
        } else if (state.communityCards.size > variant.communityCards) {
            issues += ValidationIssue.TooManyCommunityCards(state.communityCards.size, variant.communityCards)
        }

        // Street vs board
        state.street?.value?.let { street ->
            if (street !in variant.streets) {
                issues += ValidationIssue.StreetNotInVariant(street)
            } else if (variant.usesCommunityCards && street != BettingStreet.SHOWDOWN) {
                val expected = street.visibleCommunityCards
                when {
                    boardKnown > expected -> issues += ValidationIssue.BoardAheadOfStreet(street, boardKnown)
                    boardKnown < expected -> issues += ValidationIssue.BoardBehindStreet(street, boardKnown)
                }
            }
        }

        // Cards: deck membership and uniqueness (confirmed only)
        val located = state.confirmedCardLocations()
        located.filter { (card, _) -> !variant.deckType.contains(card) }.forEach { (card, loc) ->
            issues += ValidationIssue.CardNotInDeck(card, loc)
        }
        located.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }.forEach { (card, locs) ->
            issues += ValidationIssue.DuplicateCard(card, locs)
        }

        val uncertain = state.communityCards.count { it is CardSlot.Uncertain } +
            state.players.sumOf { p -> p.cards.count { it is CardSlot.Uncertain } }
        if (uncertain > 0) issues += ValidationIssue.UnconfirmedCards(uncertain)

        return ValidationReport(issues)
    }
}
