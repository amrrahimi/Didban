package com.pokervision.core.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real model on the emulator. CI fetches the model before tests; if it is absent the
 * detector must say so rather than fail.
 */
@RunWith(AndroidJUnit4::class)
class OnnxCardDetectorTest {

    private val detector = OnnxCardDetector(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun modelLoadsAndBlankFrameHasNoCards() {
        assertEquals("CI must bundle the model", DetectorStatus.Ready, detector.prepare())
        val blank = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 80, 40)) }
        val result = detector.detect(blank, scoreThreshold = 0.5f)
        // Model card: "an empty frame yields no detection".
        assertTrue("unexpected cards on a blank felt frame: ${result.sightings}", result.sightings.isEmpty())
        assertTrue(result.inferenceMillis >= 0)
    }

    @Test
    fun portraitFrameIsAccepted() {
        val portrait = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        detector.detect(portrait, scoreThreshold = 0.5f) // must not throw (letterbox handles any aspect)
    }
}
