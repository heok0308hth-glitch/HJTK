package com.ht.hjtk.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.ht.hjtk.core.three.Studio3D
import com.ht.mesh.Camera
import com.ht.mesh.Modifier as MeshMod
import com.ht.mesh.Node
import com.ht.mesh.Raster
import com.ht.mesh.Samples
import com.ht.mesh.Scene
import com.ht.mesh.V3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

/**
 * HJTK 3D 스튜디오 — 폰에서 손가락으로 만지는 작은 블렌더.
 *
 * 규칙 하나: **손가락 두 개는 언제나 화면(보기)**, 손가락 하나는 지금 고른 도구(돌리기·옮기기·돌리기·크기).
 * 장면은 HT-Scene 글이라 AI 팀이 만든 모델을 그대로 열고, 고친 결과를 다시 AI에게 맡길 수 있다.
 * 렌더는 블렌더 Workbench 같은 솔리드 셰이딩을 CPU로 — 어떤 폰에서도 같은 그림.
 */
private enum class Tool(val label: String, val hint: String) {
    VIEW("보기", "한 손가락 돌리기 · 두 손가락 확대·이동"),
    MOVE("이동", "끌어서 바닥 위로 옮기기"),
    TURN("회전", "좌우로 끌면 Y축, 위아래는 X축"),
    SIZE("크기", "위로 끌면 커지고 아래로 작아져요"),
}
private enum class Deck(val label: String) { ADD("추가"), SHAPE("모양"), MODS("수정자"), MAT("재질"), AI("AI") }

@Composable
fun StudioScreen(vm: MainVm, path: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ws = vm.store.workspace
    // 장면: 파일 → 없으면 가장 최근 작업 → 그것도 없으면 예시
    val scene = remember(path) {
        path?.let { Studio3D.load(File(it)) } ?: Studio3D.library(ws).firstOrNull()?.let { Studio3D.load(it) } ?: Scene.parse(Samples.ROBOT)
    }
    val saveTo = remember(path) { path?.let { p -> File(p).takeIf { it.startsWith(Studio3D.dir(ws)) } } }
    var rev by remember { mutableIntStateOf(0) }
    var sel by remember { mutableIntStateOf(if (scene.nodes.isEmpty()) -1 else 0) }
    var cam by remember { mutableStateOf(Camera.frame(scene)) }
    var tool by remember { mutableStateOf(Tool.VIEW) }
    var deck by remember { mutableStateOf(Deck.ADD) }
    var moving by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf(IntSize(1, 1)) }
    var note by remember { mutableStateOf("") }
    val undo = remember { ArrayDeque<String>() }
    val redo = remember { ArrayDeque<String>() }
    var file by remember { mutableStateOf(saveTo) }

    fun snapshot() = scene.toJson().toString()
    fun restore(json: String) {
        val s = Scene.parse(json)
        scene.name = s.name; scene.materials.clear(); scene.materials.putAll(s.materials); scene.nodes.clear(); scene.nodes.addAll(s.nodes)
        sel = sel.coerceAtMost(scene.nodes.size - 1); rev++
    }
    fun commit(before: String) {
        if (before == snapshot()) return
        undo.addLast(before); while (undo.size > 60) undo.removeFirst(); redo.clear()
        rev++
        file = Studio3D.save(ws, scene, file)
    }
    fun edit(block: () -> Unit) { val b = snapshot(); block(); commit(b) }
    val node: Node? = scene.nodes.getOrNull(sel)

    Column(Modifier.fillMaxSize().background(C.Paper)) {
        TopBar(scene.name, remember(rev) { scene.stats() }, onBack = { vm.back() }) {
            IconPress(I.Undo, enabled = undo.isNotEmpty(), mirror = false) { undo.removeLastOrNull()?.let { redo.addLast(snapshot()); restore(it); file = Studio3D.save(ws, scene, file) } }
            IconPress(I.Undo, enabled = redo.isNotEmpty(), mirror = true) { redo.removeLastOrNull()?.let { undo.addLast(snapshot()); restore(it); file = Studio3D.save(ws, scene, file) } }
            IconPress(I.Download) {
                scope.launch {
                    note = "굽는 중…"
                    val (b, saved) = withContext(Dispatchers.IO) {
                        val out = File(Studio3D.dir(ws), "export/" + Studio3D.slug(scene.name))
                        val built = Studio3D.build(scene, out)
                        built to Studio3D.toDownloads(ctx, listOf(built.glb, built.obj, built.mtl, built.png), Studio3D.slug(scene.name))
                    }
                    note = if (saved > 0) "다운로드/HJTK/3D/${Studio3D.slug(scene.name)} · .glb(블렌더·유니티) .obj .png" else "내보냈어요 · ${b.glb.parentFile?.name}"
                }
            }
        }

        // ── 뷰포트 ─────────────────────────────────────────
        Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { view = it }) {
            val frame = rememberFrame(scene, rev, cam, sel, view, low = moving)
            val frameNow by rememberUpdatedState(frame)
            val deckNow by rememberUpdatedState(deck)
            frame?.let { Image(it.first.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            Box(Modifier.fillMaxSize().pointerInput(tool, sel, rev) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val before = snapshot()
                    var travelled = 0f
                    var multi = false
                    var last: List<Offset> = listOf(down.position)
                    moving = true
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pts = ev.changes.filter { it.pressed }
                        if (pts.isEmpty()) break
                        val now = pts.map { it.position }
                        if (now.size >= 2) {
                            multi = true
                            if (last.size >= 2) {
                                val c0 = (last[0] + last[1]) / 2f; val c1 = (now[0] + now[1]) / 2f
                                val d0 = (last[0] - last[1]).getDistance(); val d1 = (now[0] - now[1]).getDistance()
                                val a0 = kotlin.math.atan2(last[1].y - last[0].y, last[1].x - last[0].x); val a1 = kotlin.math.atan2(now[1].y - now[0].y, now[1].x - now[0].x)
                                cam = cam.pan((c1.x - c0.x) / size.height, (c1.y - c0.y) / size.height).zoom(if (d0 > 1f) d1 / d0 else 1f).orbit(-Math.toDegrees((a1 - a0).toDouble()).toFloat(), 0f)
                            }
                        } else if (!multi) {
                            val d = now[0] - last[0]
                            travelled += d.getDistance()
                            if (travelled > 8f) when {
                                tool == Tool.VIEW || node == null -> cam = cam.orbit(-d.x * 0.35f, d.y * 0.35f)
                                tool == Tool.MOVE -> { val k = cam.dist * 0.73f / size.height; drag(node, cam, d.x * k, d.y * k); rev++ }
                                tool == Tool.TURN -> { node.rot = V3(node.rot.x + d.y * 0.4f, node.rot.y + d.x * 0.4f, node.rot.z); rev++ }
                                tool == Tool.SIZE -> { val f = exp(-d.y * 0.004f); node.scale = node.scale * f; rev++ }
                            }
                        }
                        ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        last = now
                    }
                    moving = false
                    if (!multi && travelled <= 8f) {
                        // 탭 = 고르기(픽셀마다 기록한 오브젝트 번호로)
                        val f = frameNow ?: return@awaitEachGesture
                        val r = f.second
                        val x = (down.position.x / size.width * r.w).toInt(); val y = (down.position.y / size.height * r.h).toInt()
                        sel = r.pick(x, y, radius = (r.w / 40).coerceAtLeast(4))
                        if (sel >= 0 && deckNow == Deck.ADD) deck = Deck.SHAPE
                    } else if (tool != Tool.VIEW && !multi) commit(before)
                }
            })
            Column(Modifier.align(Alignment.TopStart).padding(14.dp)) {
                Label(tool.label + " · " + tool.hint, color = C.Mute)
                if (note.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(note, style = T.mono.copy(fontSize = 11.sp, color = C.Ink)) }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                MiniChip("맞춤") { cam = Camera.frame(scene, cam.yaw, cam.pitch) }
            }
        }

        // ── 도구 · 오브젝트 ─────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tool.entries.forEach { t -> Seg(t.label, t == tool, Modifier.weight(1f)) { tool = t } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            scene.nodes.forEachIndexed { i, n -> Chip(n.name, i == sel) { sel = if (sel == i) -1 else i } }
        }
        Spacer(Modifier.height(8.dp)); Hairline()

        // ── 덱 ───────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Deck.entries.forEach { d ->
                Column(Modifier.weight(1f).press({ deck = d }).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.label, style = T.label.copy(color = if (d == deck) C.Ink else C.Mute, fontSize = 11.sp))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(18.dp).height(2.dp).background(if (d == deck) C.Red else Color.Transparent))
                }
            }
        }
        Column(Modifier.fillMaxWidth().height(232.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp)) {
            when (deck) {
                Deck.ADD -> AddDeck(
                    onAdd = { shape -> edit { val n = newNode(scene, shape, cam); scene.nodes += n; sel = scene.nodes.size - 1 }; deck = Deck.SHAPE },
                    onSample = { json -> edit { val s = Scene.parse(json); scene.name = s.name; scene.materials.clear(); scene.materials.putAll(s.materials); scene.nodes.clear(); scene.nodes.addAll(s.nodes); sel = 0 }; file = null; file = Studio3D.save(ws, scene); cam = Camera.frame(scene) },
                    onNew = { edit { scene.name = "새 장면"; scene.nodes.clear(); scene.materials.clear(); sel = -1 }; file = null; file = Studio3D.save(ws, scene) })
                Deck.SHAPE -> node?.let { n -> ShapeDeck(n, rev, onLive = { rev++ }, onCommit = { b -> commit(b) }, snapshot = ::snapshot,
                    onDuplicate = { edit { val c = n.copy().also { it.name = scene.unique(n.name); it.at = n.at + V3(0.3f, 0f, 0.3f) }; scene.nodes += c; sel = scene.nodes.size - 1 } },
                    onDelete = { edit { scene.nodes.forEach { if (it.parent == n.name) it.parent = n.parent }; scene.nodes.removeAt(sel); sel = -1 } }) } ?: Empty("오브젝트를 탭하거나 위 목록에서 고르세요")
                Deck.MODS -> node?.let { n -> ModsDeck(n, rev) { block -> edit(block) } } ?: Empty("수정자를 붙일 오브젝트를 고르세요")
                Deck.MAT -> node?.let { n -> MatDeck(scene, n, rev, onLive = { rev++ }, onCommit = { b -> commit(b) }, snapshot = ::snapshot) } ?: Empty("재질을 바꿀 오브젝트를 고르세요")
                Deck.AI -> AiDeck(scene.nodes.isEmpty()) { req, done ->
                    scope.launch {
                        val before = snapshot()
                        val (json, who) = vm.runner.sculpt(if (scene.nodes.isEmpty()) "" else before, req) { step -> note = step }
                        if (json != null) { restore(json); commit(before); cam = Camera.frame(scene, cam.yaw, cam.pitch); note = "$who 가 ${if (before.contains("\"shape\"")) "고쳤어요" else "만들었어요"} · ${scene.stats()}" }
                        else note = "못 했어요 · $who"
                        done()
                    }
                }
            }
        }
    }
}

/** 바닥 위로 옮기기: 화면의 가로는 카메라 오른쪽, 세로는 카메라가 보는 방향(바닥에 눕힌) */
private fun drag(n: Node, cam: Camera, dx: Float, dy: Float) {
    val f = (cam.target - cam.eye()); val fwd = V3(f.x, 0f, f.z).norm(); val right = fwd.cross(V3(0f, 1f, 0f)).norm()
    n.at = n.at + right * dx - fwd * dy
}

private fun newNode(s: Scene, shape: String, cam: Camera): Node {
    val p: LinkedHashMap<String, Any> = when (shape) {
        "box" -> linkedMapOf("size" to listOf(1.0, 1.0, 1.0)); "sphere" -> linkedMapOf("r" to 0.5); "cylinder" -> linkedMapOf("r" to 0.4, "h" to 1.0)
        "cone" -> linkedMapOf("r" to 0.45, "h" to 1.0); "torus" -> linkedMapOf("R" to 0.5, "r" to 0.15); "plane" -> linkedMapOf("size" to listOf(2.0, 0.0, 2.0))
        "capsule" -> linkedMapOf("r" to 0.3, "h" to 1.2)
        else -> linkedMapOf("size" to listOf(1.0, 1.0, 1.0))
    }
    val lift = when (shape) { "plane" -> 0f; "torus" -> 0.15f; "sphere" -> 0.5f; else -> 0.5f + if (shape == "capsule") 0.1f else 0f }
    return Node(s.unique(KOR[shape] ?: shape), shape, p, at = V3(cam.target.x, lift, cam.target.z))
}

private val KOR = mapOf("box" to "상자", "sphere" to "구", "cylinder" to "원기둥", "cone" to "원뿔", "torus" to "도넛", "plane" to "평면", "capsule" to "캡슐")

/** 장면이 바뀌면 다시 그린다. 만지는 동안엔 해상도를 낮춰 손을 따라오게 */
@Composable
private fun rememberFrame(scene: Scene, rev: Int, cam: Camera, sel: Int, view: IntSize, low: Boolean): Pair<Bitmap, Raster>? {
    val look = if (C.dark) Raster.DARK else Raster.PAPER
    val scale = if (low) 0.42f else 0.75f
    val w = (view.width * scale).toInt().coerceIn(64, 900); val h = (w * view.height / view.width.coerceAtLeast(1)).coerceIn(64, 1400)
    fun draw(): Pair<Bitmap, Raster> {
        val r = Raster(w, h).render(scene, cam, look, sel)
        return Bitmap.createBitmap(r.color, w, h, Bitmap.Config.ARGB_8888) to r
    }
    if (Motion.test) return remember(rev, cam, sel, w, h) { draw() }
    val state = produceState<Pair<Bitmap, Raster>?>(null, rev, cam, sel, w, h, look) { value = withContext(Dispatchers.Default) { draw() } }
    return state.value
}

@Composable
private fun AddDeck(onAdd: (String) -> Unit, onSample: (String) -> Unit, onNew: () -> Unit) {
    Label("도형")
    Spacer(Modifier.height(8.dp))
    val shapes = listOf("box", "sphere", "cylinder", "cone", "torus", "capsule", "plane")
    shapes.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { s -> ShapeTile(s, Modifier.weight(1f)) { onAdd(s) } }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(6.dp)); Label("예시 장면 · 새로")
    Spacer(Modifier.height(8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Samples.ALL.forEach { (name, json) -> Chip(name, false) { onSample(json) } }
        Chip("빈 장면", false) { onNew() }
    }
}

/** 도형 타일: 이름 + 선 하나로 그린 실루엣(이모지·스톡 아이콘 대신) */
@Composable
private fun ShapeTile(shape: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, C.Hair, RoundedCornerShape(8.dp)).press(onClick).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
            val s = size.minDimension; val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f * density)
            val c = C.Ink
            when (shape) {
                "box" -> { drawRect(c, Offset(s * 0.18f, s * 0.32f), androidx.compose.ui.geometry.Size(s * 0.5f, s * 0.5f), style = st); drawLine(c, Offset(s * 0.18f, s * 0.32f), Offset(s * 0.36f, s * 0.14f), st.width); drawLine(c, Offset(s * 0.68f, s * 0.32f), Offset(s * 0.86f, s * 0.14f), st.width); drawLine(c, Offset(s * 0.36f, s * 0.14f), Offset(s * 0.86f, s * 0.14f), st.width); drawLine(c, Offset(s * 0.86f, s * 0.14f), Offset(s * 0.86f, s * 0.64f), st.width); drawLine(c, Offset(s * 0.68f, s * 0.82f), Offset(s * 0.86f, s * 0.64f), st.width) }
                "sphere" -> { drawCircle(c, s * 0.38f, style = st); drawOval(c, Offset(s * 0.12f, s * 0.4f), androidx.compose.ui.geometry.Size(s * 0.76f, s * 0.2f), style = st) }
                "cylinder", "capsule" -> { drawOval(c, Offset(s * 0.22f, s * 0.1f), androidx.compose.ui.geometry.Size(s * 0.56f, s * 0.18f), style = st); drawOval(c, Offset(s * 0.22f, s * 0.72f), androidx.compose.ui.geometry.Size(s * 0.56f, s * 0.18f), style = st); drawLine(c, Offset(s * 0.22f, s * 0.19f), Offset(s * 0.22f, s * 0.81f), st.width); drawLine(c, Offset(s * 0.78f, s * 0.19f), Offset(s * 0.78f, s * 0.81f), st.width) }
                "cone" -> { drawOval(c, Offset(s * 0.18f, s * 0.72f), androidx.compose.ui.geometry.Size(s * 0.64f, s * 0.18f), style = st); drawLine(c, Offset(s * 0.18f, s * 0.81f), Offset(s * 0.5f, s * 0.1f), st.width); drawLine(c, Offset(s * 0.82f, s * 0.81f), Offset(s * 0.5f, s * 0.1f), st.width) }
                "torus" -> { drawOval(c, Offset(s * 0.08f, s * 0.28f), androidx.compose.ui.geometry.Size(s * 0.84f, s * 0.44f), style = st); drawOval(c, Offset(s * 0.34f, s * 0.43f), androidx.compose.ui.geometry.Size(s * 0.32f, s * 0.14f), style = st) }
                else -> { val p = androidx.compose.ui.graphics.Path().apply { moveTo(s * 0.1f, s * 0.62f); lineTo(s * 0.4f, s * 0.38f); lineTo(s * 0.9f, s * 0.38f); lineTo(s * 0.6f, s * 0.62f); close() }; drawPath(p, c, style = st) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(KOR[shape] ?: shape, style = T.small.copy(fontSize = 12.sp, color = C.Ink))
    }
}

@Composable
private fun ShapeDeck(n: Node, rev: Int, onLive: () -> Unit, onCommit: (String) -> Unit, snapshot: () -> String, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var before by remember(n) { mutableStateOf<String?>(null) }
    fun live(set: () -> Unit) { if (before == null) before = snapshot(); set(); onLive() }
    fun done() { before?.let(onCommit); before = null }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(n.name, style = T.head, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text((KOR[n.shape] ?: n.shape), style = T.num); Spacer(Modifier.width(12.dp))
        GhostButton("복제") { onDuplicate() }; Spacer(Modifier.width(6.dp)); GhostButton("삭제") { onDelete() }
    }
    Spacer(Modifier.height(10.dp))
    rev.let { }   // 되돌리기·AI 수정 뒤 값을 다시 읽도록
    run {
        when (n.shape) {
            "box" -> {
                val s = n.vec("size") ?: V3(1f, 1f, 1f)
                Hair("너비", s.x, 0.02f, 10f, ::done) { v -> live { n.params["size"] = listOf(v.toDouble(), s.y.toDouble(), s.z.toDouble()) } }
                Hair("높이", s.y, 0.02f, 10f, ::done) { v -> live { n.params["size"] = listOf(s.x.toDouble(), v.toDouble(), s.z.toDouble()) } }
                Hair("깊이", s.z, 0.02f, 10f, ::done) { v -> live { n.params["size"] = listOf(s.x.toDouble(), s.y.toDouble(), v.toDouble()) } }
                Hair("모서리 둥글게", n.num("round", 0f), 0f, (minOf(s.x, s.y, s.z) / 2f).coerceAtLeast(0.01f), ::done) { v -> live { if (v < 0.005f) n.params.remove("round") else n.params["round"] = v.toDouble() } }
            }
            "plane" -> {
                val s = n.vec("size") ?: V3(2f, 0f, 2f)
                Hair("너비", s.x, 0.1f, 40f, ::done) { v -> live { n.params["size"] = listOf(v.toDouble(), 0.0, s.z.toDouble()) } }
                Hair("깊이", s.z, 0.1f, 40f, ::done) { v -> live { n.params["size"] = listOf(s.x.toDouble(), 0.0, v.toDouble()) } }
            }
            "torus" -> {
                Hair("반지름", n.num("R", 0.5f), 0.05f, 5f, ::done) { v -> live { n.params["R"] = v.toDouble() } }
                Hair("굵기", n.num("r", 0.15f), 0.01f, 2f, ::done) { v -> live { n.params["r"] = v.toDouble() } }
            }
            "lathe", "extrude" -> Text("AI가 만든 윤곽 도형이에요. 모양은 AI 탭에서 말로 고치고, 여기서는 위치·회전·크기를 만져요.", style = T.small)
            else -> {
                Hair("반지름", n.num("r", 0.5f), 0.01f, 5f, ::done) { v -> live { n.params["r"] = v.toDouble() } }
                if (n.shape != "sphere") Hair("높이", n.num("h", 1f), 0.02f, 10f, ::done) { v -> live { n.params["h"] = v.toDouble() } }
            }
        }
        if (n.shape in setOf("sphere", "cylinder", "cone", "torus", "capsule", "lathe"))
            Hair("매끈함(면 수)", n.num("seg", 32f), 6f, 64f, ::done, step = 1f) { v -> live { n.params["seg"] = v.toInt() } }
        Hair("바닥에서 높이", n.at.y, -5f, 10f, ::done) { v -> live { n.at = V3(n.at.x, v, n.at.z) } }
        Hair("돌리기(Y)", n.rot.y, -180f, 180f, ::done, step = 1f) { v -> live { n.rot = V3(n.rot.x, v, n.rot.z) } }
    }
}

@Composable
private fun ModsDeck(n: Node, rev: Int, edit: (() -> Unit) -> Unit) {
    key(rev) {
        val mx = n.mods.any { it is MeshMod.Mirror && it.axis == 'x' }; val mz = n.mods.any { it is MeshMod.Mirror && it.axis == 'z' }
        val sub = (n.mods.firstOrNull { it is MeshMod.Subdiv } as? MeshMod.Subdiv)?.levels ?: 0
        val arr = n.mods.firstOrNull { it is MeshMod.Array } as? MeshMod.Array
        ModRow("좌우 대칭", "X축 반대편에 한 쌍 — 다리·바퀴·팔", if (mx) "켬" else "끔") { edit { if (mx) n.mods.removeAll { it is MeshMod.Mirror && it.axis == 'x' } else n.mods.add(0, MeshMod.Mirror('x')) } }
        ModRow("앞뒤 대칭", "Z축 반대편에 한 쌍", if (mz) "켬" else "끔") { edit { if (mz) n.mods.removeAll { it is MeshMod.Mirror && it.axis == 'z' } else n.mods.add(0, MeshMod.Mirror('z')) } }
        ModRow("부드럽게", "면을 나눠 둥글게(서브디비전)", if (sub == 0) "끔" else "${sub}단계") { edit { n.mods.removeAll { it is MeshMod.Subdiv }; if (sub < 3) n.mods.add(MeshMod.Subdiv(sub + 1)) } }
        ModRow("일렬 배열", "같은 간격으로 복사 — 계단·울타리·책장", if (arr != null && arr.radius == 0f) "${arr.count}개" else "끔") {
            edit { val c = if (arr != null && arr.radius == 0f) arr.count else 1; n.mods.removeAll { it is MeshMod.Array }; if (c < 8) n.mods.add(MeshMod.Array(c + 1, V3(1.2f * maxOf(n.scale.x, 0.2f), 0f, 0f), V3.ZERO)) }
        }
        ModRow("원형 배열", "중심을 돌며 복사 — 꽃잎·바퀴살·둥근 의자", if (arr != null && arr.radius > 0f) "${arr.count}개" else "끔") {
            edit { val c = if (arr != null && arr.radius > 0f) arr.count else 2; n.mods.removeAll { it is MeshMod.Array }; if (c < 16) n.mods.add(MeshMod.Array(c + 2, V3.ZERO, V3(0f, 360f / (c + 2), 0f), 1f)) }
        }
        Spacer(Modifier.height(6.dp))
        Text("누를 때마다 단계가 올라가고, 끝에서 다시 꺼져요.", style = T.small.copy(fontSize = 12.sp))
    }
}

@Composable
private fun ModRow(title: String, sub: String, state: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().press(onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = T.head.copy(fontSize = 14.sp)); Text(sub, style = T.small.copy(fontSize = 12.sp)) }
        Text(state, style = T.num.copy(color = if (state == "끔") C.Mute else C.Red))
    }
    Hairline()
}

private val SWATCH = listOf(0xFFE9E6DF, 0xFF1C1C1E, 0xFFE4572E, 0xFFC9A45C, 0xFF8A5A36, 0xFF3E5C58, 0xFF3E6FB0, 0xFF6B8F71, 0xFFB8B2A7, 0xFF7A3B3B, 0xFFD9C6A5, 0xFF8E949B).map { it.toInt() }

@Composable
private fun MatDeck(scene: Scene, n: Node, rev: Int, onLive: () -> Unit, onCommit: (String) -> Unit, snapshot: () -> String) {
    var before by remember(n) { mutableStateOf<String?>(null) }
    fun done() { before?.let(onCommit); before = null }
    // 재질은 이 오브젝트 전용으로(다른 오브젝트와 나눠 쓰던 재질이면 복사해서)
    fun mine(): com.ht.mesh.Material {
        val cur = scene.material(n)
        val shared = scene.nodes.count { it.mat == n.mat } > 1 || n.mat == null
        if (!shared) return cur
        val id = n.name + "_mat"; val m = cur.copy(name = id); scene.materials[id] = m; n.mat = id; return m
    }
    fun set(f: (com.ht.mesh.Material) -> com.ht.mesh.Material) { if (before == null) before = snapshot(); val m = mine(); scene.materials[m.name] = f(m); onLive() }
    rev.let { }
    run {
        val m = scene.material(n)
        Label("색")
        Spacer(Modifier.height(8.dp))
        SWATCH.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    val on = (m.color and 0xFFFFFF) == (c and 0xFFFFFF)
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(Color(c)).border(if (on) 2.dp else 1.dp, if (on) C.Red else C.Hair, RoundedCornerShape(6.dp)).press({ set { it.copy(color = c) }; done() }))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Hair("금속성", m.metal, 0f, 1f, ::done) { v -> set { it.copy(metal = v) } }
        Hair("거칠기", m.rough, 0.02f, 1f, ::done) { v -> set { it.copy(rough = v) } }
    }
}

@Composable
private fun AiDeck(empty: Boolean, run: (String, () -> Unit) -> Unit) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Text(if (empty) "만들고 싶은 것을 말로 적으세요. 코드에 강한 AI부터 차례로 맡고, 열리는 장면이 나올 때까지 다음 AI가 이어받아요."
         else "지금 장면을 어떻게 바꿀지 적으세요. 말하지 않은 부분은 그대로 둬요.", style = T.small)
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, C.Hair, RoundedCornerShape(8.dp)).padding(12.dp)) {
        if (text.isEmpty()) Text(if (empty) "예) 바퀴 네 개 달린 작은 배달 로봇, 앞에 둥근 눈 하나" else "예) 다리를 조금 더 길게, 상판은 원목 색으로", style = T.body.copy(color = C.Mute))
        BasicTextField(text, { text = it }, textStyle = T.body, cursorBrush = SolidColor(C.Red), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
    }
    Spacer(Modifier.height(10.dp))
    InkButton(if (busy) "AI가 작업 중…" else if (empty) "AI에게 만들기 맡기기" else "AI에게 고치기 맡기기", accent = !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy || text.isBlank()) return@InkButton
        busy = true
        run(text.trim()) { busy = false }
    }
}

@Composable
private fun Empty(text: String) { Spacer(Modifier.height(18.dp)); Text(text, style = T.small) }

/** 머리카락 굵기 슬라이더: 1dp 선, 작은 사각 손잡이, 왼쪽 이름 · 오른쪽 숫자 */
@Composable
private fun Hair(label: String, value: Float, min: Float, max: Float, onDone: () -> Unit, step: Float = 0f, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(min, max)) }
    var w by remember { mutableIntStateOf(1) }
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row { Text(label, style = T.small.copy(fontSize = 12.5.sp, color = C.Ink), modifier = Modifier.weight(1f)); Text(if (step >= 1f) "${v.toInt()}" else String.format(Locale.US, "%.2f", v), style = T.num) }
        Box(Modifier.fillMaxWidth().height(26.dp).onSizeChanged { w = it.width }.pointerInput(min, max) {
            awaitEachGesture {
                val d = awaitFirstDown()
                fun at(x: Float) { var nv = min + (x / w).coerceIn(0f, 1f) * (max - min); if (step > 0f) nv = Math.round(nv / step) * step; if (abs(nv - v) > 1e-6f) { v = nv; change(nv) } }
                at(d.position.x)
                while (true) { val e = awaitPointerEvent(); val c = e.changes.firstOrNull() ?: break; if (!c.pressed) break; at(c.position.x); c.consume() }
                done()
            }
        }, contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Hair))
            val f = ((v - min) / (max - min)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(f).height(1.dp).background(C.Ink))
            Box(Modifier.fillMaxWidth(f), contentAlignment = Alignment.CenterEnd) { Box(Modifier.size(10.dp).background(C.Ink)) }
        }
    }
}

@Composable
private fun Seg(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(if (on) C.Ink else Color.Transparent).border(1.dp, if (on) C.Ink else C.Hair, RoundedCornerShape(6.dp)).press(onClick).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, style = T.small.copy(fontSize = 12.5.sp, color = if (on) C.Paper else C.Ink))
    }
}

@Composable
private fun MiniChip(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x66000000)).press(onClick).padding(horizontal = 10.dp, vertical = 6.dp)) { Text(text, style = T.label.copy(color = Color(0xFFE9E6DF))) }
}

@Composable
private fun IconPress(icon: I, enabled: Boolean = true, mirror: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).press(onClick, enabled), contentAlignment = Alignment.Center) {
        Ico(icon, 20.dp, if (enabled) C.Ink else C.Mute, Modifier.then(if (mirror) Modifier.graphicsLayerMirror() else Modifier))
    }
}

private fun Modifier.graphicsLayerMirror() = this.graphicsLayer { scaleX = -1f }
