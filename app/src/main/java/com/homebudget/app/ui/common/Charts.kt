package com.homebudget.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Small chart set drawn with Canvas (no chart library). Conventions: one y-axis starting at zero,
 * thin marks with 4dp rounded data ends, 2dp gaps between adjacent bars, recessive grid lines,
 * text in text colors (never the series color), a legend for 2+ series, tap to select a value.
 * Every chart has a text description for TalkBack, and screens show the same numbers as text.
 */

data class ChartSeries(val name: String, val color: Color, val values: List<Long>)

@Composable
fun ChartLegend(series: List<ChartSeries>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(s.color, RoundedCornerShape(2.dp)))
                Spacer(Modifier.size(6.dp))
                Text(s.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Grouped vertical bars: one group per label, one bar per series. [selected] highlights a group;
 * tapping a group calls [onSelect] with its index.
 */
@Composable
fun GroupedBarChart(
    labels: List<String>,
    series: List<ChartSeries>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    val max = series.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .pointerInput(labels.size) {
                    detectTapGestures { p ->
                        if (labels.isNotEmpty()) onSelect((p.x / (size.width.toFloat() / labels.size)).toInt().coerceIn(0, labels.lastIndex))
                    }
                },
        ) {
            val n = labels.size
            if (n == 0) return@Canvas
            val groupW = size.width / n
            val gap = 2.dp.toPx()
            val pad = groupW * 0.18f
            val barW = ((groupW - 2 * pad - gap * (series.size - 1)) / series.size).coerceAtLeast(1f)
            val radius = 4.dp.toPx().coerceAtMost(barW / 2)
            // Recessive grid: baseline plus quarter lines.
            for (i in 0..4) {
                val y = size.height - size.height * i / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f,
                    pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            selected?.takeIf { it in 0 until n }?.let { s ->
                drawRect(highlight, topLeft = Offset(groupW * s, 0f), size = Size(groupW, size.height))
            }
            for (g in 0 until n) {
                series.forEachIndexed { si, s ->
                    val v = s.values.getOrElse(g) { 0L }.coerceAtLeast(0)
                    if (v == 0L) return@forEachIndexed
                    val h = (size.height * (v.toDouble() / max)).toFloat().coerceAtLeast(2f)
                    val left = groupW * g + pad + si * (barW + gap)
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = left, top = size.height - h, right = left + barW, bottom = size.height,
                                topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                            )
                        )
                    }
                    drawPath(path, s.color)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(
                    l,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == selected) MaterialTheme.colorScheme.onSurface else labelColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Single-series line chart with markers over time-ordered points. The y-axis starts at zero so
 * changes are not exaggerated. Tapping selects the nearest point.
 */
@Composable
fun LineChart(
    values: List<Long>,
    color: Color,
    selected: Int?,
    onSelect: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(values.size) {
                detectTapGestures { p ->
                    if (values.isEmpty()) return@detectTapGestures
                    val step = if (values.size == 1) 0f else (size.width - 2 * 12.dp.toPx()) / (values.size - 1)
                    val i = if (step == 0f) 0 else ((p.x - 12.dp.toPx()) / step).let { Math.round(it) }
                    onSelect(i.coerceIn(0, values.lastIndex))
                }
            },
    ) {
        if (values.isEmpty()) return@Canvas
        val inset = 12.dp.toPx()
        val top = 8.dp.toPx()
        val plotH = size.height - top
        for (i in 0..4) {
            val y = size.height - plotH * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f,
                pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        val step = if (values.size == 1) 0f else (size.width - 2 * inset) / (values.size - 1)
        fun pt(i: Int) = Offset(
            if (values.size == 1) size.width / 2 else inset + step * i,
            size.height - (plotH * (values[i].toDouble() / max)).toFloat(),
        )
        if (values.size > 1) {
            val path = Path().apply {
                moveTo(pt(0).x, pt(0).y)
                for (i in 1 until values.size) lineTo(pt(i).x, pt(i).y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        selected?.takeIf { it in values.indices }?.let { s ->
            drawLine(grid, Offset(pt(s).x, 0f), Offset(pt(s).x, size.height), strokeWidth = 1.dp.toPx())
        }
        values.indices.forEach { i ->
            val r = if (i == selected) 6.dp.toPx() else 4.dp.toPx()
            // 2dp surface ring keeps markers readable where they overlap the line.
            drawCircle(surface, radius = r + 2.dp.toPx(), center = pt(i))
            drawCircle(color, radius = r, center = pt(i))
        }
    }
}
