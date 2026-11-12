package com.pokervision.core.common.timing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateLimiterTest {

    private val msNanos = 1_000_000L

    @Test
    fun `first frame is always accepted`() {
        assertTrue(FrameRateLimiter(5.0).shouldProcess(123L))
    }

    @Test
    fun `30 fps stream limited to 5 fps accepts every sixth frame`() {
        val limiter = FrameRateLimiter(5.0)
        val frameIntervalNanos = 1_000_000_000L / 30
        var accepted = 0
        // 3 seconds of 30 FPS frames
        for (i in 0 until 90) {
            if (limiter.shouldProcess(i * frameIntervalNanos)) accepted++
        }
        // 5 FPS over 3s => 15 frames (allow +-1 for boundary rounding)
        assertTrue("accepted=$accepted", accepted in 14..16)
    }

    @Test
    fun `frames inside interval are rejected`() {
        val limiter = FrameRateLimiter(10.0) // 100 ms
        assertTrue(limiter.shouldProcess(0))
        assertFalse(limiter.shouldProcess(50 * msNanos))
        assertFalse(limiter.shouldProcess(90 * msNanos))
        assertTrue(limiter.shouldProcess(100 * msNanos))
    }

    @Test
    fun `clock reset is accepted`() {
        val limiter = FrameRateLimiter(1.0)
        assertTrue(limiter.shouldProcess(5_000 * msNanos))
        assertTrue(limiter.shouldProcess(10 * msNanos))
    }

    @Test
    fun `changing target fps applies immediately`() {
        val limiter = FrameRateLimiter(1.0)
        assertTrue(limiter.shouldProcess(0))
        assertFalse(limiter.shouldProcess(200 * msNanos))
        limiter.targetFps = 10.0
        assertTrue(limiter.shouldProcess(200 * msNanos))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero fps is rejected`() {
        FrameRateLimiter(0.0)
    }

    @Test
    fun `throughput meter reports window fps`() {
        val meter = ThroughputMeter(windowNanos = 1_000 * msNanos)
        for (i in 0..10) meter.record(i * 100 * msNanos) // 10 FPS for 1 s
        assertEquals(10.0, meter.fps(), 0.001)
    }

    @Test
    fun `throughput meter with single sample is zero`() {
        val meter = ThroughputMeter()
        meter.record(1)
        assertEquals(0.0, meter.fps(), 0.0)
    }
}
