package com.pokervision.core.domain.vision

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.detection.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CardMemoryTest {
    private val W = GlyphFeatures.WIDTH
    private val H = GlyphFeatures.HEIGHT

    /** Draws a simple symbol: a set of filled rectangles in [color] on white paper, with optional noise. */
    private fun glyph(rects: List<IntArray>, color: Int = 0x000000, noise: Int = 0, seed: Int = 1, brightness: Int = 0): IntArray {
        val rnd = Random(seed)
        val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val ink = rects.any { r -> x >= r[0] && x < r[2] && y >= r[1] && y < r[3] }
            val base = if (ink) color else 0xFFFFFF
            fun ch(v: Int) = (v + brightness + if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0).coerceIn(0, 255)
            val r = ch((base shr 16) and 0xFF); val g = ch((base shr 8) and 0xFF); val b = ch(base and 0xFF)
            px[y * W + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return px
    }

    // "A": two legs and a bar. "4": a vertical, a horizontal and a short diagonal-ish stub.
    private val shapeA = listOf(intArrayOf(3, 4, 6, 24), intArrayOf(14, 4, 17, 24), intArrayOf(3, 12, 17, 15), intArrayOf(3, 4, 17, 7))
    private val shapeFour = listOf(intArrayOf(12, 3, 15, 25), intArrayOf(2, 15, 18, 18), intArrayOf(2, 3, 5, 16))

    private fun feature(rects: List<IntArray>, color: Int = 0, noise: Int = 0, seed: Int = 1, brightness: Int = 0) =
        GlyphFeatures.from(glyph(rects, color, noise, seed, brightness))!!

    @Test
    fun `blank patch has no feature`() {
        assertNull(GlyphFeatures.from(IntArray(W * H) { 0xFFFFFFFF.toInt() }))
    }

    @Test
    fun `same symbol is very similar despite noise and brightness change`() {
        val a1 = feature(shapeA, noise = 20, seed = 1)
        val a2 = feature(shapeA, noise = 20, seed = 2, brightness = -40)
        val four = feature(shapeFour, noise = 20, seed = 3)
        assertTrue(GlyphFeatures.similarity(a1, a2) > 0.85f)
        assertTrue(GlyphFeatures.similarity(a1, four) < 0.6f)
    }

    @Test
    fun `a box one or two pixels off still matches the same card best`() {
        val a = feature(shapeA)
        val shifted = feature(shapeA.map { intArrayOf(it[0] + 1, it[1] + 2, it[2] + 1, it[3] + 2) })
        val four = feature(shapeFour)
        assertTrue(GlyphFeatures.shiftedSimilarity(a, shifted) > MemoryRecognizer.STRONG_SIMILARITY)
        assertTrue(GlyphFeatures.shiftedSimilarity(shifted, four) < GlyphFeatures.shiftedSimilarity(shifted, a) - 0.2f)
        val memory = CardMemory()
        memory.learn(PlayingCard.parse("AS"), a)
        memory.learn(PlayingCard.parse("4S"), four)
        val m = memory.match(shifted)!!
        assertEquals(PlayingCard.parse("AS"), m.card)
        assertEquals(PlayingCard.parse("AS"), MemoryRecognizer.decide(PlayingCard.parse("4S"), 0.7f, m).card)
    }

    @Test
    fun `upside down corner still matches`() {
        val a = feature(shapeA)
        val turned = GlyphFeature(a.gray.reversedArray(), a.redness)
        assertTrue(GlyphFeatures.similarity(a, turned) > 0.99f)
    }

    @Test
    fun `red and black versions of a shape are told apart`() {
        val black = feature(shapeA, color = 0x000000)
        val red = feature(shapeA, color = 0xD01010)
        assertTrue(red.redness > 0.5f)
        assertTrue(black.redness < 0.1f)
        assertTrue(GlyphFeatures.similarity(black, red) < GlyphFeatures.similarity(black, feature(shapeA, noise = 10)))
    }

    @Test
    fun `memory corrects the model once the user taught the card`() {
        val ace = PlayingCard.parse("AS")
        val four = PlayingCard.parse("4S")
        val memory = CardMemory()
        memory.learn(ace, feature(shapeA, noise = 15, seed = 10))
        memory.learn(four, feature(shapeFour, noise = 15, seed = 11))

        val newAce = feature(shapeA, noise = 15, seed = 12, brightness = 20)
        val match = memory.match(newAce)
        assertNotNull(match)
        assertEquals(ace, match!!.card)

        // Model thinks it is a 4 with fair confidence; the taught example wins.
        val decision = MemoryRecognizer.decide(four, 0.7f, match)
        assertEquals(ace, decision.card)
        assertTrue(decision.fromMemory)
        assertTrue(decision.score >= MemoryRecognizer.TRUSTED_SCORE)
    }

    @Test
    fun `unknown symbol keeps the model's reading`() {
        val memory = CardMemory()
        memory.learn(PlayingCard.parse("AS"), feature(shapeA))
        val other = feature(listOf(intArrayOf(2, 2, 18, 5), intArrayOf(8, 5, 11, 26)))
        val decision = MemoryRecognizer.decide(PlayingCard.parse("7C"), 0.6f, memory.match(other))
        assertEquals(PlayingCard.parse("7C"), decision.card)
        assertFalse(decision.fromMemory)
        assertEquals(0.6f, decision.score)
    }

    @Test
    fun `empty memory changes nothing`() {
        val decision = MemoryRecognizer.decide(PlayingCard.parse("KH"), 0.4f, CardMemory().match(feature(shapeA)))
        assertEquals(PlayingCard.parse("KH"), decision.card)
        assertFalse(decision.fromMemory)
    }

    @Test
    fun `keeps only the newest examples per card so new designs take over`() {
        val memory = CardMemory(perCard = 2)
        val ace = PlayingCard.parse("AH")
        repeat(5) { memory.learn(ace, feature(shapeA, color = 0xD01010, noise = 10, seed = it)) }
        assertEquals(2, memory.exampleCount)
        assertEquals(1, memory.cardCount)
    }

    @Test
    fun `survives saving and loading`() {
        val memory = CardMemory()
        memory.learn(PlayingCard.parse("AS"), feature(shapeA, seed = 1, noise = 5))
        memory.learn(PlayingCard.parse("4D"), feature(shapeFour, color = 0xD01010))
        val restored = CardMemory.fromBytes(memory.toBytes())
        assertEquals(2, restored.exampleCount)
        assertEquals(PlayingCard.parse("4D"), restored.match(feature(shapeFour, color = 0xD01010, noise = 5))!!.card)
    }

    @Test
    fun `damaged file gives empty memory`() {
        assertEquals(0, CardMemory.fromBytes(byteArrayOf(1, 2, 3)).exampleCount)
        val good = CardMemory().apply { learn(PlayingCard.parse("AS"), feature(shapeA)) }.toBytes()
        assertEquals(0, CardMemory.fromBytes(good.copyOf(good.size - 10)).exampleCount)
    }

    private val labels = CardLabels.parse(CardLabels.DEFAULT_ORDER)
    private fun idx(code: String) = labels.indexOf(PlayingCard.parse(code))

    @Test
    fun `refine fixes the class, lifts weak proposals and removes duplicates`() {
        val memory = CardMemory()
        memory.learn(PlayingCard.parse("AS"), feature(shapeA, noise = 10, seed = 1))
        val box = BoundingBox(0.1f, 0.1f, 0.14f, 0.16f)
        val pips = listOf(
            PipDetection(idx("4S"), 0.55f, box),                                  // misread ace
            PipDetection(idx("AS"), 0.15f, BoundingBox(0.101f, 0.1f, 0.141f, 0.16f)), // same symbol, weak
            PipDetection(idx("KH"), 0.2f, BoundingBox(0.5f, 0.5f, 0.54f, 0.56f)),   // weak, unknown
        )
        val features = listOf(
            feature(shapeA, noise = 10, seed = 2),
            feature(shapeA, noise = 10, seed = 3),
            null,
        )
        val out = MemoryRecognizer.refine(pips, features, labels, memory, minScore = 0.3f)
        assertEquals(1, out.size)
        assertEquals(idx("AS"), out[0].classIndex)
        val cards = PipClusterer.cluster(out, labels)
        assertEquals(listOf(PlayingCard.parse("AS")), cards.map { it.card })
        assertEquals(1, cards[0].pipBoxes.size)
    }

    @Test
    fun `refine without memory only filters`() {
        val pips = listOf(
            PipDetection(idx("QD"), 0.8f, BoundingBox(0.1f, 0.1f, 0.14f, 0.16f)),
            PipDetection(idx("QD"), 0.1f, BoundingBox(0.6f, 0.1f, 0.64f, 0.16f)),
        )
        val out = MemoryRecognizer.refine(pips, listOf(null, null), labels, null, minScore = 0.3f)
        assertEquals(listOf(pips[0]), out)
    }

    @Test
    fun `unlearn removes the misleading example only`() {
        val memory = CardMemory()
        val four = PlayingCard.parse("4S")
        memory.learn(four, feature(shapeA, seed = 1, noise = 5))   // user mistake: an ace taught as 4
        memory.learn(four, feature(shapeFour, seed = 2, noise = 5))
        assertEquals(1, memory.unlearn(four, feature(shapeA, seed = 3, noise = 5)))
        assertEquals(1, memory.exampleCount)
        assertEquals(four, memory.match(feature(shapeFour, seed = 4, noise = 5))!!.card)
    }
}
