package com.fourgeailabs.prismtune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The PrismTune mark: a faceted prism (a gem-cut triangle, NOT Plex's ">")
 * refracting a beam into three sound-wave / equalizer beams.
 * Drawn in code so the logo, the launcher icon, and the splash all match.
 */
@Composable
fun PrismLogo(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // Prism body: faceted triangle, amber gradient.
        val prism = Path().apply {
            moveTo(w * 0.50f, h * 0.08f)
            lineTo(w * 0.88f, h * 0.62f)
            lineTo(w * 0.12f, h * 0.62f)
            close()
        }
        drawPath(
            prism,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFFD166), Color(0xFFFFB020), Color(0xFFE8890C)),
                start = Offset(w * 0.2f, h * 0.1f),
                end = Offset(w * 0.8f, h * 0.7f),
            ),
        )
        // Inner facet line.
        drawLine(
            color = Color(0xFF7A4A00),
            start = Offset(w * 0.50f, h * 0.08f),
            end = Offset(w * 0.50f, h * 0.62f),
            strokeWidth = w * 0.03f,
        )
        // Incoming beam (left).
        drawLine(
            color = Color.White,
            start = Offset(0f, h * 0.34f),
            end = Offset(w * 0.30f, h * 0.34f),
            strokeWidth = w * 0.05f,
        )
        // Refracted sound-wave beams (right, fanning out).
        val beamColor = Color(0xFFFFB020)
        drawLine(beamColor, Offset(w * 0.72f, h * 0.42f), Offset(w, h * 0.30f), w * 0.07f)
        drawLine(beamColor, Offset(w * 0.72f, h * 0.50f), Offset(w, h * 0.55f), w * 0.07f)
        drawLine(beamColor, Offset(w * 0.72f, h * 0.58f), Offset(w, h * 0.80f), w * 0.07f)
        // Equalizer bars under the prism.
        val barColor = Color(0xFFFFB020)
        val baseY = h * 0.92f
        val heights = listOf(0.16f, 0.26f, 0.10f, 0.30f, 0.20f)
        heights.forEachIndexed { i, frac ->
            val x = w * (0.20f + i * 0.15f)
            drawLine(
                color = barColor,
                start = Offset(x, baseY),
                end = Offset(x, baseY - h * frac),
                strokeWidth = w * 0.07f,
            )
        }
        // Outline, for contrast on dark backgrounds.
        drawPath(prism, color = Color(0xFF3A2A00), style = Stroke(width = w * 0.02f))
    }
}
