package com.pokervision.core.vision

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pokervision.core.model.card.PlayingCard
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test of the full Kotlin pipeline (letterbox, ONNX, decode, pip clustering) on real card
 * photos fetched by scripts/fetch_card_samples.py. It reports results to logcat (tag PVCardSamples)
 * and only fails if nothing at all is recognised, which would indicate a pipeline bug rather than
 * normal model misses.
 */
@RunWith(AndroidJUnit4::class)
class RealCardSampleTest {

    @Test
    fun recognisesRealCardPhotos() {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val files = testAssets.list("samples").orEmpty().filter { it.endsWith(".jpg") }
        assumeTrue("no sample photos downloaded", files.isNotEmpty())
        val detector = OnnxCardDetector(ApplicationProvider.getApplicationContext<Context>())
        assumeTrue(detector.prepare() == DetectorStatus.Ready)

        var correct = 0
        var timeMs = 0L
        files.forEach { name ->
            val expected = PlayingCard.parse(name.substringBefore('_'))
            val bitmap = testAssets.open("samples/$name").use { BitmapFactory.decodeStream(it) }
            val result = detector.detect(bitmap, scoreThreshold = 0.25f)
            timeMs += result.inferenceMillis
            val found = result.sightings.map { "${it.card.code}:${"%.2f".format(java.util.Locale.ROOT, it.score)}" }
            val ok = result.sightings.firstOrNull()?.card == expected
            if (ok) correct++
            Log.i(TAG, "$name expected=${expected.code} top=${found.take(3)} ${if (ok) "OK" else "MISS"} ${result.inferenceMillis}ms")
        }
        Log.i(TAG, "SUMMARY correct=$correct/${files.size} avgInference=${timeMs / files.size}ms")
        // Kotlin assert() is disabled on Android; use JUnit.
        assertTrue("No sample card recognised at all: likely a preprocessing/decoding bug", correct > 0)
    }

    private companion object {
        const val TAG = "PVCardSamples"
    }
}
