package com.pokervision.core.domain.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSamplingTest {

    @Test
    fun `plan samples at interval`() {
        assertEquals(listOf(0L, 1000L, 2000L, 3000L, 4000L), FrameSamplingPlan.timestamps(4_500, 1_000, 100))
    }

    @Test
    fun `plan never reaches duration`() {
        assertEquals(listOf(0L, 1000L), FrameSamplingPlan.timestamps(2_000, 1_000, 100))
    }

    @Test
    fun `plan widens interval to respect max frames`() {
        val plan = FrameSamplingPlan.timestamps(600_000, 200, 600) // 10 min at 5 fps would be 3000
        assertTrue(plan.size <= 600)
        assertTrue(plan.size >= 590)
        assertEquals(0L, plan.first())
        assertTrue(plan.last() < 600_000)
        assertTrue(plan.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `empty for zero duration`() {
        assertTrue(FrameSamplingPlan.timestamps(0, 1_000, 10).isEmpty())
    }

    @Test
    fun `short video still gets first frame`() {
        assertEquals(listOf(0L), FrameSamplingPlan.timestamps(300, 1_000, 10))
    }

    private fun sig(value: Int, size: Int = 4) = LumaSignature(size, IntArray(size * size) { value })

    @Test
    fun `signature distance`() {
        assertEquals(0.0, sig(100).distanceTo(sig(100)), 0.0)
        assertEquals(30.0, sig(100).distanceTo(sig(130)), 1e-9)
        val half = LumaSignature(2, intArrayOf(0, 0, 255, 255))
        assertEquals(127.5, half.distanceTo(sig(0, 2)), 1e-9)
    }

    @Test
    fun `selector keeps first frame and real changes only`() {
        val s = KeyframeSelector(changeThreshold = 10.0)
        assertTrue(s.offer(0, sig(100)).isKeyframe)
        assertFalse(s.offer(1000, sig(105)).isKeyframe) // noise
        assertTrue(s.offer(2000, sig(140)).isKeyframe) // scene change
        assertFalse(s.offer(3000, sig(141)).isKeyframe) // compared with last keyframe (140), not 105
    }

    @Test
    fun `selector compares with last keyframe so slow drift is caught`() {
        val s = KeyframeSelector(changeThreshold = 10.0)
        s.offer(0, sig(100))
        assertFalse(s.offer(1000, sig(106)).isKeyframe)
        assertTrue(s.offer(2000, sig(112)).isKeyframe) // 12 from keyframe at 100
    }

    @Test
    fun `selector min gap suppresses bursts`() {
        val s = KeyframeSelector(changeThreshold = 5.0, minGapMs = 1_000)
        s.offer(0, sig(0))
        assertFalse(s.offer(500, sig(200)).isKeyframe)
        assertTrue(s.offer(1_000, sig(200)).isKeyframe)
    }

    @Test
    fun `selector max gap forces coverage`() {
        val s = KeyframeSelector(changeThreshold = 50.0, maxGapMs = 10_000)
        s.offer(0, sig(0))
        assertFalse(s.offer(5_000, sig(0)).isKeyframe)
        assertTrue(s.offer(10_000, sig(0)).isKeyframe)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `selector rejects out of order frames`() {
        val s = KeyframeSelector(10.0)
        s.offer(1_000, sig(0))
        s.offer(500, sig(0))
    }

    @Test
    fun `fit within keeps aspect and never upscales`() {
        assertEquals(PixelSize(1280, 720), ResizeMath.fitWithin(PixelSize(1920, 1080), 1280, 1280))
        assertEquals(PixelSize(720, 1280), ResizeMath.fitWithin(PixelSize(1080, 1920), 1280, 1280))
        assertEquals(PixelSize(640, 480), ResizeMath.fitWithin(PixelSize(640, 480), 1280, 1280))
        assertEquals(PixelSize(1, 1), ResizeMath.fitWithin(PixelSize(10, 10), 1, 1))
    }

    @Test
    fun `in sample size stays above target`() {
        assertEquals(1, ResizeMath.inSampleSize(PixelSize(1000, 800), 800))
        assertEquals(2, ResizeMath.inSampleSize(PixelSize(4000, 3000), 1280))
        assertEquals(4, ResizeMath.inSampleSize(PixelSize(4000, 3000), 640))
        assertEquals(1, ResizeMath.inSampleSize(PixelSize(100, 100), 1280))
    }

    @Test
    fun `rotation helpers`() {
        assertEquals(PixelSize(1080, 1920), PixelSize(1920, 1080).rotated(90))
        assertEquals(PixelSize(1920, 1080), PixelSize(1920, 1080).rotated(180))
        assertEquals(270, ResizeMath.normalizeRotation(-90))
        assertEquals(0, ResizeMath.normalizeRotation(360))
    }
}
