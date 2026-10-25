package com.fourgeailabs.prismtune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.fourgeailabs.prismtune.data.SafeAreaConfig

/**
 * THE Equinox EV rule, in one place.
 *
 * The 2025 Chevrolet Equinox EV's 17.7" infotainment display carries a
 * temperature / climate overlay in the TOP-RIGHT corner, and the screen's
 * aspect ratio is wider than a normal tablet's. So:
 *
 *  - Every tappable control lives inside [interactiveContent], which is
 *    constrained to the LEFT [safeAreaFraction] of the width (default 80%,
 *    adjustable in Settings between 55% and 85%, hard-capped at 85%).
 *    No interactive element ever sits in the top-right quadrant
 *    (x > 80%, top ~12% of height).
 *  - The remaining right strip ([ambientContent]) is DISPLAY ONLY: large
 *    artwork, now-playing text, a visualizer-style glow. It contains NO
 *    buttons, NO sliders, NO text fields — nothing a driver could tap by
 *    accident, and nothing that needs a glance at the top-right corner.
 *  - This zone is enforced SILENTLY by this layout. There is no top bar,
 *    no boundary badge, no clock, and no temperature/weather readout in
 *    the UI to explain it.
 *
 * To recalibrate from a straight-on photo of the real display, change
 * ONE value — SafeAreaConfig.DEFAULT_FRACTION — or move the Settings slider.
 * No other file needs to change. Calibrated 2026-10-25 from Eric's photo
 * (12 oz can for scale, temp at ~89% across, clock at ~83%): see README.
 */
@Composable
fun SafeDrivingLayout(
    safeAreaFraction: Float,
    modifier: Modifier = Modifier,
    interactiveContent: @Composable BoxScope.() -> Unit,
    ambientContent: @Composable BoxScope.() -> Unit,
) {
    val fraction = SafeAreaConfig.clamp(safeAreaFraction)
    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(PrismCharcoal),
            content = interactiveContent,
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(Color(0xFF0D0B08)),
            content = ambientContent,
        )
    }
}
