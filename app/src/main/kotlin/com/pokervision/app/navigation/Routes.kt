package com.pokervision.app.navigation

import com.pokervision.feature.home.HomeEntry

/** All navigation routes in one place. Argument names must match the feature ViewModels' SavedStateHandle keys. */
object Routes {
    const val HOME = "home"
    const val CAMERA = "camera"
    const val IMAGES = "images"
    const val ADVISOR = "advisor"
    const val HISTORY = "history"
    const val STATISTICS = "statistics"
    const val VARIANT = "settings/variant"
    const val DETECTION = "settings/detection"
    const val PRIVACY = "settings/privacy"

    const val MEDIA_ID_ARG = "mediaId"
    const val IMAGE_DETAIL = "images/{$MEDIA_ID_ARG}"

    fun imageDetail(id: Long) = "images/$id"

    fun forHomeEntry(entry: HomeEntry): String = when (entry) {
        HomeEntry.LIVE_CAMERA -> CAMERA
        HomeEntry.IMAGE_ANALYSIS -> IMAGES
        HomeEntry.ADVISOR -> ADVISOR
        HomeEntry.HISTORY -> HISTORY
        HomeEntry.STATISTICS -> STATISTICS
        HomeEntry.VARIANT_SELECTION -> VARIANT
        HomeEntry.DETECTION_SETTINGS -> DETECTION
        HomeEntry.PRIVACY_SETTINGS -> PRIVACY
    }
}
