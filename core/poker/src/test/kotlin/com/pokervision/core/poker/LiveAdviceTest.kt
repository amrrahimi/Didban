package com.pokervision.core.poker

import com.pokervision.core.poker.advice.LiveAction
import com.pokervision.core.poker.advice.LiveAdvisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveAdviceTest {

    @Test
    fun `max callable bet fraction`() {
        assertEquals(0.5, LiveAdvisor.maxCallBetFraction(0.25)!!, 1e-12)  // half-pot bet needs 25%
        assertEquals(1.0, LiveAdvisor.maxCallBetFraction(1.0 / 3)!!, 1e-12) // pot-size bet needs 33.3%
        assertEquals(0.0, LiveAdvisor.maxCallBetFraction(0.0)!!, 1e-12)
        assertNull(LiveAdvisor.maxCallBetFraction(0.5))
        assertNull(LiveAdvisor.maxCallBetFraction(0.9))
    }

    @Test
    fun `live actions`() {
        assertEquals(LiveAction.BET_OR_RAISE, LiveAdvisor.advise(0.8, opponents = 1).action)
        assertEquals(LiveAction.CHECK_OR_CALL, LiveAdvisor.advise(0.4, opponents = 1).action)
        assertEquals(LiveAction.CHECK_OR_FOLD, LiveAdvisor.advise(0.15, opponents = 1).action)
        // Six-handed, 50% equity is strong (bet threshold 0.5)
        assertEquals(LiveAction.BET_OR_RAISE, LiveAdvisor.advise(0.5, opponents = 5).action)
    }
}
