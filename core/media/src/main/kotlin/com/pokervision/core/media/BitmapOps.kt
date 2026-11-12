package com.pokervision.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.pokervision.core.domain.media.LumaSignature
import com.pokervision.core.domain.media.PixelSize
import com.pokervision.core.domain.media.ResizeMath
import java.io.File
import java.io.IOException

/** Size as displayed (EXIF rotation applied) and the rotation that was found. */
data class ImageInfo(val size: PixelSize, val rotationDegrees: Int)

/**
 * Bitmap utilities shared by thumbnails, keyframe extraction and (phase 3) model input preparation.
 * All functions are blocking; call from a background dispatcher.
 */
object BitmapOps {

    /** Reads dimensions and EXIF orientation without decoding pixels. Null if not a decodable image. */
    fun readImageInfo(path: String): ImageInfo? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val rotation = exifRotation(path)
        return ImageInfo(PixelSize(bounds.outWidth, bounds.outHeight).rotated(rotation), rotation)
    }

    fun exifRotation(path: String): Int = try {
        ResizeMath.normalizeRotation(ExifInterface(path).rotationDegrees)
    } catch (e: IOException) {
        0
    } catch (e: IllegalArgumentException) {
        0
    }

    /**
     * Decodes an image file upright (EXIF applied) so its longer side is at most [maxDimension].
     * Uses power-of-two subsampling first to avoid allocating the full-resolution bitmap.
     */
    fun decodeOriented(path: String, maxDimension: Int): Bitmap? {
        val info = readImageInfo(path) ?: return null
        val stored = info.size.rotated(info.rotationDegrees) // undo: rotation by 90/270 swaps back
        val options = BitmapFactory.Options().apply {
            inSampleSize = ResizeMath.inSampleSize(stored, maxDimension)
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        return scaleToFit(rotate(decoded, info.rotationDegrees), maxDimension)
    }

    /** Returns a bitmap no larger than [maxDimension] on either side. Recycles the input if a copy was made. */
    fun scaleToFit(source: Bitmap, maxDimension: Int): Bitmap {
        val target = ResizeMath.fitWithin(PixelSize(source.width, source.height), maxDimension, maxDimension)
        if (target.width == source.width && target.height == source.height) return source
        val scaled = Bitmap.createScaledBitmap(source, target.width, target.height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    /** Rotates clockwise by [degrees] (multiple of 90). Recycles the input if a copy was made. */
    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val d = ResizeMath.normalizeRotation(degrees)
        if (d == 0) return source
        val matrix = Matrix().apply { postRotate(d.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    /** Downscales to [size]×[size] and converts to luminance (ITU-R BT.601 weights). */
    fun lumaSignature(source: Bitmap, size: Int): LumaSignature {
        val small = Bitmap.createScaledBitmap(source, size, size, true)
        val pixels = IntArray(size * size)
        small.getPixels(pixels, 0, size, 0, 0, size, size)
        if (small !== source) small.recycle()
        val luma = IntArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (299 * r + 587 * g + 114 * b) / 1000
        }
        return LumaSignature(size, luma)
    }

    /** Writes JPEG atomically (temp file + rename) so a crash never leaves a truncated image. */
    fun saveJpeg(bitmap: Bitmap, path: String, quality: Int): Boolean {
        require(quality in 0..100) { "quality must be in 0..100" }
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        return try {
            val ok = tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            ok && (target.delete() || !target.exists()) && tmp.renameTo(target)
        } catch (e: IOException) {
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
