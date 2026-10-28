package com.fourgeailabs.prismtune.data

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * 1.2.2 Now Playing colours from album art, not from a song
 * title hash. The user corrected the 1.2.0 approach: the accent
 * must come from the current album artwork's own palette.
 *
 * [dominantColorFromPixels] is pure and unit-testable: it
 * quantizes pixels, counts them, and picks the most frequent
 * vibrant colour, ignoring near-black, near-white, and grey
 * pixels (which would otherwise make every album amber/grey).
 * If nothing vibrant is found, it falls back to the most
 * frequent colour overall, so the result is never invented —
 * it is always a colour actually in the artwork.
 */
/**
 * 1.5.0 PALETTE FIX: the old picker quantized to 16 levels
 * and then, when no pixel passed the "vibrant" test, fell
 * back to the most frequent colour OVERALL — which for a
 * photo-heavy cover is usually a muddy mid-brown average
 * (skin, shadows, backgrounds averaged together), even
 * when the artwork contains no brown-red at all. Eric,
 * 2026-10-27: "brownish-red background when the album art
 * does not have that shade whatsoever". New rule: (1)
 * quantize finer (32 levels) so distinct hues do not
 * collapse into one brown bucket; (2) NEVER fall back to
 * the overall most-frequent colour — if no vibrant colour
 * exists, return null and the caller keeps the chosen
 * palette, instead of inventing a brown; (3) require a
 * real saturation AND a minimum share (at least 2% of
 * sampled pixels) so a handful of stray pixels cannot
 * win. A colour returned here is actually dominant in
 * the artwork, not an average of it.
 */
fun dominantColorFromPixels(pixels: IntArray): Color? {
    if (pixels.isEmpty()) return null
    val vibrantCounts = mutableMapOf<Int, Int>()
    var vibrantTotal = 0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val saturation = if (max == 0) 0f else (max - min).toFloat() / max
        // Finer quantization: 32 levels, not 16, so reds,
        // oranges and browns stay separate buckets.
        if (saturation > 0.35f && max in 50..255) {
            val q = ((r / 32) shl 16) or ((g / 32) shl 8) or (b / 32)
            vibrantCounts[q] = (vibrantCounts[q] ?: 0) + 1
            vibrantTotal++
        }
    }
    if (vibrantCounts.isEmpty() || vibrantTotal == 0) return null
    val best = vibrantCounts.maxByOrNull { it.value } ?: return null
    // Must be at least 2% of vibrant pixels, not a stray few.
    if (best.value.toFloat() / vibrantTotal < 0.02f) return null
    val qr = (best.key shr 16) and 0xFF
    val qg = (best.key shr 8) and 0xFF
    val qb = best.key and 0xFF
    return Color(qr * 32 + 16, qg * 32 + 16, qb * 32 + 16)
}

fun mixColors(base: Color, tint: Color, tintWeight: Float): Color {
    val w = tintWeight.coerceIn(0f, 1f)
    return Color(
        (base.red * (1 - w) + tint.red * w),
        (base.green * (1 - w) + tint.green * w),
        (base.blue * (1 - w) + tint.blue * w),
        1f,
    )
}

fun lightenColor(color: Color, factor: Float = 1.4f): Color {
    return Color(
        (color.red * factor).coerceIn(0f, 1f),
        (color.green * factor).coerceIn(0f, 1f),
        (color.blue * factor).coerceIn(0f, 1f),
        1f,
    )
}

fun darkenColor(color: Color, factor: Float = 0.8f): Color {
    return Color(
        (color.red * factor).coerceIn(0f, 1f),
        (color.green * factor).coerceIn(0f, 1f),
        (color.blue * factor).coerceIn(0f, 1f),
        1f,
    )
}
