package com.pokervision.feature.settings

import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.model.confidence.ConfidenceThresholds
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.settings.AppSettings
import com.pokervision.core.model.settings.KeyframeSensitivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private class FakeRepo : SettingsRepository {
        val state = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = state
        override suspend fun setDefaultVariant(variant: PokerVariant) { state.value = state.value.copy(defaultVariant = variant) }
        override suspend fun setAnalysisFps(fps: Int) { state.value = state.value.copy(analysisFps = fps) }
        override suspend fun setConfidenceThresholds(minimumDetection: Float, autoConfirm: Float) {
            state.value = state.value.copy(confidenceThresholds = ConfidenceThresholds(minimumDetection, autoConfirm))
        }
        override suspend fun setVideoSampleInterval(intervalMs: Long) { state.value = state.value.copy(videoSampleIntervalMs = intervalMs) }
        override suspend fun setKeyframeSensitivity(sensitivity: KeyframeSensitivity) { state.value = state.value.copy(keyframeSensitivity = sensitivity) }
        override suspend fun resetToDefaults() { state.value = AppSettings() }
    }

    private val repo = FakeRepo()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `raising minimum above auto-confirm drags auto-confirm up`() {
        SettingsViewModel(repo).setThresholds(minimumDetection = 0.95f, autoConfirm = 0.9f, editedMinimum = true)
        assertEquals(ConfidenceThresholds(0.95f, 0.95f), repo.state.value.confidenceThresholds)
    }

    @Test
    fun `lowering auto-confirm below minimum drags minimum down`() {
        SettingsViewModel(repo).setThresholds(minimumDetection = 0.5f, autoConfirm = 0.3f, editedMinimum = false)
        assertEquals(ConfidenceThresholds(0.3f, 0.3f), repo.state.value.confidenceThresholds)
    }

    @Test
    fun `unsupported video interval is ignored`() {
        SettingsViewModel(repo).setVideoSampleInterval(333)
        assertEquals(AppSettings.DEFAULT_VIDEO_SAMPLE_INTERVAL_MS, repo.state.value.videoSampleIntervalMs)
    }

    @Test
    fun `fps is clamped to the allowed range`() {
        SettingsViewModel(repo).setAnalysisFps(99)
        assertEquals(AppSettings.ANALYSIS_FPS_RANGE.last, repo.state.value.analysisFps)
    }

    @Test
    fun `reset restores detection defaults`() {
        val vm = SettingsViewModel(repo)
        vm.setAnalysisFps(12)
        vm.setVideoSampleInterval(200)
        vm.setKeyframeSensitivity(KeyframeSensitivity.HIGH)
        vm.resetDetectionDefaults()
        assertEquals(AppSettings().videoSampleIntervalMs, repo.state.value.videoSampleIntervalMs)
        assertEquals(KeyframeSensitivity.MEDIUM, repo.state.value.keyframeSensitivity)
        assertEquals(AppSettings().analysisFps, repo.state.value.analysisFps)
        assertEquals(AppSettings().confidenceThresholds, repo.state.value.confidenceThresholds)
    }
}
