package com.pokervision.core.common.timing

/**
 * Decides whether a camera/video frame should be processed so analysis runs at a
 * configurable rate (e.g. 5 FPS) instead of the sensor rate (typically 30 FPS).
 *
 * Timestamps are monotonic nanoseconds (CameraX `ImageInfo.timestamp` or `System.nanoTime()`).
 * Not thread-safe: call from the single analyzer thread.
 */
class FrameRateLimiter(
    targetFps: Double,
    /**
     * Fraction of the interval tolerated as early arrival. Sensor timestamps jitter, so a strict
     * comparison would systematically skip one extra frame (30 FPS -> 5 FPS would yield ~4.3 FPS).
     */
    private val earlyToleranceFraction: Double = 0.05,
) {

    var targetFps: Double = targetFps
        set(value) {
            require(value > 0.0 && value.isFinite()) { "targetFps must be > 0, was $value" }
            field = value
        }

    private var lastAcceptedNanos: Long? = null

    init {
        require(earlyToleranceFraction in 0.0..0.5) { "earlyToleranceFraction must be in [0, 0.5]" }
        this.targetFps = targetFps
    }

    private val minIntervalNanos: Long
        get() = (1_000_000_000.0 / targetFps * (1.0 - earlyToleranceFraction)).toLong()

    /** Returns true if the frame at [timestampNanos] should be processed, and records it as accepted. */
    fun shouldProcess(timestampNanos: Long): Boolean {
        val last = lastAcceptedNanos
        if (last == null || timestampNanos < last || timestampNanos - last >= minIntervalNanos) {
            // timestampNanos < last means the clock source was reset (e.g. camera rebound): accept.
            lastAcceptedNanos = timestampNanos
            return true
        }
        return false
    }

    fun reset() {
        lastAcceptedNanos = null
    }
}

/**
 * Rolling throughput meter: reports frames per second over a sliding window.
 * Used for on-screen diagnostics and on-device benchmarking.
 */
class ThroughputMeter(private val windowNanos: Long = 2_000_000_000L) {
    private val timestamps = ArrayDeque<Long>()

    init {
        require(windowNanos > 0) { "windowNanos must be > 0" }
    }

    fun record(timestampNanos: Long) {
        timestamps.addLast(timestampNanos)
        while (timestamps.isNotEmpty() && timestampNanos - timestamps.first() > windowNanos) {
            timestamps.removeFirst()
        }
    }

    /** Frames per second over the current window, or 0.0 when fewer than two samples exist. */
    fun fps(): Double {
        if (timestamps.size < 2) return 0.0
        val span = timestamps.last() - timestamps.first()
        if (span <= 0) return 0.0
        return (timestamps.size - 1) * 1_000_000_000.0 / span
    }

    fun reset() = timestamps.clear()
}
