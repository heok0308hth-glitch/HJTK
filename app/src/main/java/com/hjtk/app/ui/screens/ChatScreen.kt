package com.hjtk.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hjtk.app.core.model.ChatItem
import com.hjtk.app.core.model.PlanState
import com.hjtk.app.core.util.TimeFmt
import com.hjtk.app.ui.HjtkViewModel
import com.hjtk.app.ui.components.ApprovalCardView
import com.hjtk.app.ui.components.HairlineDivider
import com.hjtk.app.ui.components.NoticeCardView
import com.hjtk.app.ui.components.Pill
import com.hjtk.app.ui.components.PlanCardView
import com.hjtk.app.ui.components.ReportCardView
import com.hjtk.app.ui.components.RichText
import com.hjtk.app.ui.components.StatusDot
import com.hjtk.app.ui.theme.Hjtk

@Composable
fun ChatScreen(
    vm: HjtkViewModel,
    onOpenPalette: () -> Unit,
    onOpenSessions: () -> Unit,
    headerExtra: (@Composable () -> Unit)? = null
) {
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(Hjtk.palette.background).imePadding()) {
        ChatHeader(state.statusLine, state.busy, onOpenSessions, headerExtra)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.items.isEmpty()) {
                item { WelcomeBlock(onPick = { text -> vm.submit(text) }) }
            }
            items(state.items, key = { it.id }) { item ->
                when (item) {
                    is ChatItem.UserText -> UserBubble(item.text)
                    is ChatItem.AssistantText -> AssistantBlock(item.text, item.actions, onAction = { cmd, args ->
                        vm.runCommand(cmd, args)
                    })
                    is ChatItem.PlanCard -> {
                        val job = state.job(item.plan.id)
                        PlanCardView(item.plan, job?.doneSteps ?: 0, job?.state ?: PlanState.DRAFT)
                    }
                    is ChatItem.ApprovalCard -> ApprovalCardView(
                        request = item.request,
                        onRun = { vm.approve(item.request.planId) },
                        onCancel = { vm.reject(item.request.planId) }
                    )
                    is ChatItem.ReportCard -> ReportCardView(item.report)
                    is ChatItem.NoticeCard -> NoticeCardView(item.title, item.body, item.tone)
                }
            }
            if (state.busy) {
                item {
                    state.jobs.filter { !it.queued }.forEach { job ->
                        WorkingRow(
                            job.plan.titleKo + " · " + (job.activeStep?.titleKo ?: "준비 중"),
                            job.doneSteps,
                            job.totalSteps
                        )
                    }
                }
            }
        }

        if (state.jobs.isNotEmpty()) JobsStrip(vm, state.jobs)
        if (state.openPlan != null && !state.busy) ResumeStrip(vm, state.openPlan!!.title)
        Composer(
            value = input,
            onValueChange = { input = it },
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    input = ""
                    vm.submit(text)
                }
            },
            onAdd = onOpenPalette
        )
    }
}

@Composable
private fun ChatHeader(
    status: String,
    busy: Boolean,
    onOpenSessions: () -> Unit,
    extra: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).background(Hjtk.palette.accentSoft, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("H", style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("HJTK", style = MaterialTheme.typography.titleLarge, color = Hjtk.palette.ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (busy) Hjtk.palette.warn else Hjtk.palette.success, status)
            }
        }
        extra?.invoke()
        IconButton(onClick = onOpenSessions) {
            Icon(Icons.Filled.Menu, contentDescription = "대화 목록", tint = Hjtk.palette.inkSoft)
        }
    }
    HairlineDivider()
}

@Composable
private fun WelcomeBlock(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp)) {
        Text(
            "무엇을 도와드릴까요?",
            style = MaterialTheme.typography.headlineSmall,
            color = Hjtk.palette.ink
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "말로 시키면 명령으로 바꿔 실행합니다.\n바깥으로 나가는 일은 실행 전에 확인을 받습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = Hjtk.palette.inkSoft
        )
        Spacer(Modifier.height(18.dp))
        val samples = listOf(
            "오늘 일정 알려줘",
            "안 읽은 메일 확인해줘",
            "이 문장 다듬어줘: 회의가 늦어질 것 같아요",
            "5분 뒤 알려줘",
            "내 취향은 아이스 아메리카노라고 기억해",
            "상태 점검"
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            samples.forEach { s -> Pill(text = s, onClick = { onPick(s) }) }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .background(Hjtk.palette.bubbleUser, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Hjtk.palette.bubbleUserInk)
        }
    }
}

@Composable
private fun AssistantBlock(text: String, actions: List<com.hjtk.app.core.model.ResultAction>, onAction: (String, Map<String, String>) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .background(Hjtk.palette.surface, RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .border(1.dp, Hjtk.palette.line, RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            RichText(text)
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.take(3).forEach { a ->
                    Pill(text = a.label, onClick = { onAction(a.commandId, a.args) })
                }
            }
        }
    }
}

@Composable
private fun WorkingRow(label: String, done: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = Hjtk.palette.accent
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "$label" + if (total > 0) "  ($done/$total)" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = Hjtk.palette.inkSoft
        )
    }
}

@Composable
private fun JobsStrip(vm: HjtkViewModel, jobs: List<com.hjtk.app.core.orchestrator.JobState>) {
    Column(Modifier.fillMaxWidth().background(Hjtk.palette.surfaceAlt).padding(horizontal = 18.dp, vertical = 8.dp)) {
        jobs.forEach { job ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        job.plan.titleKo + if (job.queued) " · 대기" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = Hjtk.palette.ink
                    )
                    Text(
                        if (job.queued) "앞선 작업이 끝나면 시작합니다"
                        else "${job.doneSteps}/${job.totalSteps}단계 · 남은 예상 ${TimeFmt.duration(job.etaSecondsLeft)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Hjtk.palette.inkSoft
                    )
                }
                TextButton(onClick = { vm.stop(job.plan.id) }) {
                    Text("중단", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.danger)
                }
            }
            if (!job.queued && job.totalSteps > 0) {
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).padding(bottom = 4.dp),
                    color = Hjtk.palette.accent,
                    trackColor = Hjtk.palette.line
                )
            }
        }
    }
}

@Composable
private fun ResumeStrip(vm: HjtkViewModel, title: String) {
    Row(
        Modifier.fillMaxWidth().background(Hjtk.palette.accentSoft).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "끊긴 작업: $title",
            style = MaterialTheme.typography.labelSmall,
            color = Hjtk.palette.accent,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { vm.resume() }) { Text("이어서", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAdd: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(Hjtk.palette.background)) {
        HairlineDivider()
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "명령 팔레트", tint = Hjtk.palette.inkSoft)
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("무엇을 할까요?", style = MaterialTheme.typography.bodyLarge, color = Hjtk.palette.inkSoft) },
                shape = RoundedCornerShape(22.dp),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Hjtk.palette.accent,
                    unfocusedBorderColor = Hjtk.palette.line,
                    focusedContainerColor = Hjtk.palette.surface,
                    unfocusedContainerColor = Hjtk.palette.surface
                )
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        if (value.isBlank()) Hjtk.palette.surfaceAlt else Hjtk.palette.accent,
                        CircleShape
                    )
                    .clickable(enabled = value.isNotBlank()) { onSend() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "보내기",
                    tint = if (value.isBlank()) Hjtk.palette.inkSoft else Hjtk.palette.accentInk,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
