package com.hjtk.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hjtk.app.android.prefs.Settings
import com.hjtk.app.core.util.TimeFmt
import com.hjtk.app.ui.HjtkViewModel
import com.hjtk.app.ui.components.EmptyState
import com.hjtk.app.ui.components.HairlineDivider
import com.hjtk.app.ui.components.ListRow
import com.hjtk.app.ui.components.Pill
import com.hjtk.app.ui.components.SectionHeader
import com.hjtk.app.ui.theme.Hjtk
import kotlinx.coroutines.launch

/**
 * Settings. Every row either opens a real platform screen, edits a stored
 * value, or runs a live check — there are no decorative switches.
 *
 * [developerSlot] lets a build append its own section (the personal build uses
 * it for the developer console). The open build passes nothing.
 */
@Composable
fun SettingsScreen(
    vm: HjtkViewModel,
    developerSlot: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val settings = vm.engine.settings
    val snapshot by vm.settingsState().collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var mailTest by remember { mutableStateOf<String?>(null) }
    var calendarGranted by remember { mutableStateOf(vm.engine.ports.calendar.canRead()) }
    val scope = rememberCoroutineScope()
    val calendarPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarGranted = granted
        if (!granted) {
            // Permanently denied: the only way back is the system settings page.
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Hjtk.palette.background)
            .verticalScroll(rememberScrollState())
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp)) {
            Text("설정", style = MaterialTheme.typography.headlineSmall, color = Hjtk.palette.ink)
            Text("연결과 동작을 직접 관리합니다", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft)
        }

        // ---- AI carriers -------------------------------------------------
        SectionHeader("AI 캐리어", "키를 넣으면 그 경로를 사용합니다")
        ListRow(
            title = "DeepSeek",
            subtitle = if (settings.deepSeekKey.isNullOrBlank()) "키 없음" else "키 저장됨 · " + settings.maskedKey("deepseek"),
            leading = { Monogram("D") },
            trailing = { Pill("변경", onClick = { dialog = "deepseek" }) }
        )
        HairlineDivider()
        ListRow(
            title = "Gemini",
            subtitle = if (settings.geminiKey.isNullOrBlank()) "키 없음" else "키 저장됨 · " + settings.maskedKey("gemini"),
            leading = { Monogram("G") },
            trailing = { Pill("변경", onClick = { dialog = "gemini" }) }
        )
        HairlineDivider()
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "자동", "deepseek" to "DeepSeek", "gemini" to "Gemini").forEach { (value, label) ->
                Pill(
                    text = label,
                    selected = snapshot.preferredCarrier == value,
                    onClick = { settings.preferredCarrier = value }
                )
            }
        }

        // ---- mail --------------------------------------------------------
        SectionHeader("메일", "IMAP 수신 · SMTP 발신")
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Settings.MAIL_PRESETS.entries.forEach { (key, preset) ->
                Pill(text = preset.label, selected = snapshot.mailProvider == key, onClick = { settings.mailProvider = key })
            }
        }
        OutlinedTextField(
            value = snapshot.mailAddress,
            onValueChange = { settings.mailAddress = it.trim() },
            label = { Text("메일 주소") },
            placeholder = { Text("example@naver.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hjtk.palette.accent, unfocusedBorderColor = Hjtk.palette.line)
        )
        ListRow(
            title = "앱 비밀번호",
            subtitle = if (settings.hasMailPassword()) "저장됨 · " + settings.maskedKey("mail") else "네이버는 ‘앱 비밀번호’를 발급해 사용하세요",
            leading = { Monogram("✉") },
            trailing = { Pill("변경", onClick = { dialog = "mail" }) }
        )
        HairlineDivider()
        ListRow(
            title = "연결 테스트",
            subtitle = mailTest ?: "안 읽은 메일 수를 실제로 조회합니다",
            leading = { Monogram("↻") },
            onClick = {
                if (!vm.engine.ports.mail.isConfigured()) {
                    mailTest = "주소와 앱 비밀번호를 먼저 입력해 주세요."
                } else {
                    mailTest = "확인 중…"
                    scope.launch {
                        mailTest = runCatching { "안 읽은 메일 ${vm.engine.ports.mail.unreadCount()}통" }
                            .getOrElse { "실패: " + (it.message ?: "알 수 없는 오류") }
                    }
                }
            }
        )
        HairlineDivider()

        // ---- behaviour ---------------------------------------------------
        SectionHeader("동작", null)
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 10, 15).forEach { m ->
                Pill(text = "${m}분", selected = snapshot.reportMinutes == m, onClick = { settings.reportMinutes = m })
            }
        }
        ListRow(
            title = "진행 보고",
            subtitle = "긴 작업 중 설정한 주기로 대화에 보고 카드를 남깁니다",
            trailing = {
                Switch(
                    checked = snapshot.reportsEnabled,
                    onCheckedChange = { settings.reportsEnabled = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Hjtk.palette.accent)
                )
            }
        )
        HairlineDivider()
        SectionHeader("테마", null)
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "시스템", "light" to "라이트", "dark" to "다크").forEach { (v, label) ->
                Pill(text = label, selected = snapshot.themeMode == v, onClick = { settings.themeMode = v })
            }
        }

        // ---- permissions -------------------------------------------------
        SectionHeader("권한", "필요할 때 직접 여는 방식")
        ListRow(
            title = "알림",
            subtitle = if (vm.engine.ports.notify.canPost()) "허용됨" else "차단됨 — 타이머·보고에 필요",
            onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }) }
        )
        HairlineDivider()
        ListRow(
            title = "캘린더 읽기",
            subtitle = if (calendarGranted) "허용됨" else "눌러서 허용 — 오늘 일정 명령에 필요",
            onClick = { calendarPermission.launch(android.Manifest.permission.READ_CALENDAR) }
        )
        HairlineDivider()
        ListRow(
            title = "시스템 설정 변경",
            subtitle = if (android.provider.Settings.System.canWrite(context)) "허용됨" else "화면 밝기 조절에 필요",
            onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }) }
        )
        HairlineDivider()
        ListRow(
            title = "배터리 최적화 예외",
            subtitle = "백그라운드 작업이 중간에 끊기지 않게",
            onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        )
        HairlineDivider()

        // ---- data --------------------------------------------------------
        SectionHeader("데이터", null)
        ListRow(
            title = "진단 보기",
            subtitle = "엔진·저널·캐리어 상태",
            onClick = { diagnostics = vm.diagnostics() }
        )
        HairlineDivider()
        ListRow(
            title = "이번 대화 내보내기",
            subtitle = "Markdown 파일로 저장",
            onClick = { vm.exportSession() }
        )
        HairlineDivider()
        ListRow(
            title = "기억 내보내기",
            subtitle = "${vm.memories().size}건",
            onClick = { vm.exportMemoryFile() }
        )
        HairlineDivider()
        ListRow(
            title = "스킬 JSON 내보내기",
            subtitle = "사용자 정의 스킬 ${vm.engine.skills.userDefined().size}개",
            onClick = {
                vm.runCommand(
                    "file.export_text",
                    mapOf("content" to vm.engine.skills.exportJson(), "name" to "skills.json")
                )
            }
        )
        HairlineDivider()
        ListRow(
            title = "파이프라인 JSON 내보내기",
            subtitle = "전체 ${vm.pipelines().size}개",
            onClick = {
                vm.runCommand(
                    "file.export_text",
                    mapOf("content" to vm.engine.pipelines.exportJson(), "name" to "pipelines.json")
                )
            }
        )
        HairlineDivider()
        ListRow(
            title = "시크릿 삭제",
            subtitle = "저장된 API 키와 메일 비밀번호를 모두 지웁니다",
            onClick = {
                settings.clearAllSecrets()
                mailTest = null
            }
        )
        HairlineDivider()

        if (developerSlot != null) {
            Spacer(Modifier.height(4.dp))
            SectionHeader("개발자", null)
            developerSlot()
            HairlineDivider()
        }

        // ---- about -------------------------------------------------------
        SectionHeader("정보", null)
        ListRow(title = "HJTK", subtitle = "버전 0.9.0 · 명령 ${vm.commands("").size}개 · 무키 명령 모드")
        HairlineDivider()
        ListRow(
            title = "저널 무결성",
            subtitle = runCatching {
                val v = vm.engine.journal.verify()
                if (v.ok) "정상 · ${v.records}건" else "손상: ${v.reason}"
            }.getOrDefault("확인 불가"),
            onClick = { diagnostics = vm.diagnostics() }
        )
        Spacer(Modifier.height(30.dp))
    }

    dialog?.let { kind ->
        SecretDialog(
            title = when (kind) {
                "deepseek" -> "DeepSeek API 키"
                "gemini" -> "Gemini API 키"
                else -> "메일 앱 비밀번호"
            },
            isPassword = kind == "mail",
            onDismiss = { dialog = null },
            onSave = { value ->
                when (kind) {
                    "deepseek" -> settings.deepSeekKey = value
                    "gemini" -> settings.geminiKey = value
                    else -> settings.mailPassword = value
                }
                dialog = null
            }
        )
    }

    diagnostics?.let { text ->
        InfoDialog(title = "진단", body = text, onDismiss = { diagnostics = null }, onExport = {
            vm.exportDiagnostics()
            diagnostics = null
        })
    }
}

@Composable
private fun Monogram(symbol: String) {
    Box(Modifier.size(34.dp).background(Hjtk.palette.surfaceAlt, CircleShape), contentAlignment = Alignment.Center) {
        Text(symbol, style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.accent)
    }
}

@Composable
private fun SecretDialog(
    title: String,
    isPassword: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Hjtk.palette.surface, RoundedCornerShape(20.dp)).padding(18.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Hjtk.palette.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "값은 Android Keystore로 암호화되어 기기에만 저장됩니다. 화면과 로그에는 전체 값이 남지 않습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = Hjtk.palette.inkSoft
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hjtk.palette.accent, unfocusedBorderColor = Hjtk.palette.line)
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("취소", color = Hjtk.palette.inkSoft) }
                TextButton(onClick = { if (value.isNotBlank()) onSave(value.trim()) }) {
                    Text("저장", color = Hjtk.palette.accent)
                }
            }
        }
    }
}

@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit, onExport: (() -> Unit)? = null) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).background(Hjtk.palette.surface, RoundedCornerShape(20.dp)).padding(18.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Hjtk.palette.ink)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.ink)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onExport != null) {
                    TextButton(onClick = onExport) { Text("파일로 저장", color = Hjtk.palette.accent) }
                }
                TextButton(onClick = onDismiss) { Text("닫기", color = Hjtk.palette.inkSoft) }
            }
        }
    }
}
