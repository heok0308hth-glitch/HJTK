package com.ht.hjtk.ui

import com.ht.hjtk.core.I18n
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.R
import com.ht.hjtk.core.command.CommandCatalog
import com.ht.hjtk.data.JobRec
import com.ht.hjtk.data.Msg
import com.ht.hjtk.data.Role
import com.ht.hjtk.engine.Clip
import com.ht.hjtk.engine.DeepSeekWeb
import kotlinx.coroutines.delay
import com.ht.hjtk.engine.LiveBus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)

@Composable
fun ChatScreen(vm: MainVm) {
    val cid by vm.convId.collectAsState()
    val all by vm.store.messages.collectAsState()
    val convs by vm.store.conversations.collectAsState()
    val jobs by vm.store.jobs.collectAsState()
    val busy by vm.runner.busyQuick.collectAsState()
    val ds by vm.h.deepseek.status.collectAsState()
    val msgs = all[cid] ?: emptyList()
    val conv = convs.firstOrNull { it.id == cid }
    val activeJob = jobs.firstOrNull { it.convId == cid && (it.running || it.state == "SUSPENDED") }
    val list = rememberLazyListState()
    val ime = imeOpen()
    val thinking = cid in busy
    // 바닥 근처에 있을 때만 따라 내려간다. 위로 올려 읽는 중이면 끌어내리지 않고 '최신' 버튼에 개수만 쌓는다.
    val atBottom by remember { derivedStateOf { val li = list.layoutInfo; (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 2 } }
    var unseen by remember(cid) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(cid) { if (msgs.isNotEmpty()) list.scrollToItem(msgs.size) }
    LaunchedEffect(msgs.size, thinking) {
        if (msgs.isEmpty()) return@LaunchedEffect
        if (msgs.last().role == Role.USER || atBottom) { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)); unseen = 0 } else unseen++
    }
    LaunchedEffect(ime) { if (ime && msgs.isNotEmpty()) list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
    LaunchedEffect(atBottom) { if (atBottom) unseen = 0 }

    Column(Modifier.fillMaxSize()) {
        // ── 머리 ──
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).press({ vm.go(Route.Conversations) }), contentAlignment = Alignment.Center) { Ico(I.Menu, 22.dp) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(conv?.title ?: "새 대화", style = T.head, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 클로드처럼: 모델 이름을 누르면 고르기 판. DeepSeek 웹이면 로그인 상태를 함께 보여 준다
                val aiSt by vm.h.ai.state.collectAsState()
                val chatA = remember(aiSt, ds) { vm.h.ai.chatAssignment() }
                val web = chatA == null || chatA.member.transport == com.ht.hjtk.core.ai.Transport.WEB
                val warn = web && (ds == DeepSeekWeb.Status.LOGIN || ds == DeepSeekWeb.Status.CHALLENGE)
                Row(Modifier.press({ if (warn) vm.go(Route.Cockpit) else vm.pickerOpen.value = true }), verticalAlignment = Alignment.CenterVertically) {
                    SignalDot(if (warn) C.Red else if (!web || ds == DeepSeekWeb.Status.READY) C.Ink else C.Mute, 6.dp, pulse = warn)
                    Spacer(Modifier.width(6.dp))
                    Label((if (aiSt.chatPin == "auto") "자동 · " else "") + (chatA?.name ?: "AI 연결 필요") + if (warn) " · 로그인 필요" else "", color = if (warn) C.Red else C.Mute)
                    Spacer(Modifier.width(3.dp)); Ico(I.Down, 12.dp, C.Mute)
                }
            }
            Box(Modifier.size(40.dp).press({ vm.newChat() }), contentAlignment = Alignment.Center) { Ico(I.Plus, 22.dp) }
            Box(Modifier.size(40.dp).press({ vm.go(Route.Settings) }), contentAlignment = Alignment.Center) { Ico(I.Gear, 21.dp) }
        }
        Hairline()

        activeJob?.let { if (ime) SignalSlim(vm, it) else SignalPanel(vm, it) }

        Box(Modifier.weight(1f)) {
            if (msgs.isEmpty()) { if (ime) Spacer(Modifier.fillMaxSize()) else EmptyChat(vm) }
            else LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                val lastAi = msgs.lastOrNull { it.role == Role.AI && it.kind == "text" }?.id
                items(msgs, key = { it.id }, contentType = { it.kind }) { m -> MessageView(vm, m, actions = m.id == lastAi && !thinking); Spacer(Modifier.height(10.dp)) }
                if (thinking) item(key = "thinking", contentType = "thinking") { Thinking(vm, cid) }
            }
            if (!atBottom && msgs.size > 4) Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(50)).background(C.Ink)
                    .press({ scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } }).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Ico(I.Down, 14.dp, C.OnInk); Spacer(Modifier.width(6.dp))
                Text(if (unseen > 0) "새 메시지 $unseen" else "최신으로", style = T.small.copy(color = C.OnInk, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp))
            }
        }
        Composer(vm, ime)
    }
}

@Composable
private fun Thinking(vm: MainVm, cid: String) {
    // 경과 초를 보여 준다: '멈췄나?'와 '생각 중'을 사용자가 구분할 수 있게
    var sec by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { if (Motion.enabled) while (true) { delay(1000); sec++ } }
    val lines by LiveBus.lines.collectAsState()
    val line = lines[cid]
    val text = line?.text?.let { remember(it) { com.ht.hjtk.core.stream.AepView.clean(it) } } ?: ""
    Column(Modifier.fillMaxWidth().padding(end = 24.dp, top = 2.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalDot(C.Ink, 6.dp, pulse = true); Spacer(Modifier.width(8.dp))
            val who = line?.who ?: vm.h.ai.chatAssignment()?.name ?: "AI"
            Label(when { text.isNotEmpty() -> "$who · 받는 중"; sec < 3 -> "${who}에게 묻는 중"; else -> "${who}에게 묻는 중 · ${sec}초" })
        }
        // 글자가 도착하는 대로 그린다(완료되면 같은 자리에 정식 메시지로 바뀐다)
        if (text.isNotEmpty()) { Spacer(Modifier.height(4.dp)); MdText(text) }
    }
}

@Composable
private fun EmptyChat(vm: MainVm) {
    val ideas = listOf(
        "공모전 찾아서 준비해줘" to "검색 → 마감순 표 → 고르면 초안까지",
        "오늘 일정이랑 날씨, 안 읽은 메일 정리해줘" to "캘린더·날씨·메일을 모아 5줄 브리핑",
        "다음 주 서울 전시회 3개 추천하고 일정에 넣어줘" to "웹 검색 + 일정 추가(허락 후)",
        "/계산 1,250,000*0.033" to "AI 없이 바로 실행되는 명령어"
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.Bottom) {
            Image(painterResource(R.drawable.hjtk_mark_ink), null, Modifier.size(54.dp))
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Text(I18n.t("무엇을 맡길까요?"), style = T.display)
        Spacer(Modifier.height(6.dp))
        Text("HJTK 안에는 AI가 없어요. 폰의 DeepSeek에게 일을 시키고, 명령어 ${CommandCatalog.ALL.size}개로 실행하고, 5분마다 보고합니다.", style = T.small)
        Spacer(Modifier.height(18.dp))
        Hairline(color = C.Ink)
        ideas.forEachIndexed { i, (t, s) ->
            NumRow(i + 1, t, s, onClick = { vm.draft.value = t })
            if (i < ideas.lastIndex) Hairline()
        }
    }
}

/** 키보드가 열렸을 때의 작업 신호: 한 줄(36dp)로 접는다 */
@Composable
private fun SignalSlim(vm: MainVm, j: JobRec) {
    val needs = j.state == "PAUSED_APPROVAL" || j.state == "PAUSED_INPUT"
    Column(Modifier.fillMaxWidth().background(C.Ink).press({ vm.go(Route.Job(j.id)) })) {
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SignalDot(C.Red, 6.dp, pulse = true); Spacer(Modifier.width(8.dp))
            Text(if (needs) "당신이 필요해요 · ${j.label}" else j.now.ifBlank { j.goal }, style = T.small.copy(color = C.OnInk, fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (j.eta.isNotBlank()) Label(j.eta, color = C.OnInkMute)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.Ink3)) { Box(Modifier.fillMaxWidth(j.progress.toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(C.Red)) }
    }
}

/** 진행 중 작업: 먹색 신호 패널 — 지금 / 다음 / 남은 시간 / 중단·재개 */
@Composable
private fun SignalPanel(vm: MainVm, j: JobRec) {
    var open by remember(j.id) { mutableStateOf(false) }
    val paused = j.state.startsWith("PAUSED") || j.state == "SUSPENDED"
    val needs = j.state == "PAUSED_APPROVAL" || j.state == "PAUSED_INPUT"
    FoldCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), ink = true) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.press({ open = !open })) {
            SignalDot(if (needs) C.Red else if (paused) C.OnInkMute else C.Red, 7.dp, pulse = !paused || needs)
            Spacer(Modifier.width(8.dp))
            Label(when { needs -> "당신이 필요해요 · ${j.label}"; j.state == "SUSPENDED" -> "멈춤 · 재개 가능"; paused -> "일시정지"; else -> "작업 중 · ${j.via.ifBlank { "준비" }}" }, color = if (needs) C.Red else C.OnInkMute)
            Spacer(Modifier.weight(1f))
            Label("보고 ${j.reports} · 명령 ${j.cmds}", color = C.OnInkMute)
        }
        Spacer(Modifier.height(8.dp))
        Text(j.goal, style = T.head.copy(color = C.OnInk), maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        ProgressHair(j.progress.toFloat())
        if (!open) Row(Modifier.padding(top = 8.dp).press({ open = true }), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(j.now.takeIf { it.isNotBlank() }?.let { "지금 $it" }, j.eta.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "계획을 세우는 중" },
                style = T.small.copy(color = C.OnInkMute, fontSize = 12.5.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (j.state == "SUSPENDED") Text(I18n.t("이어서 진행"), style = T.small.copy(color = C.Red, fontWeight = FontWeight.Bold), modifier = Modifier.press({ vm.runner.resume(j.id) }).padding(start = 8.dp))
            else Text(I18n.t("펼치기"), style = T.small.copy(color = C.OnInk, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 8.dp))
        }
        // 지금 AI가 쓰고 있는 글(접혀 있어도 보인다 — 클로드처럼 진행이 눈에 보이게)
        LiveWriting(j.id, dark = true)
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 10.dp)) {
                val liveAll by LiveBus.lines.collectAsState()
                if (j.team.isNotBlank()) { CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides C.OnInk) { TeamBoardDark(j.team, j.tokens, liveAll, j.id) }; Spacer(Modifier.height(8.dp)) }
                if (j.now.isNotBlank()) KV(I18n.t("지금"), j.now)
                if (j.next.isNotBlank()) KV(I18n.t("다음"), j.next)
                KV(I18n.t("남은 시간"), j.eta.ifBlank { "계획을 세우는 중" })
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (j.state == "SUSPENDED") InkButton(I18n.t("이어서 진행"), accent = true) { vm.runner.resume(j.id) }
                    else if (paused && !needs) InkButton(I18n.t("재개"), accent = true) { vm.runner.unpause(j.id) }
                    else if (!needs) GhostButton(I18n.t("일시정지"), onInk = true) { vm.runner.pause(j.id) }
                    GhostButton(I18n.t("지금 보고"), onInk = true) { vm.runner.reportNow(j.id) }
                    GhostButton(I18n.t("조종석"), onInk = true) { vm.go(Route.Cockpit) }
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, C.Ink3, RoundedCornerShape(6.dp)).press({ vm.runner.abort(j.id) }), contentAlignment = Alignment.Center) { Ico(I.Stop, 18.dp, C.OnInk) }
                }
            }
        }
    }
}

/** 먹색 패널용 팀 표 */
@Composable
private fun TeamBoardDark(team: String, tokens: Int, live: Map<String, LiveBus.Line>, jobId: String) {
    val rows = team.lines().filter { it.contains("=") }.map { it.substringBefore("=") to it.substringAfter("=").substringBefore(" · ") }
    Row(verticalAlignment = Alignment.CenterVertically) { Label(I18n.t("AI 팀"), color = C.OnInkMute, modifier = Modifier.weight(1f)); if (tokens > 0) Label("토큰 약 ${com.ht.hjtk.core.ai.Tokens.fmt(tokens)}", color = C.OnInkMute) }
    rows.forEach { (role, name) ->
        val busy = live.values.any { it.owner.startsWith(jobId) && it.who.startsWith(name.substringBefore(" (")) }
        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(role, style = T.small.copy(color = C.OnInk, fontWeight = FontWeight.Bold), modifier = Modifier.width(44.dp))
            Text(name, style = T.small.copy(color = C.OnInk), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (busy) { SignalDot(C.Red, 6.dp, pulse = true); Spacer(Modifier.width(4.dp)); Label(I18n.t("쓰는 중"), color = C.Red) }
        }
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(k, style = T.small.copy(color = C.OnInkMute), modifier = Modifier.width(64.dp))
        Text(v, style = T.small.copy(color = C.OnInk), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MessageView(vm: MainVm, m: Msg, actions: Boolean = false) {
    when (m.kind) {
        "cmd" -> CmdRow(m)
        "report" -> ReportCard(m)
        "approval" -> ApprovalCard(vm, m)
        "ask" -> AskCard(vm, m)
        "takeover" -> TakeoverCard(vm, m)
        "final" -> FinalCard(vm, m)
        "card" -> FoldCard(Modifier.fillMaxWidth()) { Label(m.meta["title"] ?: "카드"); Spacer(Modifier.height(6.dp)); MdText(m.text) }
        "artifact" -> ArtifactRow(vm, m)
        "screen" -> ScreenCard(m)
        "image" -> ImageBubble(m)
        "note" -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(14.dp).height(1.dp).background(C.Hair)); Spacer(Modifier.width(8.dp)); Text(m.text, style = T.small.copy(fontSize = 12.sp, color = C.Mute))
        }
        "error" -> ErrorRow(vm, m)
        "collab" -> CollabRow(m)
        else -> if (m.role == Role.USER) UserBubble(m) else AiText(vm, m, actions)
    }
}

@Composable
private fun UserBubble(m: Msg) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 300.dp)) {
            if (m.meta["mission"] == "true" || m.meta["expanded"] != null) Label(if (m.meta["expanded"] != null) "사용자 명령 → 작업" else "작업으로 맡김", Modifier.padding(bottom = 4.dp), C.Red)
            m.meta["image"]?.let { Thumb(it, 220.dp); if (m.text.isNotBlank()) Spacer(Modifier.height(4.dp)) }
            if (m.text.isNotBlank()) Box(Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(C.Ink).padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer { Text(m.text, style = T.body.copy(color = C.OnInk)) }
            }
        }
    }
}

@Composable
private fun AiText(vm: MainVm, m: Msg, actions: Boolean) {
    Column(Modifier.fillMaxWidth().padding(end = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.hjtk_mark_ink), null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
            Label(listOfNotNull(m.meta["who"] ?: whoOf(m.meta["via"]), viaOf(m.meta["via"]), m.meta["step"]?.let { "#$it" }).joinToString(" · "))
            Spacer(Modifier.width(8.dp)); Text(hm.format(Date(m.at)), style = T.label)
        }
        Spacer(Modifier.height(4.dp))
        val shown = remember(m.text) { com.ht.hjtk.core.stream.AepView.clean(m.text) }   // 저장된 옛 메시지의 봉투도 걸러 낸다
        SelectionContainer { MdText(shown) }   // 길게 눌러 원하는 부분만 복사
        if (actions) AnswerActions(vm, shown)
    }
}

/**
 * AI끼리 협업한 기록 한 줄. 역할마다 왼쪽 표지: 작성 쪽(구상·초안·수정)은 먹색, 검토 쪽(선택·검토·검증)은 주홍.
 * 접힌 상태로 두 줄만, 누르면 펼친다.
 */
@Composable
private fun CollabRow(m: Msg) {
    val role = m.meta["role"].orEmpty(); val who = m.meta["who"].orEmpty()
    val reviewer = role in setOf("선택", "검토", "검증")
    var open by remember(m.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().press({ open = !open }).padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 3.dp).width(3.dp).height(if (open) 40.dp else 30.dp).background(if (reviewer) C.Red else C.Ink))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(role, style = T.small.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = if (reviewer) C.Red else C.Ink))
                Spacer(Modifier.width(6.dp)); Label(who)
            }
            Text(m.text, style = T.small.copy(fontSize = 12.5.sp, color = C.Graphite), maxLines = if (open) 30 else 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 작업공간의 사진을 작게 읽어 그린다(메모리: 긴 변 600px 이하로 표본 추출) */
@Composable
fun Thumb(path: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val bmp = remember(path) {
        runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, o)
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 600) s *= 2
            android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, modifier.heightIn(max = size).clip(RoundedCornerShape(10.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    else Box(modifier.size(size / 2).clip(RoundedCornerShape(10.dp)).background(C.Paper2), contentAlignment = Alignment.Center) { Label("사진") }
}

@Composable
private fun ImageBubble(m: Msg) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        m.meta["image"]?.let { Thumb(it, 220.dp) }
        if (m.text.isNotBlank()) { Spacer(Modifier.height(4.dp)); Label(m.text) }
    }
}

/** 가장 최근 답에만: 복사 · 공유 · 다시 묻기 · 기억하기 (모든 답에 달면 화면이 시끄럽다) */
@Composable
private fun AnswerActions(vm: MainVm, text: String) {
    val ctx = LocalContext.current
    var done by remember(text) { mutableStateOf<String?>(null) }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun act(label: String, f: () -> Unit) = Text(if (done == label) "$label ✓" else label, style = T.small.copy(fontSize = 12.sp, color = if (done == label) C.Ink else C.Graphite, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clip(RoundedCornerShape(5.dp)).press({ f(); done = label }).padding(horizontal = 8.dp, vertical = 6.dp))
        act(I18n.t("복사")) { ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("HJTK", text)) }
        act(I18n.t("공유")) { ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), "답 공유").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        act(I18n.t("다시 묻기")) { vm.retryLast() }
        act(I18n.t("기억하기")) { vm.remember(text) }
    }
}

/** 명령 실행 행: ▸ RUN web.search  q=…  ✓ 0.8s  (탭하면 결과) */
@Composable
private fun CmdRow(m: Msg) {
    var open by remember { mutableStateOf(false) }
    val ok = m.meta["ok"] == "true"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, C.Hair, RoundedCornerShape(6.dp)).press({ open = !open }).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (m.meta["manual"] == "true") "▸ 직접" else "▸ 실행", style = T.label.copy(color = C.Ink))
            Spacer(Modifier.width(8.dp))
            Text(m.meta["cmd"] ?: "", style = T.mono.copy(fontWeight = FontWeight.SemiBold, color = C.Ink))
            Spacer(Modifier.width(8.dp))
            Text(m.text, style = T.small.copy(fontSize = 12.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Ico(if (ok) I.Check else I.Close, 14.dp, if (ok) C.Ink else C.Red)
            Spacer(Modifier.width(4.dp))
            Text("${((m.meta["ms"]?.toLongOrNull() ?: 0) / 100) / 10.0}s", style = T.label)
        }
        if (!m.meta["args"].isNullOrBlank()) Text(m.meta["args"]!!, style = T.mono.copy(fontSize = 11.sp, color = C.Mute), maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
        if (open || m.meta["manual"] == "true") {
            Spacer(Modifier.height(6.dp)); Hairline(); Spacer(Modifier.height(6.dp))
            Text(m.meta["out"] ?: "", style = T.mono.copy(fontSize = 11.5.sp), maxLines = if (open) 60 else 12, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ReportCard(m: Msg) {
    val lines = m.text.lines()
    FoldCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalDot(C.Red, 7.dp); Spacer(Modifier.width(8.dp))
            Label("${if (m.meta["ai"] == "true") "AI 보고" else "5분 보고"} #${m.meta["n"] ?: ""} · ${hm.format(Date(m.at))}")
        }
        Spacer(Modifier.height(6.dp))
        Text(lines.first(), style = T.head)
        lines.drop(1).forEach { l ->
            val k = l.substringBefore(':', ""); val v = l.substringAfter(':', l)
            Row(Modifier.padding(top = 3.dp)) {
                if (k.isNotBlank() && k.length <= 6) { Text(k, style = T.small.copy(color = C.Mute), modifier = Modifier.width(52.dp)); Text(v.trim(), style = T.small.copy(color = C.Ink2)) }
                else Text(l, style = T.small.copy(color = if (l.contains("늦었")) C.Red else C.Ink2))
            }
        }
    }
}

/** 허락 카드: 지문 대신 '실행' 버튼. 요약은 정규화된 행동에서 템플릿으로 만든 것(모델이 쓴 문장 아님). */
@Composable
private fun ApprovalCard(vm: MainVm, m: Msg) {
    val st = m.meta["status"]
    FoldCard(Modifier.fillMaxWidth(), ink = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalDot(if (st == "pending") C.Red else C.OnInkMute, 7.dp, pulse = st == "pending")
            Spacer(Modifier.width(8.dp))
            Label(when (st) { "approved" -> "실행함"; "rejected" -> "거절함"; else -> "허락이 필요해요" } + " · ${m.meta["effect"] ?: ""}", color = if (st == "pending") C.Red else C.OnInkMute)
        }
        Spacer(Modifier.height(8.dp))
        Text(m.meta["title"] ?: "", style = T.title.copy(color = C.OnInk, fontSize = 18.sp))
        Spacer(Modifier.height(6.dp))
        Text(m.text, style = T.body.copy(color = C.OnInk, fontSize = 14.sp))
        m.meta["warn"]?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(C.Ink2).padding(10.dp)) { Text("! ", style = T.head.copy(color = C.Red)); Text(it, style = T.small.copy(color = C.OnInk)) }
        }
        Spacer(Modifier.height(8.dp))
        Label("${m.meta["cmd"]} · digest ${m.meta["digest"]}…", color = C.OnInkMute)
        if (st == "pending") {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton(I18n.t("실행"), Modifier.weight(1f), accent = true) { vm.approve(m.meta["pid"]!!, true) }
                GhostButton(I18n.t("거절"), Modifier.weight(1f), onInk = true) { vm.approve(m.meta["pid"]!!, false) }
            }
        }
    }
}

@Composable
private fun AskCard(vm: MainVm, m: Msg) {
    val pending = m.meta["status"] == "pending"
    var free by remember { mutableStateOf("") }
    FoldCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) { SignalDot(if (pending) C.Red else C.Mute, 7.dp, pulse = pending); Spacer(Modifier.width(8.dp)); Label(if (pending) "질문" else "답함", color = if (pending) C.Red else C.Mute) }
        Spacer(Modifier.height(6.dp))
        MdText(m.text)
        if (pending) {
            val choices = m.meta["choices"].orEmpty().split('|').filter { it.isNotBlank() }
            if (choices.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { choices.forEach { c -> Chip(c, false) { vm.answer(m.meta["pid"]!!, c) } } }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(free, { free = it }, textStyle = T.body, cursorBrush = SolidColor(C.Ink),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(C.Paper).padding(10.dp),
                    decorationBox = { inner -> if (free.isEmpty()) Text(I18n.t("직접 답하기 · 답을 붙여넣어도 돼요"), style = T.small); inner() })
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(C.Ink).press({ if (free.isNotBlank()) vm.answer(m.meta["pid"]!!, free) }), contentAlignment = Alignment.Center) { Ico(I.Send, 18.dp, C.OnInk) }
            }
            val ctx = LocalContext.current
            Text(I18n.t("클립보드 붙여넣기"), style = T.small.copy(color = C.Ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp).press({ Clip.read(ctx)?.let { free = it } }))
        } else m.meta["answer"]?.let { Text("→ $it", style = T.small.copy(color = C.Ink2), modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun TakeoverCard(vm: MainVm, m: Msg) {
    val pending = m.meta["status"] == "pending"
    FoldCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) { SignalDot(if (pending) C.Red else C.Mute, 7.dp, pulse = pending); Spacer(Modifier.width(8.dp)); Label(I18n.t("직접 해 주세요"), color = if (pending) C.Red else C.Mute) }
        Spacer(Modifier.height(6.dp)); MdText(m.text)
        if (pending) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val url = m.meta["url"].orEmpty()
                if (url.isNotBlank()) GhostButton(I18n.t("열기")) { vm.runner.openTakeover(url) } else GhostButton(I18n.t("조종석")) { vm.go(Route.Cockpit) }
                InkButton(I18n.t("다 했어요")) { vm.answer(m.meta["pid"]!!, "완료") }
                GhostButton(I18n.t("못 해요")) { vm.answer(m.meta["pid"]!!, "사용자가 할 수 없음") }
            }
        }
    }
}

@Composable
private fun FinalCard(vm: MainVm, m: Msg) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(FoldShape()).background(C.Paper2).border(1.dp, C.Ink, FoldShape()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(C.Ink), contentAlignment = Alignment.Center) { Ico(I.Check, 14.dp, C.OnInk) }
            Spacer(Modifier.width(8.dp)); Label("결과 · ${m.meta["label"] ?: "완료"}", color = C.Ink)
        }
        Spacer(Modifier.height(10.dp))
        MdText(m.text)
        Spacer(Modifier.height(12.dp)); Hairline(); Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(I18n.t("복사"), style = T.small.copy(color = C.Ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.press({ Clip.copy(ctx, m.text, false) }))
            m.meta["job"]?.let { jid -> Text(I18n.t("작업 보기"), style = T.small.copy(color = C.Ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.press({ vm.go(Route.Job(jid)) })) }
        }
    }
}

@Composable
private fun ArtifactRow(vm: MainVm, m: Msg) {
    val ctx = LocalContext.current
    val f = java.io.File(vm.store.jobDir(m.meta["job"] ?: "manual"), m.meta["file"] ?: "")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(C.Paper2).press({ if (f.exists()) vm.openFile(ctx, f) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Ico(I.File, 20.dp); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Label(I18n.t("결과물")); Text(m.meta["file"] ?: "", style = T.head.copy(fontSize = 14.sp)) }
        Ico(I.Export, 18.dp)
    }
}

@Composable
private fun ScreenCard(m: Msg) {
    FoldCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(C.Paper2), contentAlignment = Alignment.Center) { Ico(I.Eye, 20.dp) }; Spacer(Modifier.width(10.dp))
            Column { Label(I18n.t("화면 공유 요청"), color = C.Red); Text(m.text, style = T.small.copy(color = C.Ink2)) }
        }
    }
}

@Composable
private fun ErrorRow(vm: MainVm, m: Msg) {
    val via = m.meta["via"].orEmpty()
    val waiting = m.meta["waiting"] == "1"
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(C.RedSoft).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp)) { SignalDot(C.Red, pulse = waiting) }
            Spacer(Modifier.width(10.dp))
            Text(m.text, style = T.small.copy(color = C.Ink), modifier = Modifier.weight(1f))
        }
        val cockpit = via in setOf("NEEDS_LOGIN", "CHALLENGE", "NOT_READY", "NONE", "P1_DOM", "OFFLINE", "RENDERER_GONE") || via.startsWith("INPUT_")
        val retry = via in setOf("OFFLINE", "NOT_READY", "RENDERER_GONE", "EXCEPTION", "TIMEOUT", "NONE") || via.startsWith("INPUT_")
        val copy = via == "CRASH"
        if (cockpit || retry || copy) Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (waiting) Label(I18n.t("로그인 기다리는 중"), color = C.Red, modifier = Modifier.padding(vertical = 8.dp).weight(1f)) else Spacer(Modifier.weight(1f))
            if (copy) ErrAction(I18n.t("복사")) {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("HJTK crash", m.meta["detail"] ?: m.text))
            }
            if (retry) ErrAction(I18n.t("다시 보내기")) { vm.retryLast() }
            if (cockpit) ErrAction(if (via == "NEEDS_LOGIN") "로그인하기" else "조종석", strong = true) { vm.go(Route.Cockpit) }
        }
    }
}

@Composable
private fun ErrAction(label: String, strong: Boolean = false, onClick: () -> Unit) {
    Text(label, style = T.small.copy(color = if (strong) C.OnInk else C.Ink, fontWeight = FontWeight.Bold),
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (strong) C.Ink else Color.Transparent).press(onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

/** 의존성 없는 가벼운 마크다운 렌더(제목·목록·굵게·코드·표는 고정폭) */
@Composable
fun MdText(text: String, color: Color = C.Ink) {
    val lines = remember(text) { text.trim().lines() }
    Column {
        var inCode = false
        val code = StringBuilder()
        val table = ArrayList<List<String>>()
        @Composable fun flushTable() {
            if (table.isEmpty()) return
            val rows = table.filter { r -> !r.all { it.trim().matches(Regex(":?-{2,}:?")) } }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, C.Hair, RoundedCornerShape(4.dp))) {
                rows.forEachIndexed { ri, r ->
                    Row(Modifier.fillMaxWidth().background(if (ri == 0) C.Paper3 else C.Paper).padding(horizontal = 8.dp, vertical = 6.dp)) {
                        r.forEach { cell -> Text(inline(cell.trim()), style = (if (ri == 0) T.label.copy(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 0.sp, color = C.Ink) else T.small.copy(color = color, fontSize = 13.sp)), modifier = Modifier.weight(1f).padding(end = 6.dp)) }
                    }
                    if (ri < rows.lastIndex) Hairline()
                }
            }
            table.clear()
        }
        for (l in lines) {
            if (l.trim().startsWith("|") && !inCode) { table += l.trim().trim('|').split('|'); continue } else flushTable()
            if (l.trim().startsWith("```")) {
                if (inCode) { Text(code.toString().trimEnd(), style = T.mono.copy(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(4.dp)).background(C.Paper3).padding(10.dp)); code.setLength(0) }
                inCode = !inCode; continue
            }
            if (inCode) { code.appendLine(l); continue }
            val t = l.trimEnd()
            when {
                t.isBlank() -> Spacer(Modifier.height(6.dp))
                t.startsWith("#") -> Text(inline(t.trimStart('#').trim()), style = T.head.copy(color = color, fontSize = if (t.startsWith("##")) 15.sp else 17.sp), modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                t.trimStart().startsWith("- ") || t.trimStart().startsWith("* ") || t.trimStart().startsWith("• ") ->
                    Row(Modifier.padding(start = ((t.length - t.trimStart().length) * 4).dp, top = 1.dp)) { Text("—  ", style = T.body.copy(color = C.Mute)); Text(inline(t.trimStart().drop(2)), style = T.body.copy(color = color)) }
                t.trimStart().matches(Regex("^\\d+[.)] .*")) -> Row(Modifier.padding(top = 1.dp)) { Text(t.trimStart().substringBefore(' ').padEnd(4), style = T.num.copy(color = C.Graphite, fontSize = 13.sp)); Text(inline(t.trimStart().substringAfter(' ')), style = T.body.copy(color = color)) }
                t.startsWith(">") -> Row(Modifier.padding(vertical = 2.dp)) { Box(Modifier.width(2.dp).height(20.dp).background(C.Red)); Spacer(Modifier.width(8.dp)); Text(inline(t.drop(1).trim()), style = T.body.copy(color = C.Graphite)) }
                else -> Text(inline(t), style = T.body.copy(color = color))
            }
        }
        flushTable()
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val rx = Regex("\\*\\*([^*]+)\\*\\*|`([^`]+)`|\\[([^]]+)]\\((https?://[^)]+)\\)")
    for (m in rx.findAll(s)) {
        append(s.substring(i, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = C.Paper3)) { append(m.groupValues[2]) }
            else -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = C.Ink)) { append(m.groupValues[3]); append(" ↗") }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

@Composable
private fun Composer(vm: MainVm, ime: Boolean) {
    val text by vm.draft.collectAsState()
    val mode by vm.mode.collectAsState()
    val slash = text.startsWith("/") && !text.contains(' ')
    val at = text.startsWith("@") && !text.contains(' ')
    val suggestions = remember(text) { if (slash) CommandCatalog.search(text.drop(1)).take(if (ime) 3 else 5) else emptyList() }
    val customs by vm.store.customs.collectAsState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().background(C.Paper).navigationBarsPadding()) {
        Hairline()
        if (at) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                listOf("@제미나이" to "Gemini에게 직접 묻기", "@함께" to "DeepSeek 초안 → 다른 AI 검토 → 종합 답").forEach { (k, d) ->
                    Row(Modifier.fillMaxWidth().press({ vm.draft.value = "$k " }).padding(vertical = 7.dp)) {
                        Text(k, style = T.mono.copy(fontWeight = FontWeight.Bold, color = C.Red), modifier = Modifier.width(110.dp)); Text(d, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Hairline()
        }
        if (suggestions.isNotEmpty() || slash) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                customs.filter { it.trigger.startsWith(text) }.take(2).forEach { c ->
                    Row(Modifier.fillMaxWidth().press({ vm.draft.value = c.trigger + " " }).padding(vertical = 7.dp)) { Text(c.trigger, style = T.mono.copy(fontWeight = FontWeight.Bold, color = C.Red), modifier = Modifier.width(110.dp)); Text(c.expandsTo, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                suggestions.forEach { s ->
                    Row(Modifier.fillMaxWidth().press({ vm.draft.value = "/" + (s.aliases.firstOrNull() ?: s.id) + " " }).padding(vertical = 7.dp)) {
                        Text("/" + (s.aliases.firstOrNull() ?: s.id), style = T.mono.copy(fontWeight = FontWeight.Bold, color = C.Ink), modifier = Modifier.width(110.dp))
                        Text("${s.title} · ${s.id}", style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Hairline()
        }
        val pimg by vm.pendingImage.collectAsState()
        pimg?.let { path ->
            Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(path, 64.dp)
                Spacer(Modifier.width(10.dp))
                Label(I18n.t("사진을 함께 보내요"), modifier = Modifier.weight(1f))
                Text("✕", style = T.body, modifier = Modifier.press({ vm.pendingImage.value = null }).padding(8.dp))
            }
        }
        if (mode == Mode.MISSION) DifficultyRow(vm)
        // 키보드가 닫혀 있을 때만 모드 칩 줄을 펼친다. 열리면 왼쪽 버튼 하나가 모드 전환을 맡는다(세로 44dp 절약).
        if (!ime) Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Mode.values().forEach { md -> Chip(md.label, mode == md) { vm.mode.value = md } }
            Spacer(Modifier.weight(1f))
            if (mode == Mode.AUTO && text.isNotBlank()) Label(if (com.ht.hjtk.core.InputRouter.looksLikeMission(text)) "→ 작업으로" else "→ 대화로", color = C.Graphite)
        }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = if (ime) 8.dp else 12.dp, bottom = if (ime) 8.dp else 12.dp), verticalAlignment = Alignment.Bottom) {
            if (ime) {
                val auto = if (mode == Mode.AUTO && text.isNotBlank()) (if (com.ht.hjtk.core.InputRouter.looksLikeMission(text)) "작업" else "대화") else null
                Column(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, if (mode == Mode.MISSION) C.Red else C.Hair, RoundedCornerShape(8.dp))
                    .press({ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); vm.mode.value = Mode.values()[(mode.ordinal + 1) % Mode.values().size] }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(when (mode) { Mode.AUTO -> "자동"; Mode.QUICK -> "대화"; Mode.MISSION -> "작업" }, style = T.small.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (mode == Mode.MISSION) C.Red else C.Ink))
                    if (auto != null) Text("→$auto", style = T.label.copy(fontFamily = Pretendard, fontSize = 9.sp, letterSpacing = 0.sp, color = C.Mute))
                }
            } else Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, C.Hair, RoundedCornerShape(8.dp)).press({ vm.paletteOpen.value = true }), contentAlignment = Alignment.Center) { Ico(I.Slash, 20.dp) }
            Spacer(Modifier.width(8.dp))
            BasicTextField(text, { vm.draft.value = it }, textStyle = T.body, cursorBrush = SolidColor(C.Ink), maxLines = if (ime) 4 else 6,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(C.Paper2).padding(horizontal = 12.dp, vertical = 11.dp)
                    // 하드웨어 키보드(PC·태블릿): Enter 보내기 · Shift+Enter 줄바꿈
                    .onPreviewKeyEvent { e -> if (e.key == androidx.compose.ui.input.key.Key.Enter && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && !e.isShiftPressed && text.isNotBlank()) { vm.send(text); true } else false },
                decorationBox = { inner -> if (text.isEmpty()) Text(if (mode == Mode.MISSION) "맡길 일을 적어 주세요 — 오래 걸려도 괜찮아요" else "메시지 · / 로 명령어", style = T.body.copy(color = C.Mute), maxLines = 1, overflow = TextOverflow.Ellipsis); inner() })
            Spacer(Modifier.width(6.dp))
            val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { u -> u?.let { vm.attachImage(it) } }
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).press({ pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }), contentAlignment = Alignment.Center) { Ico(I.Image, 21.dp) }
            Spacer(Modifier.width(4.dp))
            val ready = text.isNotBlank() || pimg != null
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(if (!ready) C.Paper3 else if (mode == Mode.MISSION) C.Red else C.Ink)
                .press({ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); vm.send(text) }, enabled = ready), contentAlignment = Alignment.Center) {
                Ico(I.Send, 20.dp, if (!ready) C.Mute else C.OnInk)
            }
        }
    }
}

/** 답의 출처를 사람 말로: 내부 경로 코드(P0_STREAM, API_CLAUDE…)를 그대로 보이지 않는다 */
internal fun whoOf(via: String?): String = when {
    via == null -> "DeepSeek"
    via.startsWith("API_") && via != "API_ERR" -> com.ht.hjtk.core.ai.Provider.values().firstOrNull { it.name == via.removePrefix("API_") }?.label ?: "DeepSeek"
    else -> "DeepSeek"
}
internal fun viaOf(via: String?): String? = when {
    via.isNullOrBlank() -> null
    via.startsWith("P0") || via.startsWith("P1") || via.startsWith("P2") -> I18n.t("웹")
    via.startsWith("API") || via == "L1_API" -> "API"
    via.startsWith("APP") || via.startsWith("RELAY") -> I18n.t("앱")
    else -> null
}
