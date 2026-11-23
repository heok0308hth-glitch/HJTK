package com.ht.hjtk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.R

/**
 * 접지(摺紙) — Fold & Signal
 * 종이(paper) 위에 먹(ink). 강조색은 주홍 하나, '당신이 필요해요'(승인·질문·진행 중 신호)에만 쓴다.
 * 카드의 오른쪽 위 모서리는 접혀 있다 — 로고의 접힌 종이새에서 온 유일한 장식.
 */
object C {
    /** 다크 모드(기본 켬). 바꾸면 화면을 다시 그린다 — 모든 색은 이 한 곳에서 나온다 */
    private val darkState = mutableStateOf(true)
    var dark: Boolean get() = darkState.value; set(v) { darkState.value = v }
    fun load(ctx: android.content.Context) { dark = ctx.getSharedPreferences("look", 0).getBoolean("dark", true) }
    fun save(ctx: android.content.Context, v: Boolean) { dark = v; ctx.getSharedPreferences("look", 0).edit().putBoolean("dark", v).apply() }
    private val PaperL = Color(0xFFF3F0E8); private val PaperD = Color(0xFF121214)
    val Paper: Color get() = if (dark) PaperD else PaperL
    private val Paper2L = Color(0xFFEAE6DC); private val Paper2D = Color(0xFF1B1B1E)
    val Paper2: Color get() = if (dark) Paper2D else Paper2L
    private val Paper3L = Color(0xFFE0DBCE); private val Paper3D = Color(0xFF26262A)
    val Paper3: Color get() = if (dark) Paper3D else Paper3L
    private val InkL = Color(0xFF121212); private val InkD = Color(0xFFEDEAE3)
    val Ink: Color get() = if (dark) InkD else InkL
    private val Ink2L = Color(0xFF262624); private val Ink2D = Color(0xFFD9D5CC)
    val Ink2: Color get() = if (dark) Ink2D else Ink2L
    private val Ink3L = Color(0xFF3A3936); private val Ink3D = Color(0xFFB8B4AA)
    val Ink3: Color get() = if (dark) Ink3D else Ink3L
    private val GraphiteL = Color(0xFF6D695F); private val GraphiteD = Color(0xFFA19D93)
    val Graphite: Color get() = if (dark) GraphiteD else GraphiteL
    private val MuteL = Color(0xFF9A958B); private val MuteD = Color(0xFF76726A)
    val Mute: Color get() = if (dark) MuteD else MuteL
    private val HairL = Color(0xFFD5CFC1); private val HairD = Color(0xFF2C2C30)
    val Hair: Color get() = if (dark) HairD else HairL
    private val RedL = Color(0xFFE2442F); private val RedD = Color(0xFFF0573F)
    val Red: Color get() = if (dark) RedD else RedL
    private val RedSoftL = Color(0xFFF5DAD3); private val RedSoftD = Color(0xFF3A201B)
    val RedSoft: Color get() = if (dark) RedSoftD else RedSoftL
    private val OnInkL = Color(0xFFF3F0E8); private val OnInkD = Color(0xFF121214)
    val OnInk: Color get() = if (dark) OnInkD else OnInkL
    private val OnInkMuteL = Color(0xFF8E8A80); private val OnInkMuteD = Color(0xFF5F5B54)
    val OnInkMute: Color get() = if (dark) OnInkMuteD else OnInkMuteL
}

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold)
)

object T {
    val display get() = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp, color = C.Ink)
    val title get() = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp, color = C.Ink)
    val head get() = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp, color = C.Ink)
    val body get() = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp, color = C.Ink)
    val small get() = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp, color = C.Graphite)
    val label get() = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 1.1.sp, color = C.Mute)
    val mono get() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = C.Ink3)
    val num get() = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.5.sp, color = C.Mute)
}

/** 오른쪽 위가 접힌 모서리, 나머지는 작은 라운드 */
class FoldShape(private val cut: Dp = 12.dp, private val r: Dp = 6.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }; val rr = with(density) { r.toPx() }
        val p = Path().apply {
            moveTo(rr, 0f); lineTo(size.width - c, 0f); lineTo(size.width, c); lineTo(size.width, size.height - rr)
            quadraticTo(size.width, size.height, size.width - rr, size.height); lineTo(rr, size.height)
            quadraticTo(0f, size.height, 0f, size.height - rr); lineTo(0f, rr); quadraticTo(0f, 0f, rr, 0f); close()
        }
        return Outline.Generic(p)
    }
}

/** 접힌 삼각형(뒷면)을 그린다 */
fun Modifier.drawWithFold(color: Color, cut: Dp = 12.dp) = this.then(Modifier.drawBehind {
    val c = cut.toPx()
    drawPath(Path().apply { moveTo(size.width - c, 0f); lineTo(size.width - c, c); lineTo(size.width, c); close() }, color)
})

@Composable
fun HjtkTheme(content: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dark = C.dark
    // 상태 표시줄·내비게이션 막대 글자색도 바탕에 맞춘다
    SideEffect {
        (ctx as? androidx.activity.ComponentActivity)?.let { a -> a.enableEdgeToEdge(
            if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)) }
    }
    MaterialTheme(colorScheme = (if (dark) androidx.compose.material3.darkColorScheme(primary = C.Ink, onPrimary = C.OnInk, background = C.Paper, surface = C.Paper, onSurface = C.Ink, secondary = C.Red, outline = C.Hair)
        else lightColorScheme(primary = C.Ink, onPrimary = C.OnInk, background = C.Paper, surface = C.Paper, onSurface = C.Ink, secondary = C.Red, outline = C.Hair))) {
        content()
    }
}

/** 눌림은 리플이 아니라 0.97 축소 */
@Composable
fun Modifier.press(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    return this.scale(if (pressed) 0.97f else 1f).clickable(src, null, enabled = enabled, onClick = onClick)
}

/** 라틴은 고정폭 대문자 + 자간, 한글이 섞이면 Pretendard로(고정폭 자간은 한글을 낱자로 흩뜨린다) */
@Composable fun Label(text: String, modifier: Modifier = Modifier, color: Color = C.Mute) {
    val hangul = text.any { it in '\uAC00'..'\uD7A3' }
    Text(if (hangul) text else text.uppercase(), modifier = modifier,
        style = if (hangul) T.label.copy(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.sp, color = color) else T.label.copy(color = color))
}

@Composable fun Hairline(modifier: Modifier = Modifier, color: Color = C.Hair) = Box(modifier.fillMaxWidth().height(1.dp).background(color))

@Composable
fun SignalDot(color: Color = C.Red, size: Dp = 7.dp, pulse: Boolean = false) {
    val a = if (pulse && Motion.enabled) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "p")
        t.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a").value
    } else 1f
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(color.copy(alpha = a)))
}

/** 테스트에서 무한 애니메이션을 끄기 위한 전역 스위치 */
object Motion { var enabled = true }

/** 화면 캡처 테스트에서 '키보드 열림' 레이아웃을 강제로 그리기 위한 스위치 */
val LocalForceIme = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 키보드가 실제로 화면을 차지하고 있는가(높이 > 0). 플래그가 아니라 높이로 판단해야 기기마다 일관된다. */
@Composable
fun imeOpen(): Boolean = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0 || LocalForceIme.current

@Composable
fun InkButton(text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(if (accent) C.Red else C.Ink).press(onClick).padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = T.head.copy(color = C.OnInk, fontSize = 14.sp))
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onInk: Boolean = false, onClick: () -> Unit) {
    val fg = if (onInk) C.OnInk else C.Ink
    Box(modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, if (onInk) C.Ink3 else C.Hair, RoundedCornerShape(6.dp)).press(onClick).padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text(text, style = T.head.copy(color = fg, fontSize = 14.sp))
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) C.Ink else Color.Transparent).border(1.dp, if (selected) C.Ink else C.Hair, RoundedCornerShape(50))
        .press(onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, style = T.small.copy(color = if (selected) C.OnInk else C.Ink2, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp))
    }
}

/** 편집 디자인식 번호 행: 01 ── 제목 ── 값 › */
@Composable
fun NumRow(n: Int, title: String, sub: String? = null, trailing: String? = null, dot: Color? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.press(onClick) else Modifier).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(n), style = T.num, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = T.head)
            sub?.let { Text(it, style = T.small, maxLines = 2) }
        }
        dot?.let { SignalDot(it); Spacer(Modifier.width(8.dp)) }
        trailing?.let { Text(it, style = T.small.copy(color = C.Graphite)) }
        if (onClick != null) { Spacer(Modifier.width(6.dp)); Ico(I.Chevron, 16.dp, C.Mute) }
    }
}

@Composable
fun SectionHead(n: String, title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        Label(n, Modifier.padding(end = 10.dp))
        Text(title, style = T.title.copy(fontSize = 18.sp), modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Text(action, style = T.small.copy(color = C.Ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.press(onAction).padding(4.dp))
    }
    Hairline(color = C.Ink)
}

@Composable
fun FoldCard(modifier: Modifier = Modifier, ink: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = FoldShape()
    Column(modifier.clip(shape).background(if (ink) C.Ink else C.Paper2).drawWithFold(if (ink) C.Ink3 else C.Paper3).padding(16.dp), content = content)
}

@Composable
fun ProgressHair(p: Float, modifier: Modifier = Modifier, track: Color = C.Ink3, bar: Color = C.Red) {
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        drawRect(track, size = size)
        drawRect(bar, size = Size(size.width * p.coerceIn(0f, 1f), size.height))
        // 눈금 4개
        for (i in 1..3) drawRect(C.Ink, topLeft = Offset(size.width * i / 4f - 1f, 0f), size = Size(2f, size.height))
    }
}
