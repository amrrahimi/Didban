package com.pokervision.core.domain.vision

import kotlin.math.ceil

/**
 * Splits a photo into overlapping square tiles. Each tile is sent to the 640-pixel model on its own,
 * so a card's corner symbol keeps far more pixels than when the whole photo is shrunk at once.
 * The overlap guarantees that a symbol cut by one tile border is whole in a neighbouring tile.
 */
object TilePlan {

    fun tiles(width: Int, height: Int, tile: Int = DEFAULT_TILE, overlap: Int = DEFAULT_OVERLAP): List<CropRect> {
        require(width > 0 && height > 0) { "Image size must be positive" }
        require(tile > 0 && overlap in 0 until tile) { "Need tile > overlap >= 0" }
        val xs = positions(width, tile, overlap)
        val ys = positions(height, tile, overlap)
        return ys.flatMap { y -> xs.map { x -> CropRect(x, y, minOf(tile, width), minOf(tile, height)) } }
    }

    private fun positions(size: Int, tile: Int, overlap: Int): List<Int> {
        if (size <= tile) return listOf(0)
        val n = ceil((size - tile).toDouble() / (tile - overlap)).toInt() + 1
        val step = (size - tile).toDouble() / (n - 1)
        return List(n) { (it * step).toInt() }
    }

    /** Photos are scaled so their long side is this before tiling. */
    const val SCAN_LONG_SIDE = 2048
    const val DEFAULT_TILE = 1024
    const val DEFAULT_OVERLAP = 256
}

/**
 * With a single photo there is no multi-frame evidence, so a card is accepted automatically only when
 * both corners agree with a reasonable score, or one corner is very clear. Everything else is shown
 * as "uncertain" for the user to accept with one tap — never silently used.
 */
object ScanAcceptance {
    const val TWO_CORNER_MIN_SCORE = 0.45f
    const val ONE_CORNER_MIN_SCORE = 0.70f

    fun isConfident(s: CardSighting): Boolean =
        (s.pipCount >= 2 && s.score >= TWO_CORNER_MIN_SCORE) || s.score >= ONE_CORNER_MIN_SCORE
}
