package com.pokervision.core.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.pokervision.core.domain.media.DecodedFrame
import com.pokervision.core.domain.media.LumaSignature
import com.pokervision.core.domain.media.PixelSize
import com.pokervision.core.domain.media.VideoFrameReader
import com.pokervision.core.domain.media.VideoFrameSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * Decodes frames with [MediaMetadataRetriever] using OPTION_CLOSEST (exact frame, slower than
 * sync-frame seeking but avoids returning the same keyframe for several nearby timestamps).
 * Returned frames already have the video's rotation applied by the platform.
 */
class AndroidVideoFrameReader @Inject constructor() : VideoFrameReader {

    override suspend fun open(path: String): VideoFrameSession = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (duration == null || duration <= 0 || !hasVideo) {
                throw IOException("Not a playable video: $path")
            }
            Session(retriever, duration)
        } catch (e: IOException) {
            retriever.release()
            throw e
        } catch (e: RuntimeException) {
            // setDataSource throws IllegalArgumentException/RuntimeException for unreadable files.
            retriever.release()
            throw IOException("Cannot open video: $path", e)
        }
    }

    private class Session(
        private val retriever: MediaMetadataRetriever,
        override val durationMs: Long,
    ) : VideoFrameSession {

        override suspend fun frameAt(timestampMs: Long, maxDimension: Int): DecodedFrame? = withContext(Dispatchers.IO) {
            val timeUs = timestampMs * 1_000
            val bitmap: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    // Fits inside maxDimension x maxDimension preserving aspect ratio.
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, maxDimension, maxDimension)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }
            } catch (e: RuntimeException) {
                null
            }
            bitmap?.let { BitmapFrame(timestampMs, BitmapOps.scaleToFit(it, maxDimension)) }
        }

        override fun close() {
            retriever.release()
        }
    }
}

internal class BitmapFrame(override val timestampMs: Long, private val bitmap: Bitmap) : DecodedFrame {
    override val size: PixelSize get() = PixelSize(bitmap.width, bitmap.height)
    override fun signature(signatureSize: Int): LumaSignature = BitmapOps.lumaSignature(bitmap, signatureSize)
    override fun saveJpeg(path: String, quality: Int): Boolean = BitmapOps.saveJpeg(bitmap, path, quality)
    override fun release() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
