package com.ht.hjtk.ui

import com.ht.hjtk.core.I18n
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.core.ai.Difficulty
import com.ht.hjtk.core.ai.Member
import com.ht.hjtk.core.ai.ModelCatalog
import com.ht.hjtk.core.ai.ModelRouter
import com.ht.hjtk.core.ai.Provider
import com.ht.hjtk.core.ai.Role
import com.ht.hjtk.core.ai.Tier
import com.ht.hjtk.core.ai.Tokens
import com.ht.hjtk.core.ai.Transport
import com.ht.hjtk.engine.LiveBus
import kotlinx.coroutines.launch

/** 회사 표지: 이모지·로고 대신 글자 한 자 + 고유 먹색 농도(브랜드를 흉내 내지 않는다) */
@Composable
fun ProviderMark(p: Provider, size: androidx.compose.ui.unit.Dp = 28.dp) {
    val shade = when (p) { Provider.DEEPSEEK -> C.Ink; Provider.CLAUDE -> C.Ink2; Provider.GPT -> C.Graphite; Provider.GEMINI -> C.Ink3; Provider.GROK -> C.Mute }
    Box(Modifier.size(size).clip(RoundedCornerShape(7.dp)).background(shade), contentAlignment = Alignment.Center) {
        Text(p.label.take(1), style = T.small.copy(color = C.OnInk, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.46f).sp))
    }
}

/** 클로드 앱처럼: 머리의 모델 이름을 누르면 아래에서 올라오는 고르기 판 */
@Composable
fun ModelPickerSheet(vm: MainVm) {
    val open by vm.pickerOpen.collectAsState()
    if (!open) return
    val st by vm.h.ai.state.collectAsState()
    val logged by com.ht.hjtk.engine.WebAiHub.loggedIn.collectAsState()
    val style by com.ht.hjtk.engine.ChatStyle.state.collectAsState()
    val members = remember(st, logged) { vm.h.ai.members() }
    var sel by remember { mutableStateOf<Provider?>(null) }
    fun pick(v: String) { vm.h.ai.update { it.copy(chatPin = v) }; vm.pickerOpen.value = false; sel = null }
    val pinProvider = members.firstOrNull { it.key == st.chatPin.substringBefore('|') }?.provider
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).press({ vm.pickerOpen.value = false; sel = null })) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 680.dp).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(C.Paper)
            .press({}).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Hair)) }
            Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sel != null) Box(Modifier.size(40.dp).press({ sel = null }), contentAlignment = Alignment.Center) { Ico(I.Back, 20.dp) } else Spacer(Modifier.width(10.dp))
                Text(sel?.label ?: I18n.t("모델"), style = T.title, modifier = Modifier.weight(1f))
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val p = sel
                if (p == null) {
                    PickRow(st.chatPin == "auto", I18n.t("자동"), I18n.t("질문마다 알맞은 AI를 고릅니다"), null) { pick("auto") }
                    Provider.values().forEach { pr ->
                        val mine = members.filter { it.provider == pr }
                        val status = when { mine.any { it.transport == Transport.API } -> "API 키"; mine.any { it.transport == Transport.WEB } -> "로그인됨"; else -> "연결 안 됨" }
                        val cur = if (pinProvider == pr) ModelCatalog.of(pr).firstOrNull { it.id == st.chatPin.substringAfter('|', "") }?.label ?: "${pr.label} 웹" else null
                        Row(Modifier.fillMaxWidth().press({ sel = pr }).background(if (pinProvider == pr) C.Paper2 else Color.Transparent).padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProviderMark(pr, 26.dp); Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pr.label, style = T.head.copy(fontSize = 16.sp))
                                Text(cur ?: status, style = T.small.copy(fontSize = 12.5.sp, color = if (mine.isEmpty()) C.Mute else C.Graphite))
                            }
                            if (pinProvider == pr) Ico(I.Check, 18.dp) else Ico(I.Chevron, 16.dp, C.Mute)
                        }
                    }
                } else {
                    val api = members.firstOrNull { it.provider == p && it.transport == Transport.API }
                    val web = members.firstOrNull { it.provider == p && it.transport == Transport.WEB }
                    val m = api ?: web
                    if (m == null) Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text(I18n.t("아직 연결되지 않았어요. Google 계정 하나로 한 번에 연결할 수 있어요."), style = T.small)
                        Spacer(Modifier.height(10.dp))
                        InkButton(I18n.t("계정으로 연결하기"), Modifier.fillMaxWidth(), accent = true) { vm.pickerOpen.value = false; sel = null; vm.loginTab.value = p; vm.go(Route.AiLogin) }
                    }
                    if (web != null && api == null) Text(I18n.t("키 없이 웹으로 써요 · 고른 모델은 사이트 메뉴에서 대신 골라 드려요"), style = T.small.copy(fontSize = 12.sp, color = C.Graphite), modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                    ModelCatalog.of(p).forEach { md ->
                        val v = m?.let { "${it.key}|${md.id}" }
                        PickRow(v != null && st.chatPin == v, md.label, md.note, md.tier, enabled = m != null) { if (v != null) pick(v) }
                    }
                    if (web != null && api == null && p != Provider.DEEPSEEK) PickRow(st.chatPin == web.key, I18n.t("사이트 기본 모델"), I18n.t("사이트에서 고른 그대로"), null) { pick(web.key) }
                }
            }
            // 노력 · 창의성 · 길이 — 고르는 즉시 다음 답부터 적용
            Hairline()
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StyleRow(I18n.t("노력"), com.ht.hjtk.engine.ChatStyle.EFFORT, style.effort) { com.ht.hjtk.engine.ChatStyle.set(style.copy(effort = it)) }
                StyleRow(I18n.t("창의성"), com.ht.hjtk.engine.ChatStyle.CREATIVE, style.creativity) { com.ht.hjtk.engine.ChatStyle.set(style.copy(creativity = it)) }
                StyleRow(I18n.t("길이"), com.ht.hjtk.engine.ChatStyle.LENGTH, style.length) { com.ht.hjtk.engine.ChatStyle.set(style.copy(length = it)) }
            }
        }
    }
}

@Composable
private fun StyleRow(title: String, options: List<String>, value: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = T.small.copy(color = C.Graphite), modifier = Modifier.width(52.dp))
        Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.Paper2).padding(3.dp)) {
            options.forEachIndexed { i, o ->
                val on = i == value
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) C.Ink else Color.Transparent).press({ onPick(i) }).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(I18n.t(o), style = T.small.copy(fontSize = 12.sp, color = if (on) C.OnInk else C.Ink2, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PickRow(selected: Boolean, title: String, sub: String, tier: Tier?, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().press(onClick).background(if (selected) C.Paper2 else Color.Transparent).padding(start = 18.dp, end = 16.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = T.body.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (enabled) C.Ink else C.Mute))
                tier?.let { Spacer(Modifier.width(6.dp)); Text(it.label, style = T.label.copy(fontFamily = Pretendard, letterSpacing = 0.sp, fontSize = 10.5.sp, color = if (it.rank >= 2) C.Red else C.Mute)) }
            }
            Text(sub, style = T.small.copy(fontSize = 12.sp, color = C.Graphite), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Ico(I.Check, 18.dp)
    }
}

/** 작업 맡기기 모드에서 입력창 위에 붙는 난이도 고르기 */
@Composable
fun DifficultyRow(vm: MainVm) {
    val d by vm.difficulty.collectAsState()
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Difficulty.values().forEach { x ->
            Text(x.label, style = T.small.copy(fontSize = 12.sp, fontWeight = if (x == d) FontWeight.Bold else FontWeight.Normal, color = if (x == d) C.OnInk else C.Ink2),
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (x == d) (if (x == Difficulty.PROJECT) C.Red else C.Ink) else C.Paper2).press({ vm.difficulty.value = x }).padding(horizontal = 10.dp, vertical = 5.dp))
        }
        Spacer(Modifier.width(4.dp))
        Label(d.desc, modifier = Modifier.weight(1f))
    }
    // 프로젝트(회사 모드): 얼마 동안 일할지. 시간이 남으면 팀이 품질을 더 끌어올린다
    if (d == Difficulty.PROJECT) {
        val st by vm.store.settings.collectAsState()
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(I18n.t("시간"))
            Spacer(Modifier.width(2.dp))
            listOf(30 to "30분", 60 to "1시간", 120 to "2시간", 180 to "3시간").forEach { (m, label) ->
                val on = st.projectMinutes == m
                Text(label, style = T.small.copy(fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) C.Red else C.Ink2),
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, if (on) C.Red else C.Hair, RoundedCornerShape(5.dp)).press({ vm.store.updateSettings { it.copy(projectMinutes = m) } }).padding(horizontal = 9.dp, vertical = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            Label(I18n.t("남으면 더 다듬어요"))
        }
    }
}

/** 작업의 AI 팀(역할 · 누가) — 작업 화면과 신호 패널에서 */
@Composable
fun TeamBoard(team: String, tokens: Int, live: Map<String, LiveBus.Line>, jobId: String) {
    val rows = team.lines().filter { it.contains("=") }.map { it.substringBefore("=") to it.substringAfter("=") }
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) { Label(I18n.t("AI 팀"), modifier = Modifier.weight(1f)); if (tokens > 0) Label("토큰 약 ${Tokens.fmt(tokens)}") }
        Spacer(Modifier.height(4.dp))
        rows.forEach { (role, who) ->
            val name = who.substringBefore(" · ")
            val busy = live.values.any { it.owner.startsWith(jobId) && it.who.startsWith(name.substringBefore(" (")) }
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(role, style = T.small.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp), modifier = Modifier.width(44.dp))
                Text(name, style = T.small.copy(fontSize = 12.5.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (busy) { SignalDot(C.Red, 6.dp, pulse = true); Spacer(Modifier.width(4.dp)); Label(I18n.t("쓰는 중"), color = C.Red) }
            }
        }
    }
}

/** 지금 AI가 쓰고 있는 글(클로드처럼 실시간) */
@Composable
fun LiveWriting(jobId: String, dark: Boolean = false) {
    val all by LiveBus.lines.collectAsState()
    val mine = all.values.filter { it.owner.startsWith(jobId) }.sortedByDescending { it.at }
    if (mine.isEmpty()) return
    mine.take(2).forEach { l ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).background(if (dark) C.Ink2 else C.Paper2).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalDot(C.Red, 6.dp, pulse = true); Spacer(Modifier.width(6.dp))
                Label("${l.who} · 쓰는 중 · ${l.text.length}자", color = if (dark) C.OnInkMute else C.Mute)
            }
            Text(l.text.takeLast(320), style = T.small.copy(fontSize = 12.5.sp, color = if (dark) C.OnInk else C.Ink2), maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 설정 → AI 모델 */
@Composable
fun AiSettingsScreen(vm: MainVm) {
    val st by vm.h.ai.state.collectAsState()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<Provider?>(null) }
    var test by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    Column(Modifier.fillMaxSize().background(C.Paper)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).press({ vm.back() }), contentAlignment = Alignment.Center) { Ico(I.Back, 22.dp) }
            Column(Modifier.weight(1f)) { Text(I18n.t("AI 모델"), style = T.title); Label(I18n.t("키는 선택 · 기기 안에 암호화 저장")) }
        }
        Hairline()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionHead("01", I18n.t("회사별 연결"))
            Text(I18n.t("API 키를 넣으면 그 회사의 모델을 바로 쓰고 글자가 실시간으로 흘러요. 키가 없어도 DeepSeek은 앱 안 웹으로, 다른 회사는 그 회사 앱으로 연결됩니다. 같은 회사 계정을 여러 개 넣어도 돼요."),
                style = T.small.copy(fontSize = 12.5.sp, color = C.Graphite))
            Provider.values().forEach { p ->
                val accs = st.accounts.filter { it.provider == p }
                val pref = st.pref(p)
                Row(Modifier.fillMaxWidth().press({ open = if (open == p) null else p }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProviderMark(p); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.label, style = T.head.copy(fontSize = 15.sp))
                        Label(if (accs.isNotEmpty()) "계정 ${accs.size}개 · 모델 ${if (pref.model == "auto") "자동" else pref.model}" else if (p == Provider.DEEPSEEK && st.webDeepSeek) "키 없이 웹으로 사용 중" else "키 없음 · 키를 넣으면 연결돼요")
                    }
                    Ico(if (open == p) I.Up else I.Down, 16.dp)
                }
                if (open == p) Column(Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 12.dp)) {
                    accs.forEach { a ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.label + if (a.isDefault) " · 기본" else "", style = T.body)
                                Label(test[a.id] ?: "키 ••••" + (vm.h.ai.key(a)?.takeLast(4) ?: ""))
                            }
                            Text(I18n.t("확인"), style = T.small.copy(fontWeight = FontWeight.Bold), modifier = Modifier.press({ scope.launch { test = test + (a.id to "확인 중…"); test = test + (a.id to vm.h.ai.test(a)) } }).padding(8.dp))
                            if (!a.isDefault) Text(I18n.t("기본"), style = T.small, modifier = Modifier.press({ vm.h.ai.makeDefault(a.id) }).padding(8.dp))
                            Text(I18n.t("삭제"), style = T.small.copy(color = C.Red), modifier = Modifier.press({ vm.h.ai.removeAccount(a.id) }).padding(8.dp))
                        }
                    }
                    AddKey(p) { label, key -> vm.h.ai.addAccount(p, label, key) }
                    Spacer(Modifier.height(10.dp)); Label(I18n.t("기본 모델"))
                    FlowChips((listOf("auto" to "자동(업무에 맞게)") + ModelCatalog.of(p).map { it.id to "${it.label} · ${it.tier.label}" }), pref.model) { v -> vm.h.ai.setPref(p) { it.copy(model = v) } }
                    var custom by remember(p) { mutableStateOf(if (ModelCatalog.of(p).none { it.id == pref.model } && pref.model != "auto") pref.model else "") }
                    Field(custom, I18n.t("모델 ID 직접 입력(새 모델이 나오면)")) { custom = it; if (it.isNotBlank()) vm.h.ai.setPref(p) { pr -> pr.copy(model = it.trim()) } }
                    Spacer(Modifier.height(10.dp)); Label(I18n.t("창의성(temperature)"))
                    Segmented(listOf("d" to "기본", "0.2" to "정확", "0.7" to "균형", "1.0" to "창의"), pref.temperature?.toString() ?: "d") { v -> vm.h.ai.setPref(p) { it.copy(temperature = v.toDoubleOrNull()) } }
                    Spacer(Modifier.height(10.dp)); Label(I18n.t("답 최대 길이(출력 토큰)"))
                    Segmented(listOf("1024" to "1k", "4096" to "4k", "8192" to "8k", "16384" to "16k"), pref.maxTokens.toString()) { v -> vm.h.ai.setPref(p) { it.copy(maxTokens = v.toInt()) } }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(I18n.t("깊게 생각하기"), style = T.body); Label(I18n.t("지원하는 모델에서 추론을 더 길게(느려지고 토큰↑)")) }
                        Toggle(pref.reasoning) { vm.h.ai.setPref(p) { it.copy(reasoning = !it.reasoning) } }
                    }
                    // 3.0: "앱으로 연결"(다른 AI 앱으로 넘기기)은 없앴다. 키(API) 또는 HJTK 안 웹만 쓴다
                    if (p == Provider.DEEPSEEK) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(I18n.t("DeepSeek 웹(키 없이)"), style = T.body); Label(I18n.t("앱 안에서 로그인한 DeepSeek을 씁니다")) }
                        Toggle(st.webDeepSeek) { vm.h.ai.update { it.copy(webDeepSeek = !it.webDeepSeek) } }
                    }
                    Spacer(Modifier.height(8.dp)); Label(I18n.t("항상 지킬 지시(선택)"))
                    Field(pref.system, I18n.t("예: 한국어로, 짧게, 출처를 붙여서"), multi = true) { v -> vm.h.ai.setPref(p) { it.copy(system = v) } }
                }
                Hairline()
            }

            SectionHead("02", I18n.t("작업 기본 난이도"))
            FlowChips(Difficulty.values().map { it.name to "${it.label} — ${it.desc}" }, st.difficulty.name) { v -> vm.h.ai.update { it.copy(difficulty = Difficulty.of(v)) }; vm.difficulty.value = Difficulty.of(v) }

            SectionHead("03", I18n.t("역할 담당(작업에서 AI끼리 분담)"))
            Text(I18n.t("자동이면 업무와 난이도에 맞춰 회사·모델을 고르고, 검토자는 작성자와 다른 회사로 붙입니다."), style = T.small.copy(fontSize = 12.5.sp, color = C.Graphite))
            val members = remember(st) { vm.h.ai.members() }
            val team = remember(st) { vm.h.ai.team(st.difficulty) }
            listOf(Role.PLAN, Role.RESEARCH, Role.WRITE, Role.REVIEW).forEach { r ->
                val cur = st.rolePins[r.name] ?: "auto"
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, style = T.head.copy(fontSize = 15.sp), modifier = Modifier.width(48.dp))
                        Label(I18n.t("지금: ") + (team[r]?.name ?: "—"), modifier = Modifier.weight(1f))
                    }
                    FlowChips(listOf("auto" to "자동") + members.map { it.key to it.display(null) }, cur) { v -> vm.h.ai.update { s -> s.copy(rolePins = s.rolePins + (r.name to v)) } }
                }
            }

            SectionHead("04", I18n.t("토큰 절약"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(I18n.t("자료 압축 · 단계별 무기록 전송"), style = T.body)
                    Label(I18n.t("중복·메뉴 문구를 빼고 목표와 겹치는 문장만 남겨 보냅니다. 같은 검색은 다시 하지 않아요."))
                }
                Toggle(st.saveTokens) { vm.h.ai.update { it.copy(saveTokens = !it.saveTokens) } }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AddKey(p: Provider, onAdd: (String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(0.4f)) { Field(label, I18n.t("이름(예: 회사)")) { label = it } }
            Box(Modifier.weight(0.6f)) { Field(key, "${p.label} API 키", secret = true) { key = it } }
        }
        if (key.length > 8) InkButton(I18n.t("계정 추가"), Modifier.fillMaxWidth().padding(top = 6.dp)) { onAdd(label, key); label = ""; key = "" }
    }
}

@Composable
fun Field(value: String, hint: String, secret: Boolean = false, multi: Boolean = false, onChange: (String) -> Unit) {
    BasicTextField(value, onChange, textStyle = T.small.copy(fontSize = 13.5.sp), cursorBrush = SolidColor(C.Ink), singleLine = !multi, maxLines = if (multi) 4 else 1,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).background(C.Paper2).padding(horizontal = 10.dp, vertical = 10.dp),
        decorationBox = { inner -> if (value.isEmpty()) Text(hint, style = T.small.copy(fontSize = 13.5.sp, color = C.Mute)); inner() })
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FlowChips(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (k, l) ->
            val on = k == value
            Text(l, style = T.small.copy(fontSize = 12.5.sp, color = if (on) C.OnInk else C.Ink2, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (on) C.Ink else C.Paper2).border(1.dp, if (on) C.Ink else C.Hair, RoundedCornerShape(5.dp)).press({ onPick(k) }).padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}
