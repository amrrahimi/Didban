package com.pokervision.core.media

import android.content.Context
import com.pokervision.core.domain.media.Keyframe
import com.pokervision.core.domain.media.KeyframeStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keyframes live in the app cache (`cacheDir/keyframes/<assetId>/<timestampMs>.jpg`): private,
 * regenerable, and the OS may reclaim them. File names encode the timestamp so the list can be
 * rebuilt without a database.
 */
@Singleton
class CacheKeyframeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : KeyframeStore {

    private val root: File get() = File(context.cacheDir, "keyframes")

    private fun dir(assetId: Long) = File(root, assetId.toString())

    override fun pathFor(assetId: Long, timestampMs: Long): String =
        // Locale.ROOT: in Persian locale %d would produce Persian digits that toLongOrNull() cannot parse.
        File(dir(assetId).apply { mkdirs() }, String.format(Locale.ROOT, "%010d.jpg", timestampMs)).absolutePath

    override fun clear(assetId: Long) {
        dir(assetId).deleteRecursively()
    }

    override fun clearAll() {
        root.deleteRecursively()
    }

    override fun list(assetId: Long): List<Keyframe> =
        dir(assetId).listFiles { f -> f.isFile && f.extension == "jpg" }
            .orEmpty()
            .mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { Keyframe(it, f.absolutePath) } }
            .sortedBy { it.timestampMs }
}
