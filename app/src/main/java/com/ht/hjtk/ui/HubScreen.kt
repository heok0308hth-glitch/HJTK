package com.ht.hjtk.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.data.HubApp
import com.ht.hjtk.data.MailAccount
import com.ht.hjtk.data.PinnedFile
import com.ht.hjtk.data.Site
import com.ht.hjtk.engine.A11yBridge
import com.ht.hjtk.engine.DeepSeekWeb
import com.ht.hjtk.engine.ExternalAiBridge
import com.ht.hjtk.engine.Perms
import com.ht.hjtk.engine.openCustomTab
import com.ht.hjtk.tools.MailClient
import com.ht.hjtk.tools.MailPresets
import kotlinx.coroutines.launch

data class Connector(val name: String, val sub: String, val state: Int /*2연결 1필요 0선택*/, val icon: I, val onClick: () -> Unit)

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false, modifier: Modifier = Modifier, single: Boolean = true) {
    BasicTextField(value, onChange, textStyle = T.body, cursorBrush = SolidColor(C.Ink), singleLine = single,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Paper2).padding(horizontal = 12.dp, vertical = 12.dp),
        decorationBox = { i -> if (value.isEmpty()) Text(hint, style = T.body.copy(color = C.Mute)); i() })
}

@Composable
fun HubScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val st = vm.store
    val sites by st.sites.collectAsState(); val apps by st.apps.collectAsState(); val files by st.files.collectAsState()
    val mails by st.mail.collectAsState(); val customs by st.customs.collectAsState(); val mems by st.memories.collectAsState()
    val ds by vm.h.deepseek.status.collectAsState()
    var tick by remember { mutableStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = u.lastPathSegment?.substringAfterLast('/') ?: "파일"
            st.files.value = st.files.value + PinnedFile(u.toString(), name); st.persistHub()
        }
    }
    var addSite by remember { mutableStateOf(false) }; var sName by remember { mutableStateOf("") }; var sUrl by remember { mutableStateOf("") }
    var addCmd by remember { mutableStateOf(false) }; var cT by remember { mutableStateOf("") }; var cE by remember { mutableStateOf("") }; var cM by remember { mutableStateOf(true) }
    var pickApps by remember { mutableStateOf(false) }

    key(tick) {
        val cal = Perms.granted(ctx, Manifest.permission.READ_CALENDAR)
        val con = Perms.granted(ctx, Manifest.permission.READ_CONTACTS)
        val gemApp = vm.h.bridge.installed(ExternalAiBridge.GEMINI)
        val connectors = listOf(
            Connector("DeepSeek", if (ds == DeepSeekWeb.Status.READY) "두뇌 · 키 없이 연결됨" else "두뇌 · 한 번 로그인", if (ds == DeepSeekWeb.Status.READY) 2 else 1, I.Spark) { vm.go(Route.Cockpit) },
            Connector("Gemini", if (vm.h.gemini.available()) "검토자 · API 키" else if (gemApp) "검토자 · 앱 + 공유 1탭" else "검토자 · 웹(Custom Tab)", if (vm.h.gemini.available() || gemApp) 2 else 0, I.Spark) { vm.go(Route.Settings) },
            Connector("Gmail", mails.firstOrNull { it.provider == "gmail" }?.email ?: "앱 비밀번호로 연결", if (mails.any { it.provider == "gmail" }) 2 else 0, I.Mail) { vm.go(Route.MailAdd) },
            Connector("네이버 메일", mails.firstOrNull { it.provider == "naver" }?.email ?: "IMAP·SMTP", if (mails.any { it.provider == "naver" }) 2 else 0, I.Mail) { vm.go(Route.MailAdd) },
            Connector("구글 캘린더", if (cal) "기기 동기화로 읽기·쓰기" else "권한 한 번", if (cal) 2 else 1, I.Cal) { permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
            Connector("연락처", if (con) "받는 사람 확인" else "권한 한 번", if (con) 2 else 0, I.Chat) { permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) },
            Connector("구글 드라이브·파일", "${files.size}개 고정 · 시스템 선택기", if (files.isNotEmpty()) 2 else 0, I.File) { docPicker.launch(arrayOf("*/*")) },
            Connector("네이버 검색", if (st.secrets.has("naver_id")) "공식 API 키" else "키 없이 웹 결과", 2, I.Search) { vm.go(Route.Settings) },
            Connector("화면 읽기", if (A11yBridge.bound()) "Gemini 답 자동 회수" else if (A11yBridge.enabled(ctx)) "켜졌지만 미연결 · 제한된 설정?" else "선택 · 사이드로드 전용", if (A11yBridge.bound()) 2 else if (A11yBridge.enabled(ctx)) 1 else 0, I.Eye) { ctx.startActivity(Perms.a11yIntent()) },
            Connector("쥬티(펫)", if (Perms.overlay(ctx)) "다른 앱 위 표시 허용됨" else "다른 앱 위 표시 필요", if (Perms.overlay(ctx)) 2 else 1, I.Paw) { ctx.startActivity(Perms.overlayIntent(ctx)) }
        )
        Column(Modifier.fillMaxSize()) {
            TopBar("허브", "연동 ${connectors.count { it.state == 2 }}/${connectors.size} · 나만의 작업 공간")
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                SectionHead("01", "연동 센터")
                Spacer(Modifier.height(10.dp))
                connectors.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c -> ConnectorTile(c, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text("서버 없이 동작합니다. 메일은 앱 비밀번호(IMAP/SMTP), 구글 캘린더는 기기 동기화, 파일은 시스템 선택기를 씁니다. 모든 키는 기기 Keystore에 암호화 저장됩니다.", style = T.small.copy(fontSize = 12.sp))

                SectionHead("02", "즐겨찾는 사이트", "추가") { addSite = !addSite }
                if (addSite) Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(sName, { sName = it }, "이름 (예: 씽굿)"); Field(sUrl, { sUrl = it }, "주소 https://")
                    InkButton("추가") { if (sName.isNotBlank() && sUrl.isNotBlank()) { st.sites.value = st.sites.value + Site(st.id("s"), sName, if (sUrl.startsWith("http")) sUrl else "https://$sUrl"); st.persistHub(); sName = ""; sUrl = ""; addSite = false } }
                }
                sites.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { NumRow(i + 1, s.name, s.url.removePrefix("https://"), onClick = { openCustomTab(ctx, s.url) }) }
                        Box(Modifier.size(36.dp).press({ st.sites.value = st.sites.value.filter { it.id != s.id }; st.persistHub() }), contentAlignment = Alignment.Center) { Ico(I.Close, 14.dp, C.Mute) }
                    }; Hairline()
                }

                SectionHead("03", "자주 쓰는 앱", if (pickApps) "닫기" else "고르기") { pickApps = !pickApps }
                if (pickApps) AppPicker(vm)
                if (apps.isEmpty() && !pickApps) Text("AI가 '앱 열기'를 할 때 먼저 찾는 목록이에요.", style = T.small, modifier = Modifier.padding(vertical = 10.dp))
                apps.forEachIndexed { i, a -> NumRow(i + 1, a.label, a.pkg, onClick = { ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let { ctx.startActivity(it) } }); Hairline() }

                SectionHead("04", "연결된 메일", "추가") { vm.go(Route.MailAdd) }
                if (mails.isEmpty()) Text("Gmail·네이버·다음·카카오·iCloud를 앱 비밀번호로 연결하세요.", style = T.small, modifier = Modifier.padding(vertical = 10.dp))
                mails.forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { NumRow(i + 1, m.email, "${m.provider} · ${m.imapHost}", dot = C.Ink) }
                        Box(Modifier.size(36.dp).press({ st.mail.value = st.mail.value.filter { it.id != m.id }; st.secrets.put("mail_${m.id}", null); st.persistHub() }), contentAlignment = Alignment.Center) { Ico(I.Trash, 16.dp, C.Mute) }
                    }; Hairline()
                }

                SectionHead("05", "자주 쓰는 파일", "추가") { docPicker.launch(arrayOf("*/*")) }
                if (files.isEmpty()) Text("구글 드라이브·다운로드의 파일을 고정해 두면 AI에게 '이 파일로 다시 작업'을 시킬 수 있어요.", style = T.small, modifier = Modifier.padding(vertical = 10.dp))
                files.forEachIndexed { i, f -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { NumRow(i + 1, f.name, "고정됨", onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }) }
                    Box(Modifier.size(36.dp).press({ st.files.value = st.files.value.filter { it.uri != f.uri }; st.persistHub() }), contentAlignment = Alignment.Center) { Ico(I.Close, 14.dp, C.Mute) }
                }; Hairline() }

                SectionHead("06", "개인 작업 공간")
                val dirs = st.workspace.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()
                if (dirs.isEmpty()) Text("작업마다 폴더가 생기고 결과물이 쌓여요.", style = T.small, modifier = Modifier.padding(vertical = 10.dp))
                dirs.take(8).forEachIndexed { i, d -> NumRow(i + 1, d.name, "${d.listFiles()?.size ?: 0}개 파일", onClick = { if (st.job(d.name) != null) vm.go(Route.Job(d.name)) }); Hairline() }

                SectionHead("07", "사용자 명령어", "만들기") { addCmd = !addCmd }
                if (addCmd) Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(cT, { cT = it }, "/트리거 (예: /주간보고)"); Field(cE, { cE = it }, "펼쳐질 지시 (예: 이번 주 한 일을 정리해 보고서 초안 만들어줘)", single = false)
                    Row(verticalAlignment = Alignment.CenterVertically) { Chip("작업으로 실행", cM) { cM = true }; Spacer(Modifier.width(6.dp)); Chip("대화로", !cM) { cM = false } }
                    InkButton("저장") { if (cT.isNotBlank() && cE.isNotBlank()) { vm.addCustom(cT, cE, cM); cT = ""; cE = ""; addCmd = false } }
                }
                customs.forEachIndexed { i, c -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { NumRow(i + 1, c.trigger, c.expandsTo, trailing = if (c.asMission) "작업" else "대화", onClick = { vm.draft.value = c.trigger + " "; vm.tab(Route.Chat) }) }
                    Box(Modifier.size(36.dp).press({ st.customs.value = st.customs.value.filter { it.id != c.id }; st.persistHub() }), contentAlignment = Alignment.Center) { Ico(I.Close, 14.dp, C.Mute) }
                }; Hairline() }

                SectionHead("08", "기억", "관리") { vm.go(Route.Memory) }
                NumRow(1, "기억하고 있는 사실 ${mems.size}개", "새 작업마다 관련된 것만 골라 AI에게 건넵니다", onClick = { vm.go(Route.Memory) })
            }
        }
    }
}

@Composable
private fun ConnectorTile(c: Connector, modifier: Modifier) {
    val dot = when (c.state) { 2 -> C.Ink; 1 -> C.Red; else -> C.Hair }
    Column(modifier.clip(FoldShape(10.dp)).background(if (c.state == 2) C.Paper2 else C.Paper).border(1.dp, if (c.state == 1) C.Red else C.Hair, FoldShape(10.dp)).press(c.onClick).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Ico(c.icon, 18.dp, C.Ink); Spacer(Modifier.weight(1f)); SignalDot(dot, 7.dp) }
        Spacer(Modifier.height(10.dp))
        Text(c.name, style = T.head.copy(fontSize = 14.5.sp))
        Text(c.sub, style = T.small.copy(fontSize = 11.5.sp, lineHeight = 15.sp, color = if (c.state == 1) C.Red else C.Graphite), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AppPicker(vm: MainVm) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val all = remember {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }.sortedBy { it.first }
    }
    val chosen by vm.store.apps.collectAsState()
    Column(Modifier.padding(vertical = 8.dp)) {
        Field(q, { q = it }, "앱 이름 검색")
        all.filter { q.isBlank() || it.first.contains(q, true) }.take(30).forEach { (label, pkg) ->
            val on = chosen.any { it.pkg == pkg }
            Row(Modifier.fillMaxWidth().press({ vm.store.apps.value = if (on) chosen.filter { it.pkg != pkg } else chosen + HubApp(pkg, label); vm.store.persistHub() }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).border(1.5.dp, C.Ink, RoundedCornerShape(4.dp)).background(if (on) C.Ink else Color.Transparent), contentAlignment = Alignment.Center) { if (on) Ico(I.Check, 12.dp, C.OnInk) }
                Spacer(Modifier.width(10.dp)); Text(label, style = T.body)
            }
        }
    }
}

@Composable
fun MailAddScreen(vm: MainVm) {
    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf(MailPresets.ALL[0]) }
    var email by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }
    var imap by remember { mutableStateOf("") }; var smtp by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }; var testing by remember { mutableStateOf(false) }
    fun account() = MailAccount(vm.store.id("m"), preset.id, email.trim(), if (preset.id == "custom") imap else preset.imap, preset.imapPort, if (preset.id == "custom") smtp else preset.smtp, preset.smtpPort, preset.ssl)
    Column(Modifier.fillMaxSize()) {
        TopBar("메일 연결", "OAuth 없이 · 앱 비밀번호 · 기기 안에 암호화 저장", onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("서비스")
            Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { MailPresets.ALL.forEach { p -> Chip(p.label, preset == p) { preset = p } } }
            FoldCard(Modifier.fillMaxWidth()) { Label("준비"); Spacer(Modifier.height(4.dp)); Text(preset.howTo, style = T.small.copy(color = C.Ink2)) }
            Field(email, { email = it }, "메일 주소")
            Field(pw, { pw = it }, "앱 비밀번호 (계정 비밀번호 아님)", secret = true)
            if (preset.id == "custom") { Field(imap, { imap = it }, "IMAP 서버 (예: imap.example.com)"); Field(smtp, { smtp = it }, "SMTP 서버") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(if (testing) "확인 중…" else "연결 테스트") { if (!testing && email.isNotBlank() && pw.isNotBlank()) { testing = true; scope.launch { result = MailClient.test(account(), pw); testing = false } } }
                InkButton("저장") {
                    if (email.isNotBlank() && pw.isNotBlank()) {
                        val a = account(); vm.store.mail.value = vm.store.mail.value + a; vm.store.secrets.put("mail_${a.id}", pw); vm.store.persistHub(); vm.back()
                    }
                }
            }
            result?.let { Text(it, style = T.small.copy(color = if (it.startsWith("연결됨")) C.Ink else C.Red, fontWeight = FontWeight.SemiBold)) }
            Text("발송(mail.send)은 언제나 '실행' 버튼으로 허락을 받은 뒤에만 나갑니다. 처음 보내는 주소이거나 링크가 있으면 경고가 함께 표시됩니다.", style = T.small.copy(fontSize = 12.sp))
        }
    }
}

@Composable
fun Modifier.horizontalScrollCompat(): Modifier = this.horizontalScroll(rememberScrollState())
