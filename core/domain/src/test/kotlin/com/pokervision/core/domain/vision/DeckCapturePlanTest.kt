package com.pokervision.core.domain.vision

import com.pokervision.core.model.card.PlayingCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeckCapturePlanTest {

    @Test
    fun `plan covers 52 cards plus background`() {
        assertEquals(53, DeckCapturePlan.targets.size)
        assertEquals(53, DeckCapturePlan.targets.map { it.datasetName }.toSet().size)
        assertEquals(CaptureTarget.Background, DeckCapturePlan.targets.last())
    }

    @Test
    fun `dataset codes match the detection model labels`() {
        val codes = DeckCapturePlan.targets.filterIsInstance<CaptureTarget.Card>().map { it.datasetName }.toSet()
        assertEquals(CardLabels.DEFAULT_ORDER.toSet(), codes)
        assertEquals("10H", DeckCapturePlan.datasetCode(PlayingCard.parse("Th")))
        assertEquals("AS", DeckCapturePlan.datasetCode(PlayingCard.parse("As")))
    }

    @Test
    fun `next incomplete skips finished targets and wraps`() {
        val first = DeckCapturePlan.targets[0].datasetName
        val second = DeckCapturePlan.targets[1].datasetName
        val full = DeckCapturePlan.SHOTS_PER_TARGET
        assertEquals(0, DeckCapturePlan.nextIncomplete(emptyMap()))
        assertEquals(1, DeckCapturePlan.nextIncomplete(mapOf(first to full)))
        assertEquals(52, DeckCapturePlan.nextIncomplete(mapOf(first to full), from = 52)) // background itself
        assertEquals(1, DeckCapturePlan.nextIncomplete(mapOf(first to full, "background" to full), from = 52)) // wraps
        val all = DeckCapturePlan.targets.associate { it.datasetName to full }
        assertNull(DeckCapturePlan.nextIncomplete(all))
        assertEquals(53, DeckCapturePlan.completedTargets(all))
        assertEquals(1, DeckCapturePlan.completedTargets(mapOf(second to full)))
    }
}
