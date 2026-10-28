package com.fourgeailabs.prismtune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

/**
 * 1.2.0 colour palettes. The app was dark-only (charcoal + amber);
 * Eric asked for brighter, matte colourways, a Settings picker,
 * and a Now Playing mode where the accent follows the current
 * track. Palettes are matte: flat surfaces, no gradient wash.
 *
 * Implementation: palette colours are read through getters that
 * subscribe to a Compose State, so changing the palette in
 * MainActivity recomposes the whole UI with the new colours.
 * Existing code that reads PrismAmber / PrismSurface /
 * PrismOnSurface keeps working unchanged.
 */
data class PrismPalette(
    val name: String,
    val accent: Color,
    val accentDeep: Color,
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val secondary: Color,
    val isLight: Boolean,
)

val PrismPalettes: List<PrismPalette> = listOf(
    PrismPalette(
        "Amber", Color(0xFFFFB020), Color(0xFFE8890C),
        Color(0xFF14100C), Color(0xFF1E1913), Color(0xFFF5EFE6), Color(0xFFD8CFC0), false,
    ),
    PrismPalette(
        "Cream", Color(0xFFFF8C00), Color(0xFFE07B00),
        Color(0xFFFFF8EC), Color(0xFFFFFFFF), Color(0xFF1E1913), Color(0xFF5A4A3A), true,
    ),
    PrismPalette(
        "Sky", Color(0xFF0090FF), Color(0xFF0070CC),
        Color(0xFFEAF4FF), Color(0xFFFFFFFF), Color(0xFF12202E), Color(0xFF4A5A6A), true,
    ),
    PrismPalette(
        "Mint", Color(0xFF00A86B), Color(0xFF008855),
        Color(0xFFEAF7EE), Color(0xFFFFFFFF), Color(0xFF12241A), Color(0xFF4A5A4E), true,
    ),
    PrismPalette(
        "Berry", Color(0xFFD81B60), Color(0xFFB0154F),
        Color(0xFFFDF0F5), Color(0xFFFFFFFF), Color(0xFF2A1220), Color(0xFF5A4250), true,
    ),
)

fun paletteByName(name: String): PrismPalette =
    PrismPalettes.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PrismPalettes[0]

/**
 * 1.5.7 "Keep current colours": a palette frozen to text so
 * it survives restarts exactly as it looks right now. Format
 * is pipe-separated: name | accent | accentDeep |
 * background | surface | onSurface | secondary |
 * isLight, colours as 8-digit ARGB hex. Pure, so unit
 * tests cover it without Android. decodePalette returns
 * null for ANY malformed input — a corrupt prefs value
 * must fall back to a built-in palette, never crash boot.
 */
fun encodePalette(p: PrismPalette): String {
    fun hex(c: Color): String = c.toArgb().toUInt().toString(16).padStart(8, '0')
    return listOf(
        p.name, hex(p.accent), hex(p.accentDeep), hex(p.background),
        hex(p.surface), hex(p.onSurface), hex(p.secondary), p.isLight.toString(),
    ).joinToString("|")
}

fun decodePalette(encoded: String?): PrismPalette? {
    if (encoded.isNullOrBlank()) return null
    return try {
        val parts = encoded.split("|")
        if (parts.size != 8) return null
        fun color(hex: String): Color {
            val v = hex.toULong(16)
            return Color(v.toLong())
        }
        PrismPalette(
            name = parts[0].ifBlank { return null },
            accent = color(parts[1]),
            accentDeep = color(parts[2]),
            background = color(parts[3]),
            surface = color(parts[4]),
            onSurface = color(parts[5]),
            secondary = color(parts[6]),
            isLight = parts[7].toBooleanStrict(),
        )
    } catch (_: Exception) {
        null
    }
}

/** The palette name used for a kept snapshot. */
const val KEPT_PALETTE_NAME = "Kept"

object PrismPaletteState {
    var current by mutableStateOf(PrismPalettes[0])
}

/** Backwards-compatible colour accessors, now palette-driven. */
val PrismAmber: Color get() = PrismPaletteState.current.accent
val PrismAmberDeep: Color get() = PrismPaletteState.current.accentDeep
val PrismCharcoal: Color get() = PrismPaletteState.current.background
val PrismSurface: Color get() = PrismPaletteState.current.surface
val PrismOnSurface: Color get() = PrismPaletteState.current.onSurface
val PrismSecondary: Color get() = PrismPaletteState.current.secondary

/**
 * 1.5.2 LIGHT-ON-DARK CLICKABLES — Eric, 2026-10-27
 * 02:52, photo of Now Playing on a dark blue background:
 * the off-state buttons (shuffle off, repeat off, prev,
 * next) were dark blue/grey on dark blue — nearly
 * invisible — and the top nav icons were dim blue on
 * dark blue. Rule from now on: on a DARK background,
 * every clickable and every secondary label uses a
 * LIGHT colour, never a dark grey or a dark blue.
 * - off-state icon: light grey-white (0xFFD8D8E0),
 *   clearly visible but still distinct from the amber
 *   on-state, so off vs on is still readable at a glance.
 * - secondary text on dark: light warm grey, not the
 *   palette's mid grey, which washes out on dark surfaces.
 * On a LIGHT background the palette's own secondary is
 * already dark enough, so it is kept unchanged.
 */
val PrismOffIcon: Color get() = if (PrismPaletteState.current.isLight) Color(0xFF6A6A6A) else Color(0xFFD8D8E0)
/**
 * Accent guaranteed visible on the current background: if the
 * accent's luminance is low (a dark artwork colour on a dark
 * background, e.g. Curtis blue on dark blue in Eric's photo),
 * use white instead — a dark accent on a dark background is
 * invisible no matter how "on brand" it is. On light
 * backgrounds the accent is kept as-is.
 */
val PrismAccentVisible: Color get() {
    val a = PrismPaletteState.current.accent
    val lum = 0.2126f * a.red + 0.7152f * a.green + 0.0722f * a.blue
    return if (!PrismPaletteState.current.isLight && lum < 0.35f) Color.White else a
}

val PrismSecondaryVisible: Color get() = if (PrismPaletteState.current.isLight) PrismPaletteState.current.secondary else Color(0xFFE8E0D0)


/**
 * 1.2.0 Now Playing dynamic accent: deterministic bright matte
 * colour from a track's title + artist. Same track = same
 * colour, neighbours usually differ, never black/white/grey.
 * Pure, unit-testable.
 */
fun dynamicAccentForTrack(title: String, artist: String): Color {
    // Pure HSV->RGB, no android.graphics call, so unit tests
    // can run on the JVM without mocked Android classes.
    val hash = (title + "|" + artist).hashCode()
    val hue = (((hash % 360) + 360) % 360).toFloat()
    val s = 0.72f
    val v = 0.88f
    val c = v * s
    val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
    val m = v - c
    val (r, g, b) = when {
        hue < 60 -> Triple(c, x, 0f)
        hue < 120 -> Triple(x, c, 0f)
        hue < 180 -> Triple(0f, c, x)
        hue < 240 -> Triple(0f, x, c)
        hue < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        ((r + m) * 255).roundToInt(),
        ((g + m) * 255).roundToInt(),
        ((b + m) * 255).roundToInt(),
    )
}

/**
 * 1.5.1 WAVEFORM COLOURS — SUPERSEDED in 1.5.5 for the
 * played colour only (see progressColor below): Eric
 * corrected his 02:16 "complimentary colour" instruction
 * on 2026-10-27 05:38, because the complement clashed.
 * Unplayed white/black is unchanged.
 * Original 1.5.1 note:
 * "The wave form needs to start as a white wave form for
 * dark backgrounds and a black wave form for light
 * backgrounds and become a complimentary color of the
 * back ground as the song progresses. The wave form is
 * supposed to be a progress bar too. Not just wave form."
 * So the waveform is BOTH: bar HEIGHT is the live
 * spectrum (bass left, treble right), bar COLOUR is
 * progress. Unplayed bars: white on a dark background,
 * black on a light background — maximum contrast, always
 * readable. Played bars: blend from that base colour
 * toward the complementary colour of the background as
 * the song progresses — at 0% the played colour is the
 * base, at 100% it is the full complement, so the colour
 * itself tells you how far through the song you are.
 * "Complementary" here is the hue opposite on the colour
 * wheel (hue + 180 degrees), computed from the actual
 * background colour, never a fixed accent — so it works
 * on Amber, Cream, Sky, Mint, Berry, and Now Playing
 * artwork-tinted surfaces alike. Pure, unit-testable.
 */
fun waveformBaseColor(isLightBackground: Boolean): Color =
    if (isLightBackground) Color.Black else Color.White

fun complementaryColor(color: Color): Color {
    // RETIRED in 1.5.5 — DO NOT USE for the waveform.
    // Eric, 2026-10-27 05:38, with a car photo: the played
    // bars were light blue on a deep-red screen and clashed
    // badly, and progress was hard to read at a glance.
    // That light blue WAS this function's output: the
    // waveform was coloured from PrismCharcoal's complement
    // (brown -> blue/cyan), not from the red artwork
    // background actually on screen, and a hue-opposite
    // colour is by definition the one colour that clashes
    // most with the background. Kept only as a record;
    // waveformBarColor() now uses progressColor() below.
    // 1.5.3 BUG FIX — Eric, 2026-10-27 04:28: "The wave
    // form doesn't change colors as the song progresses."
    // The old version kept the background's own saturation
    // AND value, so the complement of a DARK background was
    // itself dark: complement of dark brown was dark
    // blue-grey, complement of dark teal was dark red.
    // Blending white toward a dark colour is not a colour
    // change you can see — it is just white fading to
    // grey, then to near-invisible dark, on a dark
    // background. That is why the waveform looked like it
    // never changed colour. Fixed: hue is still rotated
    // 180 degrees (the true complement), but saturation is
    // boosted to at least 0.8 and value is forced to full
    // brightness, so the complement is always a VIVID,
    // unmistakable colour — bright blue for a brown
    // background, bright red-orange for teal, bright
    // yellow-orange for dark blue — on dark AND light
    // backgrounds alike. For a grey/white/black background
    // (no hue at all) the fallback hue is cyan, so the
    // complement is still a real colour, not grey.
    val r = color.red; val g = color.green; val b = color.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val delta = max - min
    var hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    if (hue < 0f) hue += 360f
    val compHue = (hue + 180f) % 360f
    val s = if (max == 0f) 0f else delta / max
    val vividS = maxOf(s, 0.8f)
    val v = 1f
    val c = v * vividS
    val x = c * (1f - kotlin.math.abs((compHue / 60f) % 2f - 1f))
    val m = v - c
    val (cr, cg, cb) = when {
        compHue < 60f -> Triple(c, x, 0f)
        compHue < 120f -> Triple(x, c, 0f)
        compHue < 180f -> Triple(0f, c, x)
        compHue < 240f -> Triple(0f, x, c)
        compHue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(cr + m, cg + m, cb + m)
}

fun blendColors(base: Color, target: Color, frac: Float): Color {
    val f = frac.coerceIn(0f, 1f)
    return Color(
        red = base.red + (target.red - base.red) * f,
        green = base.green + (target.green - base.green) * f,
        blue = base.blue + (target.blue - base.blue) * f,
        alpha = 1f,
    )
}

/**
 * 1.5.5 HARMONIOUS PROGRESS COLOUR — Eric, 2026-10-27
 * 05:38, car photo (deep-red "Friends in Low Places"
 * screen): "I like the white against the red. That does
 * look clean. However the progress bar section, where
 * it's light blue, does clash quite a bit... the filled
 * section needs to complement the background colours...
 * for every other colour as well... much easier to see
 * how far along in the song we are." And his example for
 * red: a lighter red, like the pause button, or black.
 * So played bars are NO LONGER the hue-opposite
 * complement. They are the SAME hue family as the actual
 * background, shifted in lightness until they are
 * unmistakable against it AND against the unplayed base:
 * - Dark background: lighten the background hue toward
 *   a bright tint (value +0.45, clamped 0.60-0.85).
 *   Deep red -> bright lighter red. Teal -> light teal.
 *   Charcoal (no hue) -> mid-light grey, still clearly
 *   darker than the white unplayed bars.
 * - Light background: darken the background hue toward
 *   a deep shade (value -0.45, clamped 0.15-0.40), so it
 *   reads against a light background and is not the
 *   black of the unplayed bars.
 * Saturation is left alone, so a saturated background
 * gives a saturated played colour and a grey background
 * gives a grey one — never an unrelated vivid colour.
 * Pure, unit-testable. The caller MUST pass the actual
 * on-screen background (palette background), not a
 * fixed charcoal — colouring from a fixed colour was
 * half of the clash in the first place.
 */
fun progressColor(background: Color, isLightBackground: Boolean): Color {
    val r = background.red; val g = background.green; val b = background.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val delta = max - min
    var hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    if (hue < 0f) hue += 360f
    val s = if (max == 0f) 0f else delta / max
    val v = if (isLightBackground) (max - 0.45f).coerceIn(0.15f, 0.40f)
        else (max + 0.45f).coerceIn(0.60f, 0.85f)
    val c = v * s
    val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val m = v - c
    val (cr, cg, cb) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(cr + m, cg + m, cb + m)
}

/**
 * Colour for one waveform bar at position [barFrac] (0 = first
 * bar, 1 = last bar) when the song is [progressFrac] through.
 * 1.5.5 — the played colour is now progressColor(), the
 * harmonious tint/shade of the actual background, applied
 * in full immediately (same marching-right rule as 1.5.3:
 * WHICH bars are coloured carries the progress, not a
 * fade). Unplayed bars are unchanged: white on dark,
 * black on light, exactly as Eric liked in his photo.
 * Height is still the live FFT spectrum, unchanged.
 */
fun waveformBarColor(
    barFrac: Float,
    progressFrac: Float,
    isLightBackground: Boolean,
    background: Color,
): Color {
    val base = waveformBaseColor(isLightBackground)
    if (barFrac > progressFrac) return base
    return progressColor(background, isLightBackground)
}

fun darkenForDynamic(accent: Color): Color {
    val argb = accent.toArgb()
    val r = ((argb shr 16) and 0xFF) * 0.8f
    val g = ((argb shr 8) and 0xFF) * 0.8f
    val b = (argb and 0xFF) * 0.8f
    return Color(r.roundToInt(), g.roundToInt(), b.roundToInt())
}

@Composable
fun PrismTuneTheme(content: @Composable () -> Unit) {
    val palette = PrismPaletteState.current
    val scheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.accent, onPrimary = Color.White,
            secondary = palette.accentDeep, background = palette.background,
            surface = palette.surface, onBackground = palette.onSurface,
            onSurface = palette.onSurface,
        )
    } else {
        darkColorScheme(
            primary = palette.accent, onPrimary = Color.Black,
            secondary = palette.accentDeep, background = palette.background,
            surface = palette.surface, onBackground = palette.onSurface,
            onSurface = palette.onSurface,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
