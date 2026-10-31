package com.jobscout.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** Original geometric radar mark for the public edition; no reference bitmap. */
@Composable fun JobScoutEmblem(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * .5f, size.height * .5f)
        val radius = size.minDimension * .38f
        drawCircle(ScoutColors.Blue, radius, center, style = Stroke(radius * .12f))
        drawArc(ScoutColors.Teal, 185f, 220f, false,
            center - Offset(radius * .62f, radius * .62f), Size(radius * 1.24f, radius * 1.24f),
            style = Stroke(radius * .11f, cap = StrokeCap.Round))
        drawLine(ScoutColors.Blue, center, center + Offset(radius * .66f, -radius * .66f), radius * .12f, StrokeCap.Round)
        drawCircle(ScoutColors.Teal, radius * .19f, center)
    }
}

/** Radar rings and a rising route, authored directly in normalized coordinates. */
@Composable fun OpportunityRadar(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val c = Offset(w * .52f, h * .50f)
        val r = size.minDimension * .43f
        listOf(1f, .66f, .33f).forEach { scale ->
            drawCircle(Color.White.copy(alpha = .22f), r * scale, c, style = Stroke(1.5f))
        }
        drawLine(Color.White.copy(alpha = .18f), c - Offset(r, 0f), c + Offset(r, 0f), 1f)
        drawLine(Color.White.copy(alpha = .18f), c - Offset(0f, r), c + Offset(0f, r), 1f)
        val route = Path().apply {
            moveTo(w * .16f, h * .73f); lineTo(w * .38f, h * .55f)
            lineTo(w * .58f, h * .63f); lineTo(w * .83f, h * .24f)
        }
        drawPath(route, ScoutColors.Mint, style = Stroke(w * .035f, cap = StrokeCap.Round))
        listOf(Offset(.16f, .73f), Offset(.38f, .55f), Offset(.58f, .63f), Offset(.83f, .24f)).forEach {
            drawCircle(Color.White, w * .037f, Offset(w * it.x, h * it.y))
        }
    }
}
