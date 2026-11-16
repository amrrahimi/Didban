package com.pokervision.core.vision

import android.content.Context
import android.graphics.Bitmap
import com.pokervision.core.domain.repository.LearnedCardsRepository
import com.pokervision.core.domain.vision.CardMemory
import com.pokervision.core.domain.vision.CardSighting
import com.pokervision.core.domain.vision.GlyphFeature
import com.pokervision.core.domain.vision.GlyphFeatures
import com.pokervision.core.domain.vision.MemoryRecognizer
import com.pokervision.core.domain.vision.PipClusterer
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.detection.BoundingBox
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Cuts a corner symbol out of an image and turns it into a [GlyphFeature]. */
object GlyphCropper {
    private const val PAD = 0.1f
    private const val MIN_PX = 6

    fun feature(image: Bitmap, box: BoundingBox): GlyphFeature? {
        val w = image.width
        val h = image.height
        val left = ((box.left - box.width * PAD) * w).roundToInt().coerceIn(0, w - 1)
        val top = ((box.top - box.height * PAD) * h).roundToInt().coerceIn(0, h - 1)
        val right = ((box.right + box.width * PAD) * w).roundToInt().coerceIn(left + 1, w)
        val bottom = ((box.bottom + box.height * PAD) * h).roundToInt().coerceIn(top + 1, h)
        if (right - left < MIN_PX || bottom - top < MIN_PX) return null
        val crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        val scaled = Bitmap.createScaledBitmap(crop, GlyphFeatures.WIDTH, GlyphFeatures.HEIGHT, true)
        return try {
            val px = IntArray(GlyphFeatures.SIZE)
            scaled.getPixels(px, 0, GlyphFeatures.WIDTH, 0, 0, GlyphFeatures.WIDTH, GlyphFeatures.HEIGHT)
            GlyphFeatures.from(px)
        } finally {
            if (scaled !== crop && scaled !== image) scaled.recycle()
            if (crop !== image) crop.recycle()
        }
    }
}

/** Keeps the taught card looks in a private file on the phone (never leaves the device). */
@Singleton
class CardMemoryStore @Inject constructor(
    @ApplicationContext context: Context,
) : LearnedCardsRepository {
    private val file = File(context.filesDir, FILE_NAME)
    private var memory: CardMemory? = null

    private val _examples = MutableStateFlow(0)

    /** How many symbol examples are remembered (0 = nothing learned yet). */
    val examples: StateFlow<Int> = _examples.asStateFlow()

    @Synchronized
    private fun loaded(): CardMemory {
        val m = memory ?: (if (file.exists()) CardMemory.fromBytes(file.readBytes()) else CardMemory()).also { memory = it }
        _examples.value = m.exampleCount
        return m
    }

    @Synchronized
    fun <T> read(block: (CardMemory) -> T): T = block(loaded())

    @Synchronized
    fun change(block: (CardMemory) -> Unit) {
        val m = loaded()
        block(m)
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeBytes(m.toBytes())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        _examples.value = m.exampleCount
    }

    @Synchronized
    fun clearNow() {
        memory = CardMemory()
        file.delete()
        _examples.value = 0
    }

    override suspend fun clear() = clearNow()

    /** Loads the count for the UI. */
    fun refresh() {
        read { }
    }

    private companion object {
        const val FILE_NAME = "card_memory.bin"
    }
}

/** A card read from an image together with the looks of its corner symbols (for learning). */
data class LearnedSighting(
    val sighting: CardSighting,
    val features: List<GlyphFeature>,
    /** True when the user's examples decided or confirmed this reading. */
    val fromMemory: Boolean,
) {
    val card: PlayingCard get() = sighting.card
}

data class LearnedScan(
    val cards: List<LearnedSighting>,
    val inferenceMillis: Long,
    /** Typical size of a corner symbol in this image, to cut out a symbol the user taps. */
    val typicalPip: Pair<Float, Float>?,
    /** Every symbol box the model proposed, even ones too weak to read (for tap-to-teach). */
    val proposals: List<BoundingBox> = emptyList(),
)

/**
 * Card reading that improves with use: the model proposes corner symbols (even weak ones), each
 * symbol is compared with the examples the user confirmed or corrected, and clear matches win.
 */
@Singleton
class LearningCardReader @Inject constructor(
    private val detector: CardDetector,
    private val store: CardMemoryStore,
) {
    val examples: StateFlow<Int> get() = store.examples

    fun prepare(): DetectorStatus {
        store.refresh()
        return detector.prepare()
    }

    /** Photo scan (full image + tiles). */
    fun scanPhoto(photo: Bitmap, minScore: Float): LearnedScan =
        read(photo, detector.photoPips(photo, proposalThreshold(minScore)), minScore)

    /** One image, no tiling (live monitor frames). */
    fun detect(image: Bitmap, minScore: Float): LearnedScan =
        read(image, detector.framePips(image, proposalThreshold(minScore)), minScore)

    private fun proposalThreshold(minScore: Float) = minOf(PROPOSAL_THRESHOLD, minScore)

    private fun read(image: Bitmap, scan: PipScan, minScore: Float): LearnedScan {
        if (scan.pips.isEmpty()) return LearnedScan(emptyList(), scan.inferenceMillis, null)
        val proposals = scan.pips.map { it.box }
        val features = scan.pips.map { GlyphCropper.feature(image, it.box) }
        val refined = store.read { memory ->
            MemoryRecognizer.refine(scan.pips, features, scan.labels, memory.takeIf { it.exampleCount > 0 }, minScore)
        }
        val featureByBox = HashMap<BoundingBox, GlyphFeature>()
        val originalByBox = HashMap<BoundingBox, Int>()
        scan.pips.forEachIndexed { i, p ->
            features[i]?.let { featureByBox[p.box] = it }
            originalByBox[p.box] = p.classIndex
        }
        val cards = PipClusterer.cluster(refined, scan.labels).map { s ->
            val trusted = s.score >= MemoryRecognizer.TRUSTED_SCORE
            val changed = s.pipBoxes.any { b -> originalByBox[b]?.let { scan.labels.getOrNull(it) } != s.card }
            LearnedSighting(s, s.pipBoxes.mapNotNull(featureByBox::get), fromMemory = trusted || changed)
        }
        val sizes = refined.map { it.box }
        val typical = if (sizes.isEmpty()) null else {
            val ws = sizes.map { it.width }.sorted()
            val hs = sizes.map { it.height }.sorted()
            ws[ws.size / 2] to hs[hs.size / 2]
        }
        return LearnedScan(cards, scan.inferenceMillis, typical, proposals)
    }

    /** The user confirmed these symbols show [card]. */
    fun learn(card: PlayingCard, features: List<GlyphFeature>) {
        if (features.isEmpty()) return
        store.change { m -> features.forEach { m.learn(card, it) } }
    }

    /** The user said symbols read as [wrong] are really [right]. */
    fun correct(wrong: PlayingCard, right: PlayingCard, features: List<GlyphFeature>) {
        if (features.isEmpty()) return
        store.change { m ->
            features.forEach {
                m.unlearn(wrong, it)
                m.learn(right, it)
            }
        }
    }

    /**
     * Cuts out the symbol the user tapped (normalized point). Prefers a box the model proposed
     * there, so the cut matches how symbols will be cut in later scans; otherwise centres a box of
     * the typical symbol size on the tap.
     */
    fun featureAround(image: Bitmap, x: Float, y: Float, scan: LearnedScan?): GlyphFeature? {
        val aspect = image.width.toFloat() / image.height
        val (w, h) = scan?.typicalPip ?: (DEFAULT_PIP_HEIGHT * GlyphFeatures.WIDTH / GlyphFeatures.HEIGHT / aspect to DEFAULT_PIP_HEIGHT)
        val near = scan?.proposals
            ?.filter { b ->
                val cx = (b.left + b.right) / 2
                val cy = (b.top + b.bottom) / 2
                kotlin.math.abs(cx - x) <= maxOf(b.width, w) && kotlin.math.abs(cy - y) <= maxOf(b.height, h)
            }
            ?.minByOrNull { b ->
                val dx = (b.left + b.right) / 2 - x
                val dy = (b.top + b.bottom) / 2 - y
                dx * dx + dy * dy
            }
        if (near != null) GlyphCropper.feature(image, near)?.let { return it }
        val box = BoundingBox(
            (x - w / 2).coerceIn(0f, 1f), (y - h / 2).coerceIn(0f, 1f),
            (x + w / 2).coerceIn(0f, 1f), (y + h / 2).coerceIn(0f, 1f),
        )
        return GlyphCropper.feature(image, box)
    }

    /** The user said this symbol is not [card]. */
    fun unlearnOnly(card: PlayingCard, feature: GlyphFeature) {
        store.change { it.unlearn(card, feature) }
    }

    fun forgetAll() = store.clearNow()

    companion object {
        /** The model is asked for weak proposals; only memory can lift them above the acceptance bar. */
        const val PROPOSAL_THRESHOLD = 0.12f
        private const val DEFAULT_PIP_HEIGHT = 0.06f
    }
}
