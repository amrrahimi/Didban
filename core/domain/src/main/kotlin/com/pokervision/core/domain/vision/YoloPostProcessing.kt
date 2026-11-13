package com.pokervision.core.domain.vision

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.detection.BoundingBox
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Ultralytics-style letterbox: the source image is scaled to fit a square [size] input keeping its
 * aspect ratio, and centred with padding. Maps model coordinates back to the source image.
 */
data class Letterbox(val sourceWidth: Int, val sourceHeight: Int, val size: Int) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0 && size > 0) { "Sizes must be positive" }
    }

    val scale: Float = min(size.toFloat() / sourceWidth, size.toFloat() / sourceHeight)
    val scaledWidth: Int = (sourceWidth * scale).roundToInt().coerceIn(1, size)
    val scaledHeight: Int = (sourceHeight * scale).roundToInt().coerceIn(1, size)
    val padX: Int = (size - scaledWidth) / 2
    val padY: Int = (size - scaledHeight) / 2

    /** Converts a box in model input pixels (centre x/y, width, height) to normalized source coordinates. */
    fun toSourceBox(cx: Float, cy: Float, w: Float, h: Float): BoundingBox {
        fun nx(x: Float) = ((x - padX) / scale / sourceWidth).coerceIn(0f, 1f)
        fun ny(y: Float) = ((y - padY) / scale / sourceHeight).coerceIn(0f, 1f)
        return BoundingBox(nx(cx - w / 2), ny(cy - h / 2), nx(cx + w / 2), ny(cy + h / 2))
    }
}

/** One raw box from the model: a corner pip (rank + suit symbol) of a card. */
data class PipDetection(val classIndex: Int, val score: Float, val box: BoundingBox)

/**
 * Decodes YOLO (v8/11) detection output of shape [1, 4 + numClasses, numAnchors]: rows 0..3 are
 * centre-x, centre-y, width, height in input pixels; the remaining rows are per-class scores
 * (already sigmoid-activated). The export has no NMS, so class-wise NMS is applied here.
 */
object YoloDecoder {

    fun decode(
        output: FloatArray,
        numClasses: Int,
        numAnchors: Int,
        scoreThreshold: Float,
        letterbox: Letterbox,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
        maxDetections: Int = DEFAULT_MAX_DETECTIONS,
    ): List<PipDetection> {
        val channels = 4 + numClasses
        require(output.size == channels * numAnchors) {
            "Output size ${output.size} != ($channels x $numAnchors)"
        }
        val candidates = ArrayList<PipDetection>()
        for (a in 0 until numAnchors) {
            var bestClass = -1
            var bestScore = scoreThreshold
            for (c in 0 until numClasses) {
                val s = output[(4 + c) * numAnchors + a]
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestClass < 0) continue
            val cx = output[a]
            val cy = output[numAnchors + a]
            val w = output[2 * numAnchors + a]
            val h = output[3 * numAnchors + a]
            if (w <= 0f || h <= 0f) continue
            candidates += PipDetection(bestClass, bestScore, letterbox.toSourceBox(cx, cy, w, h))
        }
        return nms(candidates, iouThreshold).take(maxDetections)
    }

    /** Class-wise non-maximum suppression; result sorted by score descending. */
    fun nms(detections: List<PipDetection>, iouThreshold: Float): List<PipDetection> {
        val kept = ArrayList<PipDetection>()
        for (d in detections.sortedByDescending { it.score }) {
            if (kept.none { it.classIndex == d.classIndex && it.box.iou(d.box) > iouThreshold }) kept += d
        }
        return kept
    }

    const val DEFAULT_IOU_THRESHOLD = 0.5f
    const val DEFAULT_MAX_DETECTIONS = 100
}

/** A card seen in one frame, built from one or more of its corner pips. */
data class CardSighting(val card: PlayingCard, val score: Float, val box: BoundingBox, val pipCount: Int)

/**
 * The detector boxes corner pips (about two per card). A standard deck contains each card once,
 * so all pips of the same class belong to one card; pips far from the strongest one are treated as
 * likely false positives and ignored.
 */
object PipClusterer {

    fun cluster(
        pips: List<PipDetection>,
        labels: List<PlayingCard>,
        maxDistanceInDiagonals: Float = DEFAULT_MAX_DISTANCE_DIAGONALS,
    ): List<CardSighting> = pips.groupBy { it.classIndex }.mapNotNull { (cls, group) ->
        val card = labels.getOrNull(cls) ?: return@mapNotNull null
        val best = group.maxBy { it.score }
        val diagonal = sqrt(best.box.width * best.box.width + best.box.height * best.box.height)
        val members = group.filter { it === best || centerDistance(it.box, best.box) <= maxDistanceInDiagonals * diagonal }
        CardSighting(card, best.score, union(members.map { it.box }), members.size)
    }.sortedByDescending { it.score }

    private fun centerDistance(a: BoundingBox, b: BoundingBox): Float {
        val dx = (a.left + a.right) / 2 - (b.left + b.right) / 2
        val dy = (a.top + a.bottom) / 2 - (b.top + b.bottom) / 2
        return sqrt(dx * dx + dy * dy)
    }

    private fun union(boxes: List<BoundingBox>) = BoundingBox(
        boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom },
    )

    /** "A few box diagonals" per the model card; a pip box is small relative to the card. */
    const val DEFAULT_MAX_DISTANCE_DIAGONALS = 8f
}

/** Parses the model's class codes ("10H", "AS", "QC") into cards, in index order. */
object CardLabels {
    fun parse(codes: List<String>): List<PlayingCard> = codes.map {
        PlayingCard.parseOrNull(it) ?: throw IllegalArgumentException("Unknown card label '$it'")
    }

    /** Class order documented for sroot/lgd-cards-gen3 (also embedded in the model metadata). */
    val DEFAULT_ORDER: List<String> = listOf("10", "2", "3", "4", "5", "6", "7", "8", "9", "A", "J", "K", "Q")
        .flatMap { r -> listOf("C", "D", "H", "S").map { s -> r + s } }
}
