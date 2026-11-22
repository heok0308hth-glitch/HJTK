package com.ht.hjtk.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.engine.PetPack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 아틀라스의 한 행을 프레임 애니메이션으로 그린다(도트는 최근접 보간) */
@Composable
fun SpriteRow(atlas: ImageBitmap, cell: Int, row: Int, count: Int, fps: Int, size: Dp, crisp: Boolean, modifier: Modifier = Modifier) {
    var f by remember(row, count) { mutableStateOf(0) }
    LaunchedEffect(row, count, fps) { if (Motion.enabled && count > 1) while (true) { delay(1000L / fps.coerceAtLeast(1)); f = (f + 1) % count } }
    Canvas(modifier.size(size)) {
        val d = this.size.width.toInt()
        drawImage(atlas, srcOffset = IntOffset(f * cell, row * cell), srcSize = IntSize(cell, cell), dstSize = IntSize(d, d),
            filterQuality = if (crisp) FilterQuality.None else FilterQuality.Low)
    }
}

@Composable
fun PetScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver by PetPack.version.collectAsState()
    val current = remember(ver) { PetPack.load(ctx) }
    var draft by remember { mutableStateOf<PetPack.Draft?>(null) }
    var rolesTick by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(PetPack.Role.IDLE) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { busy = true; err = null; scope.launch { PetPack.slice(ctx, uri).onSuccess { draft = it }.onFailure { err = it.message }; busy = false } }
    }

    // 지금 보여 줄 것: 가져온 초안이 있으면 초안, 아니면 현재 펫
    val atlas = remember(draft, current) { (draft?.atlas ?: current.atlas).asImageBitmap() }
    val cell = draft?.cell ?: current.cell
    val frames = draft?.frames ?: current.frames
    val crisp = draft != null || current.custom
    fun rowOf(r: PetPack.Role): Int { rolesTick; return (draft?.roles?.get(r) ?: current.row(r)).coerceIn(0, frames.lastIndex) }

    Column(Modifier.fillMaxSize().background(C.Paper)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).press({ vm.back() }), contentAlignment = Alignment.Center) { Ico(I.Back, 22.dp) }
            Column(Modifier.weight(1f)) { Text("펫", style = T.title); Label(if (draft != null) "미리보기 · 아직 적용 전" else if (current.custom) "내가 가져온 펫" else "기본 펫 · 쥬티") }
        }
        Hairline()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // 무대: 고른 동작을 크게
            Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(210.dp).clip(RoundedCornerShape(10.dp)).background(C.Paper2), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).padding(horizontal = 40.dp).background(C.Hair))
                SpriteRow(atlas, cell, rowOf(preview), frames[rowOf(preview)], preview.fps, 168.dp, crisp, Modifier.padding(bottom = 10.dp))
                Label(preview.label, Modifier.align(Alignment.TopStart).padding(12.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton(if (busy) "자르는 중…" else "스프라이트 시트 불러오기", Modifier.weight(1f), accent = true) { if (!busy) pick.launch(arrayOf("image/*")) }
                if (current.custom && draft == null) GhostButton("기본 펫으로") { PetPack.reset(ctx) }
            }
            err?.let { Spacer(Modifier.height(8.dp)); Text(it, style = T.small.copy(color = C.Red)) }
            Text("배경이 한 가지 색이거나 투명한 시트면 됩니다. 행을 동작으로, 칸을 프레임으로 자동으로 자르고 발 위치를 맞춰요. 이미지는 기기 밖으로 나가지 않아요.",
                style = T.small.copy(fontSize = 12.sp, color = C.Graphite), modifier = Modifier.padding(top = 8.dp))

            SectionHead("01", "동작마다 쓸 줄")
            Text("줄을 누르면 시트의 다음 줄로 바뀌어요. 왼쪽 그림을 누르면 위 무대에서 크게 봅니다.", style = T.small.copy(fontSize = 12.sp, color = C.Graphite))
            Spacer(Modifier.height(6.dp))
            PetPack.Role.values().forEach { r ->
                val row = rowOf(r)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(if (preview == r) C.Paper3 else C.Paper2).press({ preview = r }), contentAlignment = Alignment.Center) {
                        SpriteRow(atlas, cell, row, frames[row], r.fps, 46.dp, crisp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.label, style = T.head.copy(fontSize = 15.sp))
                        Label("${row + 1}번째 줄 · ${frames[row]}프레임")
                    }
                    if (draft != null) Text("바꾸기", style = T.small.copy(fontWeight = FontWeight.Bold), modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, C.Hair, RoundedCornerShape(6.dp))
                        .press({ draft!!.roles[r] = (row + 1) % frames.size; rolesTick++; preview = r }).padding(horizontal = 12.dp, vertical = 7.dp))
                }
                Hairline()
            }
            if (draft != null) {
                Spacer(Modifier.height(10.dp))
                GhostButton("달리기 방향 서로 바꾸기", Modifier.fillMaxWidth()) {
                    val d = draft!!; val a = d.roles[PetPack.Role.RUN_R] ?: 0; d.roles[PetPack.Role.RUN_R] = d.roles[PetPack.Role.RUN_L] ?: 0; d.roles[PetPack.Role.RUN_L] = a; rolesTick++; preview = PetPack.Role.RUN_R
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (draft != null) Column(Modifier.fillMaxWidth().background(C.Paper).navigationBarsPadding()) {
            Hairline()
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("취소") { draft = null }
                InkButton("이 펫으로 바꾸기", Modifier.weight(1f), accent = true) { PetPack.save(ctx, draft!!); draft = null }
            }
        }
    }
}
