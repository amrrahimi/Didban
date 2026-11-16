package com.pokervision.core.domain.vision

import com.pokervision.core.model.card.PlayingCard
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Fingerprint" of one corner symbol (rank + suit): a small grayscale patch, mean-removed and
 * normalized so a dot product is a normalized cross-correlation (insensitive to brightness and
 * contrast), plus how red the symbol is (hearts/diamonds vs spades/clubs).
 */
class GlyphFeature(val gray: FloatArray, val redness: Float) {
    init {
        require(gray.size == GlyphFeatures.SIZE) { "Expected ${GlyphFeatures.SIZE} values, got ${gray.size}" }
    }
}

object GlyphFeatures {
    const val WIDTH = 20
    const val HEIGHT = 28
    const val SIZE = WIDTH * HEIGHT

    /** How much a red/black mismatch lowers similarity (a red 4 is never a black A). */
    private const val REDNESS_PENALTY = 0.6f

    /** Builds a feature from ARGB pixels of the symbol resized to [WIDTH] x [HEIGHT]. Null for flat patches. */
    fun from(argb: IntArray): GlyphFeature? {
        require(argb.size == SIZE) { "Expected $SIZE pixels" }
        val g = FloatArray(SIZE)
        var redInk = 0f
        var ink = 0f
        for (i in 0 until SIZE) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val gr = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = 0.299f * r + 0.587f * gr + 0.114f * b
            g[i] = y
            // "Ink" = darker than paper. Red ink is dark in green/blue but not in red.
            val darkness = (255f - y) / 255f
            ink += darkness
            redInk += darkness * ((r - (gr + b) / 2f).coerceAtLeast(0f) / 255f)
        }
        blur(g)
        val mean = g.average().toFloat()
        var norm = 0f
        for (i in 0 until SIZE) {
            g[i] -= mean
            norm += g[i] * g[i]
        }
        norm = sqrt(norm)
        if (norm < 1e-3f * SIZE) return null // blank patch: nothing to learn from
        for (i in 0 until SIZE) g[i] /= norm
        val redness = if (ink < 1e-3f) 0f else (redInk / ink * 4f).coerceIn(0f, 1f)
        return GlyphFeature(g, redness)
    }

    /** 3x3 box blur in place: makes matching tolerant to the box being a pixel or two off. */
    private fun blur(g: FloatArray) {
        val src = g.copyOf()
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            var sum = 0f
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until WIDTH && yy in 0 until HEIGHT) {
                    sum += src[yy * WIDTH + xx]
                    n++
                }
            }
            g[y * WIDTH + x] = sum / n
        }
    }

    /**
     * Similarity in about [-1, 1]. Also compares against the 180-degree turned patch, because the
     * bottom-right corner of a card shows the same symbol upside down.
     */
    fun similarity(a: GlyphFeature, b: GlyphFeature): Float {
        var direct = 0f
        var turned = 0f
        val n = SIZE
        for (i in 0 until n) {
            direct += a.gray[i] * b.gray[i]
            turned += a.gray[i] * b.gray[n - 1 - i]
        }
        return max(direct, turned) - REDNESS_PENALTY * abs(a.redness - b.redness)
    }

    /**
     * Like [similarity] but also tries moving one patch up to [MAX_SHIFT] pixels in each direction:
     * the model's boxes around the same symbol differ by a pixel or two between photos.
     */
    fun shiftedSimilarity(a: GlyphFeature, b: GlyphFeature): Float {
        var best = -2f
        for (dy in -MAX_SHIFT..MAX_SHIFT) for (dx in -MAX_SHIFT..MAX_SHIFT) {
            var direct = 0f
            var turned = 0f
            for (y in 0 until HEIGHT) {
                val yy = y + dy
                if (yy < 0 || yy >= HEIGHT) continue
                for (x in 0 until WIDTH) {
                    val xx = x + dx
                    if (xx < 0 || xx >= WIDTH) continue
                    val av = a.gray[y * WIDTH + x]
                    val j = yy * WIDTH + xx
                    direct += av * b.gray[j]
                    turned += av * b.gray[SIZE - 1 - j]
                }
            }
            best = max(best, max(direct, turned))
        }
        return best - REDNESS_PENALTY * abs(a.redness - b.redness)
    }

    const val MAX_SHIFT = 2
}

data class MemoryMatch(val card: PlayingCard, val similarity: Float, val runnerUpSimilarity: Float)

/**
 * Examples of corner symbols the user has confirmed or corrected, kept on the phone. Recognition
 * compares a new symbol with every example (nearest neighbour). Keeps the newest [perCard] examples
 * per card, so when a site changes its card design the new design takes over after a few hands.
 * Not thread-safe: guard externally.
 */
class CardMemory(private val perCard: Int = DEFAULT_PER_CARD) {
    private val examples = LinkedHashMap<PlayingCard, ArrayDeque<GlyphFeature>>()

    val cardCount: Int get() = examples.size
    val exampleCount: Int get() = examples.values.sumOf { it.size }

    fun learn(card: PlayingCard, feature: GlyphFeature) {
        val list = examples.getOrPut(card) { ArrayDeque() }
        list.addLast(feature)
        while (list.size > perCard) list.removeFirst()
    }

    /** Best matching card and the best score of any *other* card (to judge how clear the match is). */
    fun match(feature: GlyphFeature): MemoryMatch? {
        if (examples.isEmpty()) return null
        // Quick pass without shifting, then a careful (shift-tolerant) pass on the likeliest cards.
        val best = HashMap(examples.mapValues { (_, list) -> list.maxOf { GlyphFeatures.similarity(feature, it) } })
        best.entries.sortedByDescending { it.value }.take(CAREFUL_CANDIDATES).forEach { (card, _) ->
            best[card] = examples.getValue(card).maxOf { GlyphFeatures.shiftedSimilarity(feature, it) }
        }
        val top = best.maxBy { it.value }
        val runnerUp = best.filterKeys { it != top.key }.values.maxOrNull() ?: -1f
        return MemoryMatch(top.key, top.value, runnerUp)
    }

    /**
     * Removes examples of [card] that look like [feature]: used when the user says a symbol that
     * memory read as [card] is actually something else, so the wrong example stops misleading.
     */
    fun unlearn(card: PlayingCard, feature: GlyphFeature, minSimilarity: Float = UNLEARN_SIMILARITY): Int {
        val list = examples[card] ?: return 0
        val before = list.size
        list.removeAll { GlyphFeatures.shiftedSimilarity(feature, it) >= minSimilarity }
        if (list.isEmpty()) examples.remove(card)
        return before - list.size
    }

    fun forget(card: PlayingCard) {
        examples.remove(card)
    }

    fun clear() = examples.clear()

    fun toBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(GlyphFeatures.SIZE)
            out.writeInt(exampleCount)
            examples.forEach { (card, list) ->
                list.forEach { f ->
                    out.writeUTF(card.code)
                    out.writeFloat(f.redness)
                    f.gray.forEach(out::writeFloat)
                }
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        const val DEFAULT_PER_CARD = 12
        const val UNLEARN_SIMILARITY = 0.75f
        private const val CAREFUL_CANDIDATES = 6
        private const val FORMAT = 1

        /** Restores a memory; returns an empty memory for unknown or damaged data. */
        fun fromBytes(data: ByteArray, perCard: Int = DEFAULT_PER_CARD): CardMemory {
            val memory = CardMemory(perCard)
            runCatching {
                DataInputStream(ByteArrayInputStream(data)).use { input ->
                    if (input.readInt() != FORMAT || input.readInt() != GlyphFeatures.SIZE) return memory
                    repeat(input.readInt()) {
                        val card = PlayingCard.parse(input.readUTF())
                        val redness = input.readFloat()
                        val gray = FloatArray(GlyphFeatures.SIZE) { input.readFloat() }
                        memory.learn(card, GlyphFeature(gray, redness))
                    }
                }
            }.onFailure { memory.clear() }
            return memory
        }
    }
}

/**
 * Combines the model's guess for one corner symbol with what the user has taught.
 * The user's examples win when they clearly match: that is how "A read as 4" gets fixed for good.
 */
object MemoryRecognizer {
    /** A learned example this similar, and clearly closer than any other card, overrides the model. */
    const val STRONG_SIMILARITY = 0.80f
    const val MIN_MARGIN = 0.05f

    /** When memory and model agree, a weaker match is enough to trust the reading. */
    const val AGREE_SIMILARITY = 0.65f

    /** Score given to readings backed by the user's examples (above the photo-scan acceptance bar). */
    const val TRUSTED_SCORE = 0.95f

    data class Decision(val card: PlayingCard, val score: Float, val fromMemory: Boolean)

    fun decide(modelCard: PlayingCard, modelScore: Float, match: MemoryMatch?): Decision {
        if (match != null) {
            val clear = match.similarity - match.runnerUpSimilarity >= MIN_MARGIN
            if (match.similarity >= STRONG_SIMILARITY && clear) {
                return Decision(match.card, TRUSTED_SCORE, fromMemory = true)
            }
            if (match.card == modelCard && match.similarity >= AGREE_SIMILARITY) {
                return Decision(modelCard, max(modelScore, TRUSTED_SCORE), fromMemory = true)
            }
        }
        return Decision(modelCard, modelScore, fromMemory = false)
    }

    /**
     * Re-reads every corner symbol found by the model with the user's examples, then removes
     * duplicates (the same symbol proposed as two different cards) and weak readings.
     *
     * @param features one entry per pip (null when no usable patch could be cut out).
     * @param minScore readings below this after refinement are dropped; the model may be asked for
     *   weaker proposals than this, so a symbol the model barely noticed can still be recognized
     *   from memory.
     */
    fun refine(
        pips: List<PipDetection>,
        features: List<GlyphFeature?>,
        labels: List<PlayingCard>,
        memory: CardMemory?,
        minScore: Float,
        duplicateIou: Float = DUPLICATE_IOU,
    ): List<PipDetection> {
        require(features.size == pips.size) { "One feature slot per pip" }
        val refined = pips.mapIndexedNotNull { i, pip ->
            val modelCard = labels.getOrNull(pip.classIndex) ?: return@mapIndexedNotNull null
            val match = features[i]?.let { f -> memory?.match(f) }
            val d = decide(modelCard, pip.score, match)
            val index = labels.indexOf(d.card).takeIf { it >= 0 } ?: pip.classIndex
            pip.copy(classIndex = index, score = d.score)
        }
        val kept = ArrayList<PipDetection>()
        for (p in refined.sortedByDescending { it.score }) {
            if (p.score < minScore) continue
            if (kept.none { it.box.iou(p.box) > duplicateIou }) kept += p
        }
        return kept
    }

    /** Two boxes this much overlapped are the same symbol, whatever card each claims to be. */
    const val DUPLICATE_IOU = 0.6f
}
