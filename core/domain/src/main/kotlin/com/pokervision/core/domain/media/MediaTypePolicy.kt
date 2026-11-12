package com.pokervision.core.domain.media

import com.pokervision.core.model.media.MediaType

/** Which MIME types the app accepts for each media type. Pure policy so it can be unit tested. */
object MediaTypePolicy {

    private val SUPPORTED_IMAGE = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
    private val SUPPORTED_VIDEO = setOf("video/mp4", "video/3gpp", "video/webm", "video/quicktime", "video/x-matroska")

    fun isSupported(mimeType: String?, type: MediaType): Boolean {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return false
        return when (type) {
            MediaType.IMAGE -> mime in SUPPORTED_IMAGE
            MediaType.VIDEO -> mime in SUPPORTED_VIDEO
        }
    }

    /** File extension used when copying into private storage. */
    fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "video/mp4" -> "mp4"
        "video/3gpp" -> "3gp"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        else -> "bin"
    }

    fun mimeTypeForExtension(extension: String): String? = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "mp4" -> "video/mp4"
        "3gp" -> "video/3gpp"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        else -> null
    }
}
