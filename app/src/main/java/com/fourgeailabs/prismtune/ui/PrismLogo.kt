package com.fourgeailabs.prismtune.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The PrismTune mark: the Vinyl Ring (Concept 3, v3 full redesign, picked
 * by Eric). A gold vinyl disc with concentric grooves, a charcoal centre
 * hole, and a gold centre dot. Drawn in code so the logo, the launcher
 * icon, and the splash all match. The old triangle/prism mark is gone.
 */
@Composable
fun PrismLogo(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.46f
        val centre = Offset(cx, cy)
        // Vinyl disc: amber gradient, pale gold at the top to deep amber.
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFFE9A8),
                    Color(0xFFFFC93D),
                    Color(0xFFFF9E1B),
                    Color(0xFFE8730C),
                ),
                start = Offset(cx, cy - r),
                end = Offset(cx, cy + r),
            ),
            radius = r,
            center = centre,
        )
        // Grooves.
        val grooveColor = Color(0xFFBE5F05)
        listOf(0.85f, 0.72f, 0.59f, 0.46f).forEach { frac ->
            drawCircle(
                color = grooveColor,
                radius = r * frac,
                center = centre,
                style = Stroke(width = maxOf(1f, r * 0.015f)),
            )
        }
        // Gloss highlight, upper-left facet of the disc.
        val gloss = Path().apply {
            moveTo(cx - r * 0.55f, cy - r * 0.42f)
            lineTo(cx, cy - r * 0.60f)
            lineTo(cx + r * 0.32f, cy - r * 0.32f)
            lineTo(cx - r * 0.22f, cy - r * 0.12f)
            close()
        }
        drawPath(gloss, color = Color.White.copy(alpha = 0.22f))
        // Charcoal centre hole, then the gold centre dot.
        drawCircle(color = Color(0xFF16120E), radius = r * 0.29f, center = centre)
        drawCircle(color = Color(0xFFFFC93D), radius = r * 0.10f, center = centre)
    }
}

/**
 * Loading state: the Vinyl Ring spinning CLOCKWISE, like a record.
 * Smooth, continuous rotation while loading, and ONLY while loading —
 * callers must only compose this in a loading state. Once loading
 * finishes, screens go back to the static [PrismLogo], which never
 * rotates. Never use this in the launcher icon (launcher icons cannot
 * animate), and never add clock/temp/weather chrome alongside it.
 */
@Composable
fun LoadingVinyl(modifier: Modifier = Modifier, size: Dp = 160.dp) {
    val transition = rememberInfiniteTransition(label = "vinyl-spin")
    // Increasing rotationZ in Compose rotates clockwise (y axis points down).
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearEasing)),
        label = "vinyl-angle",
    )
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(size).graphicsLayer { rotationZ = angle }) {
            PrismLogo(size = size)
        }
    }
}
