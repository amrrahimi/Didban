package com.pokervision.core.domain.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGeometryTest {

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
