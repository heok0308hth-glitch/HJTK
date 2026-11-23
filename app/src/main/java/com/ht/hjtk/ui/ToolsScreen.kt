package com.ht.hjtk.ui

import com.ht.hjtk.core.I18n
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.core.Pipeline
import com.ht.hjtk.core.PipelineCompiler
import com.ht.hjtk.core.PipelineStep
import com.ht.hjtk.core.Skill
import com.ht.hjtk.core.command.Category
import com.ht.hjtk.core.command.CommandCall
import com.ht.hjtk.core.command.CommandCatalog
import com.ht.hjtk.core.command.CommandSpec

@Composable
fun ToolsScreen(vm: MainVm) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TopBar("도구", "명령어 ${CommandCatalog.ALL.size} · 파이프라인 · 스킬")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("명령어", "파이프라인", "스킬").forEachIndexed { i, l -> Chip(l, tab == i) { tab = i } }
        }
        when (tab) { 0 -> CommandList(vm, onPick = { vm.go(Route.Command(it.id)) }); 1 -> PipelineList(vm); else -> SkillList(vm) }
    }
}

@Composable
fun CommandList(vm: MainVm, onPick: (CommandSpec) -> Unit) {
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<Category?>(null) }
    val list = remember(q, cat) { CommandCatalog.search(q).filter { cat == null || it.category == cat } }
    Column {
        Box(Modifier.padding(horizontal = 16.dp)) { Field(q, { q = it }, "명령어 검색 · 예: 메일, 날씨, 공모전, 계산") }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("전체 ${CommandCatalog.ALL.size}", cat == null) { cat = null }
            Category.values().forEach { c -> Chip("${c.label} ${CommandCatalog.ALL.count { it.category == c }}", cat == c) { cat = c } }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            var lastCat: Category? = null
            list.forEach { s ->
                if (s.category != lastCat) { val cc = s.category; item(key = "h_" + cc.name) { Row(Modifier.padding(top = 18.dp, bottom = 4.dp)) { Label(cc.code, Modifier.width(48.dp), C.Ink); Label(cc.label) }; Hairline(color = C.Ink) }; lastCat = s.category }
                item(key = s.id) { CommandRow(s) { onPick(s) }; Hairline() }
            }
        }
    }
}

@Composable
private fun CommandRow(s: CommandSpec, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().press(onClick).padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, style = T.head.copy(fontSize = 15.sp))
                Spacer(Modifier.width(8.dp)); Text("/" + (s.aliases.firstOrNull() ?: s.id), style = T.mono.copy(fontSize = 11.sp, color = C.Graphite))
            }
            Text(s.desc, style = T.small.copy(fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.effect.needsApproval) Box(Modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, C.Red, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) { Text("E${s.effect.level} 승인", style = T.label.copy(color = C.Red, fontSize = 9.sp)) }
    }
}

@Composable
fun CommandScreen(vm: MainVm, id: String) {
    val s = CommandCatalog.find(id) ?: return
    val vals = remember { mutableStateMapOf<String, String>() }
    Column(Modifier.fillMaxSize()) {
        TopBar(s.title, "${s.category.label} · ${s.id}", onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.desc, style = T.body)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("효과 E${s.effect.level} · ${s.effect.label}", color = if (s.effect.needsApproval) C.Red else C.Mute)
                Label(I18n.t("슬래시 /") + (s.aliases.firstOrNull() ?: s.id))
            }
            Hairline(color = C.Ink)
            if (s.params.isEmpty()) Text(I18n.t("인자 없이 바로 실행됩니다."), style = T.small)
            s.params.forEach { p ->
                Column { Label(p.name + if (p.required) " *" else " (선택)", color = if (p.required) C.Ink else C.Mute); Spacer(Modifier.height(4.dp))
                    Field(vals[p.name] ?: "", { vals[p.name] = it }, p.desc, single = p.name !in listOf("text", "content", "body", "draft", "rows", "items", "pairs", "md", "csv", "json", "urls", "steps", "guide")) }
            }
            val missing = s.params.filter { it.required && vals[it.name].isNullOrBlank() }
            InkButton(if (missing.isEmpty()) "지금 실행" else "필수: ${missing.joinToString { it.name }}", Modifier.fillMaxWidth(), accent = s.effect.needsApproval) {
                if (missing.isEmpty()) vm.runCommand(CommandCall("u", s.id, vals.toMap()))
            }
            if (s.effect.needsApproval) Text(I18n.t("직접 실행은 당신의 손으로 누른 것이므로 승인 카드를 다시 띄우지 않습니다. AI가 이 명령을 쓸 때는 항상 '실행' 버튼으로 허락을 받습니다."), style = T.small.copy(fontSize = 12.sp))
            Text("AI에게 맡길 때: \"${s.title}\"이 필요한 일을 자연어로 말하면 DeepSeek이 이 명령을 고릅니다.", style = T.small.copy(fontSize = 12.sp))
        }
    }
}

@Composable
private fun PipelineList(vm: MainVm) {
    val ps by vm.store.pipelines.collectAsState()
    var runFor by remember { mutableStateOf<Pipeline?>(null) }; var input by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text(I18n.t("명령을 이어 붙인 나만의 자동화. 밖으로 나가는 단계 앞에는 승인 단계가 자동으로 들어갑니다."), style = T.small, modifier = Modifier.weight(1f)); Spacer(Modifier.width(10.dp)); InkButton(I18n.t("새로")) { vm.go(Route.PipelineEdit(null)) } } }
        items(ps, key = { it.id }) { p ->
            val comp = remember(p) { PipelineCompiler.compile(p) }
            FoldCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) { Ico(I.Flow, 18.dp); Spacer(Modifier.width(8.dp)); Text(p.name, style = T.head, modifier = Modifier.weight(1f)); Label("${comp.steps.size}단계") }
                if (p.desc.isNotBlank()) Text(p.desc, style = T.small, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                comp.steps.forEachIndexed { i, s -> Text("%02d  ".format(i + 1) + when (s.kind) { "cmd" -> s.cmd + " " + s.args.entries.joinToString(" ") { "${it.key}=${it.value}" }; "ai" -> "AI · " + s.prompt; else -> "승인 · " + s.prompt },
                    style = T.mono.copy(fontSize = 11.5.sp, color = if (s.kind == "approval") C.Red else C.Ink3), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(10.dp))
                if (runFor == p) {
                    Field(input, { input = it }, "{input}에 들어갈 값 (예: 디자인)")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { InkButton(I18n.t("작업으로 실행"), accent = true) { vm.runPipeline(p, input); runFor = null; input = "" }; GhostButton(I18n.t("취소")) { runFor = null } }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { InkButton(I18n.t("실행")) { runFor = p }; GhostButton(I18n.t("편집")) { vm.go(Route.PipelineEdit(p.id)) } }
            }
        }
    }
}

@Composable
fun PipelineEditScreen(vm: MainVm, id: String?) {
    val orig = vm.store.pipelines.value.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(orig?.name ?: "") }
    var desc by remember { mutableStateOf(orig?.desc ?: "") }
    var text by remember { mutableStateOf(orig?.steps?.joinToString("\n") { s -> when (s.kind) { "ai" -> "ai: " + s.prompt; "approval" -> "approve " + s.prompt; else -> s.cmd + " " + s.args.entries.joinToString(" ") { e -> "${e.key}=" + (if (e.value.contains(' ')) "\"${e.value}\"" else e.value) } } } ?: "web.search q={input}\nai: 결과를 표로 정리해줘\nfile.write name=정리.md content={prev}") }
    val steps = text.lines().mapNotNull { PipelineStep.parseLine(it) }
    val comp = PipelineCompiler.compile(Pipeline("x", name, desc, steps))
    Column(Modifier.fillMaxSize()) {
        TopBar(if (orig == null) "새 파이프라인" else "파이프라인 편집", "한 줄에 한 단계", onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(name, { name = it }, "이름"); Field(desc, { desc = it }, "설명")
            Label(I18n.t("단계 — 명령 key=value · ai: 지시 · approve 질문 · {input} {prev} {today}"))
            Field(text, { text = it }, "web.search q={input}", single = false, modifier = Modifier.heightIn(min = 160.dp))
            Label(I18n.t("컴파일 결과"))
            comp.steps.forEachIndexed { i, s -> Text("%02d  ".format(i + 1) + (if (s.kind == "cmd") s.cmd else s.kind) + "  " + (if (s.kind == "cmd") s.args.toString() else s.prompt), style = T.mono.copy(fontSize = 11.5.sp, color = if (s.kind == "approval") C.Red else C.Ink3)) }
            if (comp.insertedApprovals > 0) Text("밖으로 나가는 단계 앞에 승인 ${comp.insertedApprovals}개를 자동으로 넣었어요.", style = T.small.copy(color = C.Red))
            comp.issues.forEach { Text("· $it", style = T.small.copy(color = C.Graphite)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton(I18n.t("저장")) {
                    if (name.isNotBlank() && steps.isNotEmpty()) {
                        val p = Pipeline(orig?.id ?: vm.store.id("p"), name, desc, steps)
                        vm.store.pipelines.value = vm.store.pipelines.value.filter { it.id != p.id } + p; vm.store.persistHub(); vm.back()
                    }
                }
                if (orig != null) GhostButton(I18n.t("삭제")) { vm.store.pipelines.value = vm.store.pipelines.value.filter { it.id != orig.id }; vm.store.persistHub(); vm.back() }
            }
        }
    }
}

@Composable
private fun SkillList(vm: MainVm) {
    val custom by vm.store.skills.collectAsState()
    var add by remember { mutableStateOf(false) }
    var n by remember { mutableStateOf("") }; var w by remember { mutableStateOf("") }; var g by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(I18n.t("스킬은 코드가 아니라 '지침'입니다. 작업을 시작할 때 AI에게 건네지고, AI가 skill.use로 꺼내 읽습니다. AI가 새 스킬을 제안하면 당신이 저장 여부를 정합니다."), style = T.small, modifier = Modifier.weight(1f)); Spacer(Modifier.width(10.dp)); InkButton(if (add) "닫기" else "만들기") { add = !add } }
            if (add) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(n, { n = it }, "이름 (예: 포트폴리오 정리)"); Field(w, { w = it }, "언제 쓰나"); Field(g, { g = it }, "지침 — 순서·기준·금지사항", single = false, modifier = Modifier.heightIn(min = 120.dp))
                InkButton(I18n.t("저장")) { if (n.isNotBlank() && g.isNotBlank()) { vm.store.skills.value = vm.store.skills.value + Skill(vm.store.id("sk"), n, w, g); vm.store.persistHub(); n = ""; w = ""; g = ""; add = false } }
            }
        }
        items(Skill.BUILT_IN + custom, key = { it.id }) { s ->
            FoldCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(s.name, style = T.head, modifier = Modifier.weight(1f)); Label(if (s.builtIn) "기본" else "내 스킬") }
                Text(s.whenToUse, style = T.small, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(6.dp)); Text(s.guide, style = T.small.copy(color = C.Ink2, fontSize = 12.5.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton(I18n.t("이 스킬로 작업")) { vm.draft.value = "[${s.name}] "; vm.mode.value = Mode.MISSION; vm.tab(Route.Chat) }
                    if (!s.builtIn) GhostButton(I18n.t("삭제")) { vm.store.skills.value = vm.store.skills.value.filter { it.id != s.id }; vm.store.persistHub() }
                }
            }
        }
    }
}

/** 대화창의 '/' 버튼: 명령어 사전을 덮개로 띄워 슬래시를 넣는다 */
@Composable
fun Palette(vm: MainVm) {
    Column(Modifier.fillMaxSize().background(C.Paper)) {
        TopBar("명령어", "${CommandCatalog.ALL.size}개 · 고르면 입력창에 들어갑니다", onBack = { vm.paletteOpen.value = false })
        Spacer(Modifier.height(10.dp))
        CommandList(vm) { s -> vm.draft.value = "/" + (s.aliases.firstOrNull() ?: s.id) + " "; vm.paletteOpen.value = false }
    }
}
