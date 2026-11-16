package com.pokervision.shared

import com.pokervision.core.domain.vision.CardLabels
import com.pokervision.core.domain.vision.CardMemory
import com.pokervision.core.domain.vision.CropRect
import com.pokervision.core.domain.vision.FocusRegion
import com.pokervision.core.domain.vision.GlyphFeature
import com.pokervision.core.domain.vision.GlyphFeatures
import com.pokervision.core.domain.vision.Letterbox
import com.pokervision.core.domain.vision.MemoryRecognizer
import com.pokervision.core.domain.vision.PipClusterer
import com.pokervision.core.domain.vision.PipDetection
import com.pokervision.core.domain.vision.ScanAcceptance
import com.pokervision.core.domain.vision.TilePlan
import com.pokervision.core.domain.vision.YoloDecoder
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.card.Rank
import com.pokervision.core.model.detection.BoundingBox
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/** A card as the iPhone screens show it. */
class CardView(val code: String, val rank: String, val suit: String, val isRed: Boolean)

internal fun PlayingCard.view() =
    CardView(code, if (rank == Rank.TEN) "10" else rank.symbol.toString(), suit.glyph.toString(), suit.isRed)

/** A box normalized to the photo (0..1). */
class BoxView(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal fun BoundingBox.view() = BoxView(left, top, right, bottom)

/** Pixel rectangle of the photo given to the model in one pass. */
class RegionView(val left: Int, val top: Int, val width: Int, val height: Int)

/** Where the region is drawn inside the 640x640 model input (the rest is filled with gray 114). */
class LetterboxView(val size: Int, val padX: Int, val padY: Int, val scaledWidth: Int, val scaledHeight: Int)

/** Glyph patch size the app must cut out for each proposal (pixels). */
object GlyphSize {
    val width: Int = GlyphFeatures.WIDTH
    val height: Int = GlyphFeatures.HEIGHT
}

/**
 * Card looks the user has taught, saved in the app's private folder. Thread-safe: photo scans run in
 * the background while corrections happen on the main thread.
 */
class MemoryStore(private val path: String) {
    private val lock = NSLock()
    private var memory: CardMemory = load()

    private fun load(): CardMemory {
        val data = NSData.dataWithContentsOfFile(path) ?: return CardMemory()
        return CardMemory.fromBytes(data.toByteArray())
    }

    internal fun <T> locked(block: (CardMemory) -> T): T {
        lock.lock()
        try {
            return block(memory)
        } finally {
            lock.unlock()
        }
    }

    private fun change(block: (CardMemory) -> Unit) {
        lock.lock()
        try {
            block(memory)
            memory.toBytes().toNSData().writeToFile(path, atomically = true)
        } finally {
            lock.unlock()
        }
    }

    val exampleCount: Int get() = locked { it.exampleCount }

    internal fun learn(card: PlayingCard, features: List<GlyphFeature>) {
        if (features.isNotEmpty()) change { m -> features.forEach { m.learn(card, it) } }
    }

    internal fun correct(wrong: PlayingCard, right: PlayingCard, features: List<GlyphFeature>) {
        if (features.isNotEmpty()) change { m ->
            features.forEach {
                m.unlearn(wrong, it)
                m.learn(right, it)
            }
        }
    }

    internal fun unlearn(card: PlayingCard, features: List<GlyphFeature>) {
        if (features.isNotEmpty()) change { m -> features.forEach { m.unlearn(card, it) } }
    }

    fun forgetAll() {
        lock.lock()
        try {
            memory = CardMemory()
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        } finally {
            lock.unlock()
        }
    }
}

/** One card read from the photo. */
class ReadCard internal constructor(
    internal val card: PlayingCard,
    val box: BoxView,
    val score: Float,
    val confident: Boolean,
    val fromMemory: Boolean,
    internal val features: List<GlyphFeature>,
) {
    val view: CardView get() = card.view()
}

class ScanReading internal constructor(
    val cards: List<ReadCard>,
    internal val typicalPip: Pair<Float, Float>?,
    internal val proposals: List<BoundingBox>,
    val millis: Long,
)

/**
 * Reads one photo (same steps as the Android app): the model runs on the whole photo and on
 * overlapping tiles ([regions]); its corner-symbol proposals are merged, every symbol is cut out by
 * the app ([proposals] / [setGlyph]) and compared with what the user taught, then grouped into cards.
 * Use from one background thread.
 */
class PhotoScan(private val photoWidth: Int, private val photoHeight: Int, labelCodes: List<String>) {
    private val labels: List<PlayingCard> =
        runCatching { CardLabels.parse(labelCodes) }.getOrElse { CardLabels.parse(CardLabels.DEFAULT_ORDER) }
    private val regions: List<CropRect> = buildList {
        add(CropRect(0, 0, photoWidth, photoHeight))
        val tiles = TilePlan.tiles(photoWidth, photoHeight)
        if (tiles.size > 1) addAll(tiles)
    }
    private val raw = ArrayList<PipDetection>()
    private var merged: List<PipDetection>? = null
    private var glyphs: Array<GlyphFeature?> = emptyArray()

    fun regions(): List<RegionView> = regions.map { RegionView(it.left, it.top, it.width, it.height) }

    fun letterbox(regionIndex: Int): LetterboxView {
        val r = regions[regionIndex]
        val lb = Letterbox(r.width, r.height, INPUT_SIZE)
        return LetterboxView(INPUT_SIZE, lb.padX, lb.padY, lb.scaledWidth, lb.scaledHeight)
    }

    /** Model output for one region: float32 [1, 4 + classes, anchors]. */
    fun addOutput(regionIndex: Int, output: NSData) {
        val r = regions[regionIndex]
        val flat = output.toFloatArray()
        val channels = 4 + labels.size
        if (flat.isEmpty() || flat.size % channels != 0) return
        val pips = YoloDecoder.decode(flat, labels.size, flat.size / channels, PROPOSAL_THRESHOLD, Letterbox(r.width, r.height, INPUT_SIZE))
        if (regionIndex == 0) raw += pips
        else pips.mapTo(raw) { it.copy(box = FocusRegion.toFrame(it.box, r, photoWidth, photoHeight)) }
        merged = null
    }

    private fun merged(): List<PipDetection> =
        merged ?: YoloDecoder.nms(raw, YoloDecoder.DEFAULT_IOU_THRESHOLD).also {
            merged = it
            glyphs = arrayOfNulls(it.size)
        }

    /** Symbol boxes to cut out (normalized); give each one's pixels to [setGlyph]. */
    fun proposals(): List<BoxView> = merged().map { it.box.view() }

    /** RGBX pixels of proposal [index], already resized to [GlyphSize]. */
    fun setGlyph(index: Int, rgbx: NSData) {
        merged()
        if (index !in glyphs.indices) return
        val px = rgbx.rgbxToArgb()
        if (px.size == GlyphFeatures.SIZE) glyphs[index] = GlyphFeatures.from(px)
    }

    fun finish(store: MemoryStore?, millis: Long): ScanReading {
        val pips = merged()
        if (pips.isEmpty()) return ScanReading(emptyList(), null, emptyList(), millis)
        val features = glyphs.toList()
        val refined = store?.locked { m ->
            MemoryRecognizer.refine(pips, features, labels, m.takeIf { it.exampleCount > 0 }, MIN_SCORE)
        } ?: MemoryRecognizer.refine(pips, features, labels, null, MIN_SCORE)
        val featureByBox = HashMap<BoundingBox, GlyphFeature>()
        val originalByBox = HashMap<BoundingBox, Int>()
        pips.forEachIndexed { i, p ->
            features[i]?.let { featureByBox[p.box] = it }
            originalByBox[p.box] = p.classIndex
        }
        val cards = PipClusterer.cluster(refined, labels).map { s ->
            val changed = s.pipBoxes.any { b -> originalByBox[b]?.let { labels.getOrNull(it) } != s.card }
            ReadCard(
                card = s.card,
                box = s.box.view(),
                score = s.score,
                confident = ScanAcceptance.isConfident(s),
                fromMemory = changed || s.score >= MemoryRecognizer.TRUSTED_SCORE,
                features = s.pipBoxes.mapNotNull(featureByBox::get),
            )
        }
        val typical = if (refined.isEmpty()) null else {
            val ws = refined.map { it.box.width }.sorted()
            val hs = refined.map { it.box.height }.sorted()
            ws[ws.size / 2] to hs[hs.size / 2]
        }
        return ScanReading(cards, typical, pips.map { it.box }, millis)
    }

    companion object {
        const val INPUT_SIZE = 640
        const val PROPOSAL_THRESHOLD = 0.12f
        const val MIN_SCORE = 0.3f

        /** Box to cut out around a tapped point (normalized): a nearby proposal or the typical symbol size. */
        fun tapBox(reading: ScanReading?, x: Float, y: Float, photoAspect: Float): BoxView {
            val (w, h) = reading?.typicalPip ?: (0.06f * GlyphFeatures.WIDTH / GlyphFeatures.HEIGHT / photoAspect to 0.06f)
            val near = reading?.proposals
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
            if (near != null) return near.view()
            return BoxView(
                (x - w / 2).coerceIn(0f, 1f), (y - h / 2).coerceIn(0f, 1f),
                (x + w / 2).coerceIn(0f, 1f), (y + h / 2).coerceIn(0f, 1f),
            )
        }

        /** Glyph feature from RGBX pixels already resized to [GlyphSize] (null if blank). */
        internal fun glyph(rgbx: NSData): GlyphFeature? {
            val px = rgbx.rgbxToArgb()
            return if (px.size == GlyphFeatures.SIZE) GlyphFeatures.from(px) else null
        }
    }
}
