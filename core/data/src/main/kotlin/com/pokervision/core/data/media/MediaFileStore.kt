package com.pokervision.core.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.pokervision.core.media.BitmapOps
import com.pokervision.core.media.VideoThumbnailer
import com.pokervision.core.model.media.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class MediaMetadata(val width: Int?, val height: Int?, val durationMillis: Long?)

/**
 * Owns the app-private media directory (`filesDir/media`). Files here are not visible to other
 * apps and are removed when the app is uninstalled. All functions perform blocking I/O and must be
 * called from a background dispatcher.
 */
@Singleton
class MediaFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val thumbnailer: VideoThumbnailer,
) {
    private val root: File get() = File(context.filesDir, ROOT_DIR)

    private fun dirFor(type: MediaType): File =
        File(root, if (type == MediaType.IMAGE) "images" else "videos").apply { mkdirs() }

    fun newFile(type: MediaType, extension: String): File {
        val prefix = if (type == MediaType.IMAGE) "IMG" else "VID"
        val name = "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension"
        return File(dirFor(type), name)
    }

    /** Copies the content at [uri] into [target]. Deletes [target] on failure. */
    @Throws(IOException::class)
    fun copyFromUri(uri: Uri, target: File) {
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open input stream for $uri")
            input.use { inStream -> target.outputStream().use { out -> inStream.copyTo(out) } }
        } catch (e: Exception) {
            target.delete()
            throw if (e is IOException) e else IOException("Copy failed for $uri", e)
        }
    }

    fun mimeTypeOf(uri: Uri): String? = context.contentResolver.getType(uri)

    fun displayNameOf(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun readMetadata(file: File, type: MediaType): MediaMetadata = when (type) {
        MediaType.IMAGE -> readImageMetadata(file)
        MediaType.VIDEO -> readVideoMetadata(file)
    }

    /** Dimensions as displayed: EXIF orientation applied (a portrait photo reports portrait size). */
    private fun readImageMetadata(file: File): MediaMetadata {
        val info = BitmapOps.readImageInfo(file.absolutePath)
        return MediaMetadata(width = info?.size?.width, height = info?.size?.height, durationMillis = null)
    }

    /** Writes a preview for a video into `media/thumbs`; returns its path or null on failure. */
    fun createVideoThumbnail(video: File): String? {
        val dir = File(root, "thumbs").apply { mkdirs() }
        val target = File(dir, video.nameWithoutExtension + ".jpg")
        return if (thumbnailer.create(video.absolutePath, target.absolutePath)) target.absolutePath else null
    }

    private fun readVideoMetadata(file: File): MediaMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val rotated = rotation == 90 || rotation == 270
            MediaMetadata(
                width = if (rotated) h else w,
                height = if (rotated) w else h,
                durationMillis = duration,
            )
        } catch (e: RuntimeException) {
            // Corrupt or unsupported container: keep the file, metadata unknown.
            MediaMetadata(null, null, null)
        } finally {
            retriever.release()
        }
    }

    /** True only for files inside the app media directory; guards deletes against arbitrary paths. */
    fun isManaged(path: String): Boolean {
        val rootPath = root.canonicalPath + File.separator
        return File(path).canonicalPath.startsWith(rootPath)
    }

    fun delete(path: String): Boolean = isManaged(path) && File(path).let { !it.exists() || it.delete() }

    fun deleteAll(): Boolean {
        // Keyframe images cached by app version 0.2 (video analysis was removed in 0.3).
        File(context.cacheDir, LEGACY_KEYFRAME_CACHE).deleteRecursively()
        return !root.exists() || root.deleteRecursively()
    }

    fun usageBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val ROOT_DIR = "media"
        const val LEGACY_KEYFRAME_CACHE = "keyframes"
    }
}
