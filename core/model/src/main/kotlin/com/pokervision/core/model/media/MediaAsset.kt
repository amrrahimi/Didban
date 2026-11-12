package com.pokervision.core.model.media

enum class MediaType { IMAGE, VIDEO }

enum class MediaOrigin { CAMERA_PHOTO, CAMERA_VIDEO, USER_IMPORT }

/** An image or video stored in the app's private storage. */
data class MediaAsset(
    val id: Long = 0,
    val type: MediaType,
    val origin: MediaOrigin,
    /** Absolute path inside app-private storage. */
    val localPath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    /** Videos only. */
    val durationMillis: Long? = null,
    val createdAtMillis: Long,
)
