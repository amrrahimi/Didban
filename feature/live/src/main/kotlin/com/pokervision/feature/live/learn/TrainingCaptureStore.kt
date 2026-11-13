package com.pokervision.feature.live.learn

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.pokervision.core.media.BitmapOps
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photos taken in "learn my cards" mode, kept in private storage under `files/training/<target>/`.
 * They leave the phone only when the user exports them through the share sheet.
 * Blocking I/O: call off the main thread.
 */
@Singleton
class TrainingCaptureStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File get() = File(context.filesDir, DIR)

    fun counts(): Map<String, Int> =
        root.listFiles { f -> f.isDirectory }.orEmpty().associate { dir ->
            dir.name to dir.listFiles { f -> f.isFile && f.extension == "jpg" }.orEmpty().size
        }

    /** Saves an upright card-area image, downscaled to [MAX_SIDE]. Takes ownership of [image]. */
    fun save(target: String, image: Bitmap): Boolean {
        val dir = File(root, target).apply { mkdirs() }
        val index = dir.listFiles().orEmpty().size
        val scaled = BitmapOps.scaleToFit(image, MAX_SIDE)
        return try {
            // Locale.ROOT keeps ASCII digits in file names even in Persian locale.
            BitmapOps.saveJpeg(scaled, File(dir, String.format(Locale.ROOT, "%03d.jpg", index)).absolutePath, JPEG_QUALITY)
        } finally {
            scaled.recycle()
        }
    }

    fun clear(target: String) {
        File(root, target).deleteRecursively()
    }

    fun clearAll() {
        root.deleteRecursively()
    }

    fun totalBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Packs everything into one zip in the cache (shared via FileProvider) and returns it. */
    fun exportZip(appVersion: String): File {
        val dir = File(context.cacheDir, EXPORT_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val zip = File(dir, EXPORT_NAME)
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            val manifest = JSONObject()
                .put("format", "pokervision-card-capture-v1")
                .put("appVersion", appVersion)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("shotsPerTarget", com.pokervision.core.domain.vision.DeckCapturePlan.SHOTS_PER_TARGET)
                .put("note", "Each folder name is the card code (rank + suit letter); 'background' = empty table.")
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write(manifest.toString(2).toByteArray())
            out.closeEntry()
            root.walkTopDown().filter { it.isFile && it.extension == "jpg" }.forEach { f ->
                out.putNextEntry(ZipEntry("cards/" + f.relativeTo(root).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    companion object {
        const val DIR = "training"
        const val EXPORT_DIR = "export"
        const val EXPORT_NAME = "PokerVisionAI-my-cards.zip"
        private const val MAX_SIDE = 640
        private const val JPEG_QUALITY = 88
    }
}
