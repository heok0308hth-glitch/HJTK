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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hjtk.app.core.model.HubEntry
import com.hjtk.app.core.model.HubKind
import com.hjtk.app.ui.HjtkViewModel
import com.hjtk.app.ui.components.HairlineDivider
import com.hjtk.app.ui.components.ListRow
import com.hjtk.app.ui.components.Pill
import com.hjtk.app.ui.components.SectionHeader
import com.hjtk.app.ui.theme.Hjtk

@Composable
fun HubScreen(vm: HjtkViewModel, onOpenPalette: () -> Unit, onOpenSettings: () -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val entries = remember(query) { vm.hub().filter { query.isBlank() || it.label.contains(query, true) || it.target.contains(query, true) } }
    val mailConnected = vm.engine.ports.mail.isConfigured()

    LazyColumn(Modifier.fillMaxSize().background(Hjtk.palette.background)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("허브", style = MaterialTheme.typography.headlineSmall, color = Hjtk.palette.ink)
                    Text("자주 쓰는 것들을 한곳에서", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft)
                }
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "추가", tint = Hjtk.palette.accent)
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("허브 검색", style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.inkSoft) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Hjtk.palette.inkSoft) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Hjtk.palette.accent,
                    unfocusedBorderColor = Hjtk.palette.line,
                    focusedContainerColor = Hjtk.palette.surface,
                    unfocusedContainerColor = Hjtk.palette.surface
                )
            )
            Spacer(Modifier.height(10.dp))
        }
        item {
            SectionHeader("연결", "메일과 검색 어댑터 상태")
            ListRow(
                title = if (mailConnected) vm.engine.ports.mail.providerName() else "메일 연결하기",
                subtitle = if (mailConnected) "IMAP 수신 · SMTP 발신" else "네이버·Gmail·다음 프리셋 지원",
                leading = { Box(Modifier.size(34.dp).background(Hjtk.palette.accentSoft, CircleShape), contentAlignment = Alignment.Center) { Text("✉", color = Hjtk.palette.accent) } },
                trailing = {
                    Pill(
                        text = if (mailConnected) "연결됨" else "설정",
                        selected = !mailConnected,
                        onClick = onOpenSettings
                    )
                }
            )
            HairlineDivider()
        }
        item { SectionHeader("즐겨찾기", "${entries.size}개 · 길게 눌러 고정 해제") }
        items(entries, key = { it.id }) { entry ->
            HubRow(entry, onOpen = { vm.openHub(entry) }, onPin = { vm.pinHub(entry.id) }, onDelete = { vm.removeHub(entry.id) })
            HairlineDivider()
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "아직 즐겨찾기가 없습니다. 오른쪽 위 + 로 앱이나 사이트를 추가하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Hjtk.palette.inkSoft,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
        item {
            Spacer(Modifier.height(6.dp))
            SectionHeader("명령", "엔진이 할 수 있는 일 전체")
            ListRow(
                title = "명령 팔레트 열기",
                subtitle = "${vm.commands("").size}개 명령 · 카테고리별 검색",
                leading = { Box(Modifier.size(34.dp).background(Hjtk.palette.surfaceAlt, CircleShape), contentAlignment = Alignment.Center) { Text("/", color = Hjtk.palette.inkSoft) } },
                onClick = onOpenPalette
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAdd) {
        AddHubDialog(
            exists = { id -> vm.engine.registry.exists(id) },
            onDismiss = { showAdd = false },
            onAdd = { kind, label, target ->
                vm.addHub(kind, label, target)
                showAdd = false
            }
        )
    }
}

@Composable
private fun HubRow(entry: HubEntry, onOpen: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    var showActions by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { onOpen() }.padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).background(Hjtk.palette.surfaceAlt, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(entry.label.take(1), style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.label, style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.ink)
                if (entry.pinned) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Star, contentDescription = null, tint = Hjtk.palette.warn, modifier = Modifier.size(12.dp))
                }
            }
            Text(
                entry.kind.labelKo + " · " + entry.target.take(48),
                style = MaterialTheme.typography.labelSmall,
                color = Hjtk.palette.inkSoft
            )
        }
        TextButton(onClick = { showActions = !showActions }) {
            Text("⋯", color = Hjtk.palette.inkSoft)
        }
        if (showActions) {
            TextButton(onClick = { onPin(); showActions = false }) { Text(if (entry.pinned) "고정 해제" else "고정", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { onDelete(); showActions = false }) { Text("삭제", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.danger) }
        }
    }
}

@Composable
private fun AddHubDialog(exists: (String) -> Boolean, onDismiss: () -> Unit, onAdd: (HubKind, String, String) -> Unit) {
    var kind by remember { mutableStateOf(HubKind.SITE) }
    var label by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Hjtk.palette.surface, RoundedCornerShape(20.dp)).padding(18.dp)
        ) {
            Text("허브에 추가", style = MaterialTheme.typography.titleLarge, color = Hjtk.palette.ink)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(HubKind.SITE, HubKind.APP, HubKind.AI, HubKind.COMMAND).forEach { k ->
                    Pill(text = k.labelKo, selected = kind == k, onClick = { kind = k })
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = label, onValueChange = { label = it },
                label = { Text("이름") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hjtk.palette.accent, unfocusedBorderColor = Hjtk.palette.line)
            )
            OutlinedTextField(
                value = target, onValueChange = { target = it },
                label = { Text(if (kind == HubKind.COMMAND) "명령 ID" else if (kind == HubKind.APP) "앱 이름" else "주소") },
                placeholder = {
                    Text(
                        when (kind) {
                            HubKind.SITE, HubKind.AI -> "https://example.com"
                            HubKind.APP -> "카카오톡"
                            else -> "calendar.today"
                        }
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hjtk.palette.accent, unfocusedBorderColor = Hjtk.palette.line)
            )
            if (error != null) Text(error!!, style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.danger)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("취소", color = Hjtk.palette.inkSoft) }
                TextButton(onClick = {
                    when {
                        label.isBlank() -> error = "이름을 입력해 주세요."
                        target.isBlank() -> error = "대상을 입력해 주세요."
                        kind == HubKind.COMMAND && !exists(target) -> error = "존재하지 않는 명령입니다."
                        else -> onAdd(kind, label, target)
                    }
                }) { Text("추가", color = Hjtk.palette.accent) }
            }
        }
    }
}
