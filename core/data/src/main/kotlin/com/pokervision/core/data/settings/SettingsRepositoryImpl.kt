package com.pokervision.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pokervision.core.common.logging.AppLogger
import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.model.confidence.ConfidenceThresholds
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val logger: AppLogger,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                logger.e(TAG, "Settings read failed; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { p ->
            SettingsDecoder.decode(
                variantName = p[Keys.VARIANT],
                analysisFps = p[Keys.FPS],
                minimumDetection = p[Keys.MIN_DETECTION],
                autoConfirm = p[Keys.AUTO_CONFIRM],
            )
        }

    override suspend fun setDefaultVariant(variant: PokerVariant) {
        dataStore.edit { it[Keys.VARIANT] = variant.name }
    }

    override suspend fun setAnalysisFps(fps: Int) {
        require(fps in AppSettings.ANALYSIS_FPS_RANGE) { "fps out of range: $fps" }
        dataStore.edit { it[Keys.FPS] = fps }
    }

    override suspend fun setConfidenceThresholds(minimumDetection: Float, autoConfirm: Float) {
        ConfidenceThresholds(minimumDetection, autoConfirm) // validates, throws on invalid input
        dataStore.edit {
            it[Keys.MIN_DETECTION] = minimumDetection
            it[Keys.AUTO_CONFIRM] = autoConfirm
        }
    }

    override suspend fun resetToDefaults() {
        dataStore.edit { it.clear() }
    }

    private object Keys {
        val VARIANT = stringPreferencesKey("default_variant")
        val FPS = intPreferencesKey("analysis_fps")
        val MIN_DETECTION = floatPreferencesKey("min_detection_confidence")
        val AUTO_CONFIRM = floatPreferencesKey("auto_confirm_confidence")
    }

    private companion object {
        const val TAG = "SettingsRepository"
    }
}
