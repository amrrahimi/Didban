package com.pokervision.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pokervision.core.domain.media.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BitmapOpsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun solid(w: Int, h: Int, color: Int) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun rotateSwapsDimensions() {
        val r = BitmapOps.rotate(solid(40, 20, Color.RED), 90)
        assertEquals(20, r.width)
        assertEquals(40, r.height)
    }

    @Test
    fun scaleToFitKeepsAspect() {
        val s = BitmapOps.scaleToFit(solid(400, 200, Color.RED), 100)
        assertEquals(100, s.width)
        assertEquals(50, s.height)
    }

    @Test
    fun lumaOfWhiteAndBlack() {
        assertTrue(BitmapOps.lumaSignature(solid(64, 64, Color.WHITE), 8).values.all { it >= 254 })
        assertTrue(BitmapOps.lumaSignature(solid(64, 64, Color.BLACK), 8).values.all { it == 0 })
    }

    @Test
    fun exifRotationIsApplied() {
        val file = File(context.cacheDir, "exif_test.jpg")
        assertTrue(BitmapOps.saveJpeg(solid(300, 100, Color.BLUE), file.absolutePath, 90))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val info = BitmapOps.readImageInfo(file.absolutePath)
        assertNotNull(info)
        assertEquals(90, info!!.rotationDegrees)
        assertEquals(PixelSize(100, 300), info.size)
        val decoded = BitmapOps.decodeOriented(file.absolutePath, 150)!!
        assertEquals(50, decoded.width)
        assertEquals(150, decoded.height)
    }
}
