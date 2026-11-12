package com.pokervision.core.domain.media

import com.pokervision.core.common.result.AppError
import com.pokervision.core.domain.usecase.FakeMediaRepository
import com.pokervision.core.domain.usecase.FakeSettingsRepository
import com.pokervision.core.domain.usecase.media
import com.pokervision.core.model.media.MediaType
import com.pokervision.core.model.settings.KeyframeSensitivity
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractKeyframesUseCaseTest {

    /** A synthetic video: luminance value per second of footage; null entries can't be decoded. */
    private class FakeVideo(private val lumaPerSecond: List<Int?>) : VideoFrameReader {
        var closed = false
        var released = 0
        val saved = mutableListOf<String>()

        override suspend fun open(path: String): VideoFrameSession = object : VideoFrameSession {
            override val durationMs: Long = lumaPerSecond.size * 1_000L
            override suspend fun frameAt(timestampMs: Long, maxDimension: Int): DecodedFrame? {
                val luma = lumaPerSecond[(timestampMs / 1_000).toInt()] ?: return null
                return object : DecodedFrame {
                    override val timestampMs = timestampMs
                    override val size = PixelSize(1280, 720)
                    override fun signature(signatureSize: Int) =
                        LumaSignature(signatureSize, IntArray(signatureSize * signatureSize) { luma })
                    override fun saveJpeg(path: String, quality: Int): Boolean {
                        saved += path
                        return true
                    }
                    override fun release() {
                        released++
                    }
                }
            }
            override fun close() {
                closed = true
            }
        }
    }

    private class FakeStore : KeyframeStore {
        var cleared = 0
        override fun pathFor(assetId: Long, timestampMs: Long) = "/cache/$assetId/$timestampMs.jpg"
        override fun clear(assetId: Long) {
            cleared++
        }
        override fun clearAll() = Unit
        override fun list(assetId: Long) = emptyList<Keyframe>()
    }

    private val config = KeyframeExtractionConfig(
        sampleIntervalMs = 1_000,
        sensitivity = KeyframeSensitivity.MEDIUM,
        minGapMs = 0,
        maxGapMs = 60_000,
    )

    private fun useCase(video: FakeVideo, store: KeyframeStore = FakeStore()) = ExtractKeyframesUseCase(
        FakeMediaRepository(listOf(media(1, MediaType.VIDEO), media(2, MediaType.IMAGE))),
        FakeSettingsRepository(),
        video,
        store,
    )

    @Test
    fun `keeps only scene changes and releases every frame`() = runBlocking {
        // static, static, change, static, change back
        val video = FakeVideo(listOf(50, 52, 120, 121, 50))
        val events = useCase(video)(1, config).toList()
        val done = events.last() as KeyframeExtractionEvent.Completed
        assertEquals(listOf(0L, 2_000L, 4_000L), done.keyframes.map { it.timestampMs })
        assertEquals(5, done.sampled)
        assertEquals(5, video.released)
        assertTrue(video.closed)
        assertEquals(5, events.count { it is KeyframeExtractionEvent.Progress })
        assertEquals(3, video.saved.size)
    }

    @Test
    fun `undecodable frames are counted and skipped`() = runBlocking {
        val done = useCase(FakeVideo(listOf(50, null, 200)))(1, config).last() as KeyframeExtractionEvent.Completed
        assertEquals(1, done.undecodable)
        assertEquals(listOf(0L, 2_000L), done.keyframes.map { it.timestampMs })
    }

    @Test
    fun `all frames undecodable is a failure`() = runBlocking {
        val last = useCase(FakeVideo(listOf(null, null)))(1, config).last()
        assertTrue((last as KeyframeExtractionEvent.Failed).error is AppError.Unsupported)
    }

    @Test
    fun `image asset is rejected`() = runBlocking {
        val last = useCase(FakeVideo(listOf(1)))(2, config).last()
        assertTrue((last as KeyframeExtractionEvent.Failed).error is AppError.NotFound)
    }

    @Test
    fun `cancelling collection closes the video`() = runBlocking {
        val video = FakeVideo(List(20) { it * 10 })
        useCase(video)(1, config).take(3).toList()
        assertTrue(video.closed)
    }

    @Test
    fun `settings are used when no override`() = runBlocking {
        val store = FakeStore()
        val done = useCase(FakeVideo(listOf(10, 10, 10)), store)(1).last()
        assertTrue(done is KeyframeExtractionEvent.Completed)
        assertEquals(1, store.cleared)
    }
}
