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

    private fun sight(code: String, score: Float = 0.8f, pips: Int = 1) =
        CardSighting(c(code), score, BoundingBox(0f, 0f, 0.1f, 0.1f), pips)

    @Test
    fun `confirmer requires repeated sightings`() {
        val conf = CardConfirmer(window = 4, requiredWeight = 3, minScore = 0.5f)
        assertTrue(conf.update(listOf(sight("As"))).confirmed.isEmpty())
        assertTrue(conf.update(listOf(sight("As"), sight("2c", 0.3f))).confirmed.isEmpty()) // 2c below min score
        val s = conf.update(listOf(sight("As")))
        assertEquals(setOf(c("As")), s.confirmed.keys)
        assertFalse(c("2c") in s.tentative)
    }

    @Test
    fun `one-frame flicker never confirms`() {
        val conf = CardConfirmer(window = 8, requiredWeight = 4)
        conf.update(listOf(sight("7d", pips = 2)))
        repeat(7) { assertFalse(c("7d") in conf.update(emptyList()).confirmed) }
    }

    @Test
    fun `both corners agreeing confirms faster than a single corner`() {
        val twoCorners = CardConfirmer() // default: weight 4 in 8 frames
        twoCorners.update(listOf(sight("Ah", pips = 2)))
        assertTrue(c("Ah") in twoCorners.update(listOf(sight("Ah", pips = 2))).confirmed)

        val oneCorner = CardConfirmer()
        repeat(3) { assertFalse(c("4h") in oneCorner.update(listOf(sight("4h"))).confirmed) }
        assertTrue(c("4h") in oneCorner.update(listOf(sight("4h"))).confirmed)
    }

    @Test
    fun `ace and four flip-flopping on one corner each does not confirm quickly`() {
        val conf = CardConfirmer()
        // Same card, corners disagree every frame: A on one corner, 4 on the other.
        repeat(3) {
            val s = conf.update(listOf(sight("As"), sight("4s")))
            assertTrue(s.confirmed.isEmpty())
        }
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

    @Test
    fun `hand assembly - hero first then board`() {
        var hand = LiveHand(opponents = 2)
        hand = LiveHandAssembler.onConfirmed(hand, setOf(c("As"), c("Kh")))
        assertEquals(LivePhase.WATCH_BOARD, hand.phase)
        assertEquals(setOf(c("As"), c("Kh")), hand.hero.toSet())
        hand = LiveHandAssembler.onConfirmed(hand, setOf(c("As"), c("Qh"), c("7h"), c("2c")))
        assertEquals(setOf(c("Qh"), c("7h"), c("2c")), hand.board.toSet())
        hand = LiveHandAssembler.onConfirmed(hand, setOf(c("9s")))
        assertEquals(4, hand.board.size)
    }

    @Test
    fun `too many cards while scanning hero is reported not guessed`() {
        val hand = LiveHandAssembler.onConfirmed(LiveHand(), setOf(c("As"), c("Kh"), c("2c")))
        assertTrue(hand.hero.isEmpty())
        assertTrue(hand.tooManyCardsForHero)
    }

    @Test
    fun `hero can arrive one card at a time`() {
        var hand = LiveHandAssembler.onConfirmed(LiveHand(), setOf(c("As")))
        assertEquals(LivePhase.SCAN_HERO, hand.phase)
        hand = LiveHandAssembler.onConfirmed(hand, setOf(c("As"), c("Kh")))
        assertEquals(LivePhase.WATCH_BOARD, hand.phase)
    }

    @Test
    fun `rejected card is not re-added and board is capped at five`() {
        var hand = LiveHand(hero = listOf(c("As"), c("Kh")), phase = LivePhase.WATCH_BOARD)
        hand = LiveHandAssembler.onConfirmed(hand, setOf(c("2c"), c("3c"), c("4c")))
        hand = LiveHandAssembler.reject(hand, c("3c"))
        hand = LiveHandAssembler.onConfirmed(hand, setOf(c("3c"), c("5c"), c("6c"), c("7c"), c("8c")))
        assertFalse(c("3c") in hand.board)
        assertEquals(5, hand.board.size)
    }

    @Test
    fun `manual add fills slots and respects limits`() {
        var hand = LiveHandAssembler.addManual(LiveHand(), c("As"), toHero = true)
        hand = LiveHandAssembler.addManual(hand, c("As"), toHero = true) // duplicate ignored
        hand = LiveHandAssembler.addManual(hand, c("Kd"), toHero = true)
        assertEquals(LivePhase.WATCH_BOARD, hand.phase)
        hand = LiveHandAssembler.addManual(hand, c("2c"), toHero = true) // hero full
        assertEquals(2, hand.hero.size)
        hand = LiveHandAssembler.addManual(hand, c("2c"), toHero = false)
        assertEquals(listOf(c("2c")), hand.board)
    }

    @Test
    fun `rejecting a hero card returns to scanning and new hand keeps opponents`() {
        var hand = LiveHand(hero = listOf(c("As"), c("Kh")), board = listOf(c("2c")), opponents = 4, phase = LivePhase.WATCH_BOARD)
        hand = LiveHandAssembler.reject(hand, c("Kh"))
        assertEquals(LivePhase.SCAN_HERO, hand.phase)
        val next = LiveHandAssembler.newHand(hand)
        assertEquals(4, next.opponents)
        assertTrue(next.hero.isEmpty() && next.board.isEmpty() && next.rejected.isEmpty())
        assertEquals(hand.handNumber + 1, next.handNumber)
    }
}
