package com.pokervision.shared

import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Copies between Foundation's NSData and Kotlin arrays (bulk copies, no per-element bridging). */
internal fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/** Model output: native-endian 32-bit floats. */
internal fun NSData.toFloatArray(): FloatArray {
    val n = (length / 4u).toInt()
    if (n == 0) return FloatArray(0)
    val p = bytes!!.reinterpret<FloatVar>()
    return FloatArray(n) { p[it] }
}

/** RGBX bytes (4 per pixel, red first) to ARGB ints. */
internal fun NSData.rgbxToArgb(): IntArray {
    val n = (length / 4u).toInt()
    if (n == 0) return IntArray(0)
    val p = bytes!!.reinterpret<UByteVar>()
    return IntArray(n) { i ->
        val r = p[4 * i].toInt()
        val g = p[4 * i + 1].toInt()
        val b = p[4 * i + 2].toInt()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
