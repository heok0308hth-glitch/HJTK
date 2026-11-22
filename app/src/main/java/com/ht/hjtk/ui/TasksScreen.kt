package com.ht.hjtk.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ht.hjtk.R
import com.ht.hjtk.data.JobRec
import com.ht.hjtk.engine.DeepSeekWeb
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dt = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA)

@Composable
fun TopBar(title: String, sub: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 6.dp else 20.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { Box(Modifier.size(40.dp).press(it), contentAlignment = Alignment.Center) { Ico(I.Back, 22.dp) } }
            Column(Modifier.weight(1f)) { Text(title, style = T.title); sub?.let { Label(it) } }
            actions()
        }
        Hairline()
    }
}

fun stateLabel(s: String) = when (s) {
    "RUNNING" -> "진행 중"; "PAUSED" -> "일시정지"; "PAUSED_APPROVAL" -> "허락 대기"; "PAUSED_INPUT" -> "답 대기"; "SUSPENDED" -> "멈춤"
    "COMPLETED" -> "완료"; "FAILED" -> "실패"; "ABORTED" -> "중지"; else -> s
}

@Composable
fun TasksScreen(vm: MainVm) {
    val jobs by vm.store.jobs.collectAsState()
    var filter by remember { mutableStateOf(0) }
    val shown = when (filter) { 0 -> jobs.filter { it.running || it.state == "SUSPENDED" }; 1 -> jobs.filter { it.state == "COMPLETED" }; else -> jobs }
    Column(Modifier.fillMaxSize()) {
        TopBar("작업", "${jobs.count { it.running }}개 진행 · 5분마다 보고")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("진행 중", "완료", "전체").forEachIndexed { i, l -> Chip(l, filter == i) { filter = i } }
        }
        if (shown.isEmpty()) Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.mascot_sleep), null, Modifier.size(120.dp))
            Spacer(Modifier.height(10.dp))
            Text(if (filter == 0) "진행 중인 작업이 없어요" else "아직 기록이 없어요", style = T.head)
            Text("대화에서 '작업 맡기기'로 보내면 여기서 진행·보고·결과물을 볼 수 있어요.", style = T.small, modifier = Modifier.padding(top = 4.dp))
        } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { j -> JobCard(vm, j) }
        }
    }
}

@Composable
private fun JobCard(vm: MainVm, j: JobRec) {
    val live = j.running
    FoldCard(Modifier.fillMaxWidth().press({ vm.go(Route.Job(j.id)) }), ink = live) {
        val fg = if (live) C.OnInk else C.Ink; val mute = if (live) C.OnInkMute else C.Mute
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalDot(if (j.state.startsWith("PAUSED_")) C.Red else if (live) C.Red else if (j.state == "COMPLETED") C.Ink else C.Mute, 7.dp, pulse = live)
            Spacer(Modifier.width(8.dp)); Label(stateLabel(j.state) + " · " + dt.format(Date(j.createdAt)), color = mute)
            Spacer(Modifier.weight(1f)); Label(j.id.take(6), color = mute)
        }
        Spacer(Modifier.height(6.dp))
        Text(j.goal, style = T.head.copy(color = fg), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        ProgressHair(j.progress.toFloat(), track = if (live) C.Ink3 else C.Paper3, bar = if (live) C.Red else C.Ink)
        Spacer(Modifier.height(6.dp))
        Text(listOfNotNull(j.now.takeIf { it.isNotBlank() && live }?.let { "지금 $it" }, j.eta.takeIf { it.isNotBlank() && live }, "보고 ${j.reports}", "명령 ${j.cmds}").joinToString(" · "), style = T.small.copy(color = mute, fontSize = 12.sp))
    }
}

@Composable
fun JobScreen(vm: MainVm, id: String) {
    val jobs by vm.store.jobs.collectAsState()
    val all by vm.store.messages.collectAsState()
    val j = jobs.firstOrNull { it.id == id } ?: run { TopBar("작업", onBack = { vm.back() }); return }
    val ctx = LocalContext.current
    val reports = (all[j.convId] ?: emptyList()).filter { it.meta["job"] == id && it.kind == "report" }
    val files = vm.store.jobDir(id).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    val plan = runCatching { JSONArray(j.plan) }.getOrNull()
    val journal = remember(j.updatedAt) { runCatching { vm.store.journalFile(id).readLines().takeLast(40).reversed() }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize()) {
        TopBar("작업 ${j.id.take(6)}", stateLabel(j.state), onBack = { vm.back() }) {
            Text("대화로", style = T.small.copy(color = C.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.press({ vm.openConversation(j.convId) }).padding(8.dp))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
            Spacer(Modifier.height(14.dp))
            Text(j.goal, style = T.title)
            Spacer(Modifier.height(10.dp)); ProgressHair(j.progress.toFloat(), track = C.Paper3, bar = C.Ink)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    j.state == "SUSPENDED" -> InkButton("이어서 진행", accent = true) { vm.runner.resume(id) }
                    j.state == "PAUSED" -> InkButton("재개", accent = true) { vm.runner.unpause(id) }
                    j.running -> GhostButton("일시정지") { vm.runner.pause(id) }
                    else -> GhostButton("같은 목표로 다시") { vm.runner.startMission(j.convId, j.goal); vm.openConversation(j.convId) }
                }
                if (j.running || j.state == "SUSPENDED") GhostButton("중지") { vm.runner.abort(id) }
                if (!j.running) GhostButton("삭제") { vm.store.deleteJob(id); vm.back() }
            }
            SectionHead("01", "계획")
            if (plan == null || plan.length() == 0) Text("아직 계획이 없어요", style = T.small, modifier = Modifier.padding(vertical = 10.dp))
            else for (k in 0 until plan.length()) { val o = plan.getJSONObject(k)
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val s = o.optString("s")
                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(50)).border(1.5.dp, if (s == "TODO") C.Hair else C.Ink, RoundedCornerShape(50)).background(if (s == "DONE") C.Ink else C.Paper), contentAlignment = Alignment.Center) {
                        if (s == "DONE") Ico(I.Check, 10.dp, C.OnInk) else if (s == "DOING") SignalDot(C.Red, 6.dp, pulse = true)
                    }
                    Spacer(Modifier.width(10.dp)); Text(o.optString("t"), style = T.body.copy(color = if (s == "TODO") C.Graphite else C.Ink), modifier = Modifier.weight(1f))
                    Text("~${o.optInt("eta")}s", style = T.label)
                }
            }
            if (j.final.isNotBlank()) { SectionHead("02", "결과"); Spacer(Modifier.height(8.dp)); MdText(j.final) }
            SectionHead("03", "결과물 · 작업공간", "공유") { files.firstOrNull()?.let { vm.openFile(ctx, it) } }
            if (files.isEmpty()) Text("파일이 아직 없어요", style = T.small, modifier = Modifier.padding(vertical = 10.dp))
            files.forEachIndexed { i, f -> NumRow(i + 1, f.name, "${f.length()}B · ${dt.format(Date(f.lastModified()))}", onClick = { vm.openFile(ctx, f) }); Hairline() }
            SectionHead("04", "보고 ${reports.size}회")
            reports.reversed().forEach { r -> Column(Modifier.padding(vertical = 8.dp)) { Label("#${r.meta["n"]} · ${dt.format(Date(r.at))}"); Text(r.text, style = T.small.copy(color = C.Ink2)) }; Hairline() }
            SectionHead("05", "저널 (해시 체인)")
            journal.forEach { l -> Text(l.replace(Regex("\"digest\":\"([0-9a-f]{10})[0-9a-f]+\""), "\"digest\":\"$1…\""), style = T.mono.copy(fontSize = 10.5.sp, color = C.Graphite), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp)) }
        }
    }
}

@Composable
fun ConversationsScreen(vm: MainVm) {
    val convs by vm.store.conversations.collectAsState()
    val all by vm.store.messages.collectAsState()
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val shown = convs.filter { c -> q.isBlank() || c.title.contains(q, true) || (all[c.id] ?: emptyList()).any { it.text.contains(q, true) } }
        .sortedWith(compareByDescending<com.ht.hjtk.data.Conversation> { it.pinned }.thenByDescending { it.updatedAt })
    Column(Modifier.fillMaxSize()) {
        TopBar("대화", "${convs.size}개 · 검색·고정·내보내기", onBack = { vm.back() }) {
            Box(Modifier.size(40.dp).press({ vm.newChat() }), contentAlignment = Alignment.Center) { Ico(I.Plus, 22.dp) }
        }
        Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Paper2).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Ico(I.Search, 18.dp, C.Mute); Spacer(Modifier.width(8.dp))
            BasicTextField(q, { q = it }, textStyle = T.body, cursorBrush = SolidColor(C.Ink), modifier = Modifier.weight(1f), decorationBox = { i -> if (q.isEmpty()) Text("제목·내용 검색", style = T.body.copy(color = C.Mute)); i() })
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(shown, key = { it.id }) { c ->
                val last = all[c.id]?.lastOrNull()
                Column(Modifier.fillMaxWidth().press({ vm.openConversation(c.id) }).padding(vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (c.pinned) { Ico(I.Pin, 14.dp, C.Red); Spacer(Modifier.width(4.dp)) }
                        Text(c.title, style = T.head, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(dt.format(Date(c.updatedAt)), style = T.label)
                        Box(Modifier.size(32.dp).press({ open = if (open == c.id) null else c.id }), contentAlignment = Alignment.Center) { Text("···", style = T.head.copy(color = C.Graphite)) }
                    }
                    last?.let { Text(it.text.lines().first(), style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (open == c.id) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        listOf("고정" to { vm.store.updateConversation(c.id) { it.copy(pinned = !it.pinned) } },
                            "MD" to { vm.export(ctx, c, "md") }, "HTML" to { vm.export(ctx, c, "html") }, "JSON" to { vm.export(ctx, c, "json") },
                            "삭제" to { vm.store.deleteConversation(c.id); if (vm.convId.value == c.id) vm.convId.value = vm.store.conversations.value.firstOrNull()?.id ?: vm.store.newConversation().id }
                        ).forEach { (l, a) -> Text(if (l == "고정" && c.pinned) "고정 해제" else l, style = T.small.copy(color = if (l == "삭제") C.Red else C.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.press(a)) }
                    }
                }
                Hairline()
            }
        }
    }
}

@Composable
fun CockpitScreen(vm: MainVm) {
    val st by vm.h.deepseek.status.collectAsState()
    val diag by vm.h.deepseek.diag.collectAsState()
    Column(Modifier.fillMaxSize().background(C.Ink)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).press({ vm.back() }), contentAlignment = Alignment.Center) { Ico(I.Back, 22.dp, C.OnInk) }
            Column(Modifier.weight(1f)) {
                Text("조종석", style = T.title.copy(color = C.OnInk))
                Label(when (st) { DeepSeekWeb.Status.READY -> "연결됨 · 직접 입력해도 됩니다"; DeepSeekWeb.Status.LOGIN -> "한 번만 로그인해 주세요 · 끝나면 질문이 이어집니다"; DeepSeekWeb.Status.CHALLENGE -> "확인을 한 번만 눌러 주세요"; DeepSeekWeb.Status.OFFLINE -> "네트워크에 연결할 수 없어요"; else -> diag.ifBlank { "여는 중" } }, color = if (st == DeepSeekWeb.Status.READY) C.OnInkMute else C.Red)
            }
            Text("새 대화", style = T.small.copy(color = C.OnInk, fontWeight = FontWeight.Bold), modifier = Modifier.press({ vm.h.deepseek.newChat() }).padding(8.dp))
            Text("새로고침", style = T.small.copy(color = C.OnInk, fontWeight = FontWeight.Bold), modifier = Modifier.press({ vm.h.deepseek.reload() }).padding(8.dp))
        }
        Text("AI가 지금 쓰는 DeepSeek 화면 그대로입니다. 로그인·확인처럼 사람만 할 수 있는 일은 여기서 하고, 작업 중에는 지켜보거나 직접 개입할 수 있어요.",
            style = T.small.copy(color = C.OnInkMute, fontSize = 12.sp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(C.Paper)) {
            if (androidx.compose.ui.platform.LocalInspectionMode.current || !Motion.enabled) Text("DeepSeek 화면", style = T.small, modifier = Modifier.align(Alignment.Center))
            else AndroidView(factory = { c -> FrameLayout(c).also { f -> f.layoutParams = ViewGroup.LayoutParams(-1, -1); vm.h.deepseek.attach(f) } }, modifier = Modifier.fillMaxSize(),
                onRelease = { f -> f.removeAllViews(); vm.h.deepseek.park() })
        }
    }
}

@Composable
fun MemoryScreen(vm: MainVm) {
    val mems by vm.store.memories.collectAsState()
    var add by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar("기억", "${mems.size}개 · 신경망 없이 BM25로 찾습니다", onBack = { vm.back() })
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(add, { add = it }, textStyle = T.body, cursorBrush = SolidColor(C.Ink), modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.Paper2).padding(12.dp),
                decorationBox = { i -> if (add.isEmpty()) Text("예: 나는 무료 공모전만 원해", style = T.body.copy(color = C.Mute)); i() })
            Spacer(Modifier.width(8.dp)); InkButton("기억") { if (add.isNotBlank()) { vm.store.memories.value = vm.store.memories.value + com.ht.hjtk.core.Memory(vm.store.id("mem"), add, "직접", System.currentTimeMillis()); vm.store.persistHub(); add = "" } }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(mems.reversed(), key = { it.id }) { m ->
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Label(m.tag); Text(m.text, style = T.body) }
                    Box(Modifier.size(36.dp).press({ vm.store.memories.value = vm.store.memories.value.filter { it.id != m.id }; vm.store.persistHub() }), contentAlignment = Alignment.Center) { Ico(I.Trash, 18.dp, C.Graphite) }
                }
                Hairline()
            }
        }
    }
}
