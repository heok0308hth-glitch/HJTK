package com.ht.hjtk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 자체 라인 아이콘(24 그리드, 1.6 스트로크). 머티리얼 기본 아이콘을 쓰지 않는다. */
enum class I { Chat, Tasks, Hub, Tools, Send, Plus, Slash, Pause, Play, Stop, Check, Close, Gear, Mail, Globe, Cal, File, Spark, Paw, Chevron, Back, Search, Pin, Export, Trash, Eye, Bell, Menu, Key, Link, Flow, Up, Down }

@Composable
fun Ico(i: I, size: Dp = 22.dp, color: Color = Color(0xFF121212), modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        val st = Stroke(width = 1.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg pts: Float, close: Boolean = false) = Path().apply { moveTo(pts[0] * s, pts[1] * s); var k = 2; while (k < pts.size) { lineTo(pts[k] * s, pts[k + 1] * s); k += 2 }; if (close) close() }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), st.width, StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) = drawRoundRect(color, Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(r * s), style = st)
        fun circ(x: Float, y: Float, r: Float, fill: Boolean = false) = if (fill) drawCircle(color, r * s, Offset(x * s, y * s)) else drawCircle(color, r * s, Offset(x * s, y * s), style = st)
        when (i) {
            I.Chat -> { drawPath(p(4f, 5f, 16f, 5f, 20f, 9f, 20f, 16f, 10f, 16f, 6f, 20f, 6f, 16f, 4f, 16f, close = true), color, style = st) }
            I.Tasks -> { line(9f, 7f, 20f, 7f); line(9f, 12f, 20f, 12f); line(9f, 17f, 16f, 17f); drawPath(p(3.5f, 7f, 4.8f, 8.3f, 7f, 5.8f), color, style = st); drawPath(p(3.5f, 12f, 4.8f, 13.3f, 7f, 10.8f), color, style = st); circ(5f, 17f, 1.2f, true) }
            I.Hub -> { rect(4f, 4f, 7f, 7f); rect(13f, 4f, 7f, 7f); rect(4f, 13f, 7f, 7f); drawPath(p(13f, 13f, 18f, 13f, 20f, 15f, 20f, 20f, 13f, 20f, close = true), color, style = st) }
            I.Tools -> { rect(3.5f, 3.5f, 17f, 17f, 3f); line(14f, 7.5f, 10f, 16.5f) }
            I.Send -> { line(12f, 19f, 12f, 5f); drawPath(p(6f, 11f, 12f, 5f, 18f, 11f), color, style = st) }
            I.Up -> { drawPath(p(6f, 14f, 12f, 8f, 18f, 14f), color, style = st) }
            I.Down -> { line(12f, 5f, 12f, 19f); drawPath(p(6f, 13f, 12f, 19f, 18f, 13f), color, style = st) }
            I.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            I.Slash -> { line(15f, 4f, 9f, 20f) }
            I.Pause -> { line(9f, 6f, 9f, 18f); line(15f, 6f, 15f, 18f) }
            I.Play -> { drawPath(p(8f, 5.5f, 18.5f, 12f, 8f, 18.5f, close = true), color, style = st) }
            I.Stop -> { rect(6.5f, 6.5f, 11f, 11f, 1.5f) }
            I.Check -> { drawPath(p(5f, 12.5f, 10f, 17f, 19f, 7f), color, style = st) }
            I.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            I.Gear -> { circ(12f, 12f, 3f); for (k in 0 until 8) { val a = Math.toRadians(k * 45.0); line((12 + 6.2 * Math.cos(a)).toFloat(), (12 + 6.2 * Math.sin(a)).toFloat(), (12 + 8.5 * Math.cos(a)).toFloat(), (12 + 8.5 * Math.sin(a)).toFloat()) }; circ(12f, 12f, 6.2f) }
            I.Mail -> { rect(3.5f, 5.5f, 17f, 13f); drawPath(p(4f, 7f, 12f, 13f, 20f, 7f), color, style = st) }
            I.Globe -> { circ(12f, 12f, 8.5f); line(3.5f, 12f, 20.5f, 12f); drawOval(color, Offset(8.5f * s, 3.5f * s), Size(7f * s, 17f * s), style = st) }
            I.Cal -> { rect(4f, 5.5f, 16f, 14.5f); line(4f, 10f, 20f, 10f); line(8.5f, 3.5f, 8.5f, 7f); line(15.5f, 3.5f, 15.5f, 7f) }
            I.File -> { drawPath(p(6f, 3.5f, 14f, 3.5f, 18.5f, 8f, 18.5f, 20.5f, 6f, 20.5f, close = true), color, style = st); drawPath(p(14f, 3.5f, 14f, 8f, 18.5f, 8f), color, style = st) }
            I.Spark -> { drawPath(p(12f, 3.5f, 13.8f, 10.2f, 20.5f, 12f, 13.8f, 13.8f, 12f, 20.5f, 10.2f, 13.8f, 3.5f, 12f, 10.2f, 10.2f, close = true), color, style = st) }
            I.Paw -> { circ(12f, 15f, 3.6f); circ(7f, 10f, 1.7f); circ(10.3f, 6.8f, 1.7f); circ(13.7f, 6.8f, 1.7f); circ(17f, 10f, 1.7f) }
            I.Chevron -> { drawPath(p(9f, 6f, 15f, 12f, 9f, 18f), color, style = st) }
            I.Back -> { drawPath(p(15f, 6f, 9f, 12f, 15f, 18f), color, style = st) }
            I.Search -> { circ(10.5f, 10.5f, 6f); line(15f, 15f, 20f, 20f) }
            I.Pin -> { drawPath(p(9f, 4f, 15f, 4f, 14f, 10f, 17f, 13f, 7f, 13f, 10f, 10f, close = true), color, style = st); line(12f, 13f, 12f, 20f) }
            I.Export -> { line(12f, 4f, 12f, 15f); drawPath(p(7.5f, 8.5f, 12f, 4f, 16.5f, 8.5f), color, style = st); drawPath(p(5f, 13f, 5f, 20f, 19f, 20f, 19f, 13f), color, style = st) }
            I.Trash -> { line(4.5f, 7f, 19.5f, 7f); drawPath(p(6.5f, 7f, 7.5f, 20f, 16.5f, 20f, 17.5f, 7f), color, style = st); line(9.5f, 7f, 10f, 4f); line(14.5f, 7f, 14f, 4f); line(10f, 4f, 14f, 4f) }
            I.Eye -> { drawPath(Path().apply { moveTo(3f * s, 12f * s); quadraticTo(12f * s, 3f * s, 21f * s, 12f * s); quadraticTo(12f * s, 21f * s, 3f * s, 12f * s); close() }, color, style = st); circ(12f, 12f, 2.8f) }
            I.Bell -> { drawPath(Path().apply { moveTo(6f * s, 16.5f * s); lineTo(6f * s, 11f * s); quadraticTo(6f * s, 5f * s, 12f * s, 5f * s); quadraticTo(18f * s, 5f * s, 18f * s, 11f * s); lineTo(18f * s, 16.5f * s); close() }, color, style = st); line(4.5f, 16.5f, 19.5f, 16.5f); line(10.5f, 19.5f, 13.5f, 19.5f) }
            I.Menu -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 14f, 17f) }
            I.Key -> { circ(8f, 12f, 4f); line(12f, 12f, 20.5f, 12f); line(17.5f, 12f, 17.5f, 15f); line(20f, 12f, 20f, 14.5f) }
            I.Link -> { drawRoundRect(color, Offset(3.5f * s, 9f * s), Size(9f * s, 6f * s), CornerRadius(3f * s), style = st); drawRoundRect(color, Offset(11.5f * s, 9f * s), Size(9f * s, 6f * s), CornerRadius(3f * s), style = st) }
            I.Flow -> { circ(6f, 6f, 2.2f); circ(18f, 12f, 2.2f); circ(6f, 18f, 2.2f); drawPath(p(8f, 6.5f, 12f, 6.5f, 16f, 11f), color, style = st); drawPath(p(8f, 17.5f, 12f, 17.5f, 16f, 13f), color, style = st) }
        }
    }
}
