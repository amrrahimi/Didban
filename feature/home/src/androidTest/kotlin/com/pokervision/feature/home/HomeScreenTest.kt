package com.pokervision.feature.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pokervision.core.domain.usecase.DashboardSummary
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.ui.theme.PokerVisionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun showsCountsAndNavigatesFromTiles() {
        var opened: HomeEntry? = null
        val summary = DashboardSummary(3, 2, 0, 0, PokerVariant.TEXAS_HOLDEM, emptyList())
        compose.setContent {
            PokerVisionTheme { HomeScreen(state = HomeUiState.Ready(summary), onOpen = { opened = it }) }
        }
        compose.onNodeWithText("3").assertExists()

        val cameraTitle = context.getString(R.string.home_tile_camera)
        compose.onNodeWithText(cameraTitle).performClick()
        assertEquals(HomeEntry.LIVE_CAMERA, opened)

        val privacyTitle = context.getString(R.string.home_tile_privacy)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(privacyTitle))
        compose.onNodeWithText(privacyTitle).performClick()
        assertEquals(HomeEntry.PRIVACY_SETTINGS, opened)
    }
}
