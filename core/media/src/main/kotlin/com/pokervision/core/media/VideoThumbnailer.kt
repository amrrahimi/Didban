package com.pokervision.core.media

import android.media.MediaMetadataRetriever
import android.os.Build
import javax.inject.Inject

/** Creates small JPEG previews for the video list. Blocking; call from a background dispatcher. */
class VideoThumbnailer @Inject constructor() {

    /** Returns true if a thumbnail was written to [targetPath]. */
    fun create(videoPath: String, targetPath: String, maxDimension: Int = DEFAULT_MAX_DIMENSION): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // A moment in, not frame 0, which is often black.
            val timeUs = minOf(PREFERRED_OFFSET_MS, duration / 3) * 1_000
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxDimension, maxDimension)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: return false
            val scaled = BitmapOps.scaleToFit(frame, maxDimension)
            try {
                BitmapOps.saveJpeg(scaled, targetPath, JPEG_QUALITY)
            } finally {
                scaled.recycle()
            }
        } catch (e: RuntimeException) {
            false
        } finally {
            retriever.release()
        }
    }

    companion object {
        const val DEFAULT_MAX_DIMENSION = 320
        private const val PREFERRED_OFFSET_MS = 1_000L
        private const val JPEG_QUALITY = 80
    }
}
