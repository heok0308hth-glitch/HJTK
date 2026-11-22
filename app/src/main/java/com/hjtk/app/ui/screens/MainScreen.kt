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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hjtk.app.core.util.TimeFmt
import com.hjtk.app.ui.HjtkViewModel
import com.hjtk.app.ui.components.HairlineDivider
import com.hjtk.app.ui.components.Pill
import com.hjtk.app.ui.theme.Hjtk

private data class Tab(val label: String, val subtitle: String)

private val TABS = listOf(
    Tab("대화", "HJTK"),
    Tab("허브", "즐겨찾기"),
    Tab("작업", "파이프라인"),
    Tab("설정", "연결")
)

@Composable
fun MainScreen(
    vm: HjtkViewModel,
    startTab: Int = 0,
    headerExtra: (@Composable () -> Unit)? = null,
    developerSlot: (@Composable () -> Unit)? = null
) {
    var tab by remember(startTab) { mutableIntStateOf(startTab.coerceIn(0, TABS.lastIndex)) }
    var palette by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Hjtk.palette.background).statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ChatScreen(
                    vm = vm,
                    onOpenPalette = { palette = true },
                    onOpenSessions = { sessions = true },
                    headerExtra = headerExtra
                )
                1 -> HubScreen(vm = vm, onOpenPalette = { palette = true }, onOpenSettings = { tab = 3 })
                2 -> WorkScreen(vm = vm)
                else -> SettingsScreen(vm = vm, developerSlot = developerSlot)
            }
        }
        BottomBar(selected = tab, onSelect = { tab = it })
    }

    if (palette) CommandPaletteDialog(vm = vm, onDismiss = { palette = false })
    if (sessions) SessionsDialog(vm = vm, onDismiss = { sessions = false })
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        HairlineDivider()
        Row(
            Modifier.fillMaxWidth().background(Hjtk.palette.background).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TABS.forEachIndexed { i, t ->
                val active = i == selected
                Column(
                    Modifier
                        .clickable { onSelect(i) }
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(if (active) Hjtk.palette.accent else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) Hjtk.palette.ink else Hjtk.palette.inkSoft
                    )
                    Text(
                        t.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) Hjtk.palette.accent else Hjtk.palette.inkSoft.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun SessionsDialog(vm: HjtkViewModel, onDismiss: () -> Unit) {
    val sessions = remember { vm.engine.sessions.summary() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp)
                .background(Hjtk.palette.surface, RoundedCornerShape(20.dp)).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("대화", style = MaterialTheme.typography.titleLarge, color = Hjtk.palette.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    vm.newSession()
                    onDismiss()
                }) { Text("새 대화", color = Hjtk.palette.accent) }
            }
            HairlineDivider()
            LazyColumn(Modifier.weight(1f)) {
                items(sessions, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            vm.engine.sessions.switchTo(s.id)
                            vm.engine.orchestrator.restoreConversation()
                            onDismiss()
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.ink)
                            Text(
                                "${s.messageCount}개 메시지 · ${TimeFmt.ago(System.currentTimeMillis(), s.updatedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Hjtk.palette.inkSoft
                            )
                        }
                        TextButton(onClick = { vm.engine.sessions.delete(s.id); onDismiss() }) {
                            Text("삭제", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.danger)
                        }
                    }
                    HairlineDivider()
                }
            }
        }
    }
}
