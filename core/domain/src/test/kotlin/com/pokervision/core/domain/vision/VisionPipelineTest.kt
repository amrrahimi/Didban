package com.pokervision.core.domain.vision

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.detection.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPipelineTest {

    private fun c(s: String) = PlayingCard.parse(s)
    private val labels = CardLabels.parse(CardLabels.DEFAULT_ORDER)

    @Test
    fun `labels match the documented model order`() {
        assertEquals(52, labels.size)
        assertEquals(c("Th"), labels[2]) // "10H"
        assertEquals(c("As"), labels[39])
        assertEquals(c("Qs"), labels[51])
        assertEquals(52, labels.toSet().size)
    }

    @Test
    fun `letterbox maps model pixels back to the source`() {
        val lb = Letterbox(1280, 720, 640) // scale 0.5, scaled 640x360, padY 140
        assertEquals(0.5f, lb.scale, 1e-6f)
        assertEquals(140, lb.padY)
        val box = lb.toSourceBox(cx = 320f, cy = 320f, w = 64f, h = 36f) // centre of image
        assertEquals(0.45f, box.left, 1e-4f)
        assertEquals(0.55f, box.right, 1e-4f)
        assertEquals(0.45f, box.top, 1e-4f)
        assertEquals(0.55f, box.bottom, 1e-4f)
    }

    /** Builds a [1, 4+nc, anchors] tensor with given (anchor, cx, cy, w, h, class, score) entries. */
    private fun tensor(nc: Int, anchors: Int, vararg boxes: FloatArray): FloatArray {
        val out = FloatArray((4 + nc) * anchors)
        boxes.forEachIndexed { a, b ->
            out[a] = b[0]; out[anchors + a] = b[1]; out[2 * anchors + a] = b[2]; out[3 * anchors + a] = b[3]
            out[(4 + b[4].toInt()) * anchors + a] = b[5]
        }
        return out
    }

    @Test
    fun `decoder keeps confident boxes and suppresses overlapping duplicates`() {
        val lb = Letterbox(640, 640, 640)
        val out = tensor(
            nc = 3, anchors = 5,
            floatArrayOf(100f, 100f, 20f, 30f, 1f, 0.9f),
            floatArrayOf(101f, 101f, 20f, 30f, 1f, 0.8f), // duplicate of the first -> suppressed
            floatArrayOf(400f, 400f, 20f, 30f, 2f, 0.7f),
            floatArrayOf(500f, 500f, 20f, 30f, 0f, 0.2f), // below threshold
        )
        val d = YoloDecoder.decode(out, numClasses = 3, numAnchors = 5, scoreThreshold = 0.3f, letterbox = lb)
        assertEquals(listOf(1, 2), d.map { it.classIndex })
        assertEquals(0.9f, d[0].score, 1e-6f)
    }

    private fun pip(cls: Int, score: Float, x: Float, y: Float, s: Float = 0.02f) =
        PipDetection(cls, score, BoundingBox(x, y, x + s, y + s))

    @Test
    fun `two corner pips of the same card become one card`() {
        val aceSpades = labels.indexOf(c("As"))
        val kingHearts = labels.indexOf(c("Kh"))
        val sightings = PipClusterer.cluster(
            listOf(
                pip(aceSpades, 0.9f, 0.30f, 0.30f),
                pip(aceSpades, 0.7f, 0.38f, 0.42f), // opposite corner of the same card
                pip(kingHearts, 0.8f, 0.60f, 0.30f),
            ),
            labels,
        )
        assertEquals(listOf(c("As"), c("Kh")), sightings.map { it.card })
        assertEquals(2, sightings[0].pipCount)
        assertEquals(0.9f, sightings[0].score, 1e-6f)
    }

    @Test
    fun `far away same-class pip is ignored`() {
        val ace = labels.indexOf(c("As"))
        val s = PipClusterer.cluster(listOf(pip(ace, 0.9f, 0.1f, 0.1f), pip(ace, 0.6f, 0.9f, 0.9f)), labels).single()
        assertEquals(1, s.pipCount)
    }

    @Test
    fun `focus region crop and mapping`() {
        val crop = FocusRegion.centerSquare(1080, 1920, 0.8f)
        assertEquals(CropRect(108, 528, 864, 864), crop)
        val full = FocusRegion.toFrame(BoundingBox(0f, 0f, 1f, 1f), crop, 1080, 1920)
        assertEquals(0.1f, full.left, 1e-4f)
        assertEquals(0.9f, full.right, 1e-4f)
        assertEquals(528f / 1920, full.top, 1e-4f)
        assertEquals(FocusRegion.normalized(crop, 1080, 1920), full)
    }
}
