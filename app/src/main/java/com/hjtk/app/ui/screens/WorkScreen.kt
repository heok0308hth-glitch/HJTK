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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.Pipeline
import com.hjtk.app.core.model.Skill
import com.hjtk.app.core.model.SkillStep
import com.hjtk.app.core.util.TimeFmt
import com.hjtk.app.ui.HjtkViewModel
import com.hjtk.app.ui.components.EffectBadge
import com.hjtk.app.ui.components.EmptyState
import com.hjtk.app.ui.components.HairlineDivider
import com.hjtk.app.ui.components.Pill
import com.hjtk.app.ui.components.SectionHeader
import com.hjtk.app.ui.theme.Hjtk

@Composable
fun WorkScreen(vm: HjtkViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var showBuilder by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Skill?>(null) }
    val pipelines = vm.pipelines()
    val skills = vm.skills()

    LazyColumn(Modifier.fillMaxSize().background(Hjtk.palette.background)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("작업", style = MaterialTheme.typography.headlineSmall, color = Hjtk.palette.ink)
                    Text("여러 단계를 묶어 한 번에 실행", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft)
                }
                IconButton(onClick = { showBuilder = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "새 파이프라인", tint = Hjtk.palette.accent)
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("파이프라인 ${pipelines.size}", selected = tab == 0, onClick = { tab = 0 })
                Pill("스킬 ${skills.size}", selected = tab == 1, onClick = { tab = 1 })
            }
            Spacer(Modifier.height(8.dp))
        }

        if (tab == 0) {
            items(pipelines, key = { it.id }) { p ->
                PipelineCard(
                    p = p,
                    expanded = expanded == p.id,
                    onToggle = { expanded = if (expanded == p.id) null else p.id },
                    onRun = { vars -> vm.runPipelineWith(p.id, vars) },
                    onDelete = { vm.deletePipeline(p.id) }
                )
                HairlineDivider()
            }
            if (pipelines.isEmpty()) {
                item { EmptyState("파이프라인이 없습니다", "명령을 순서대로 묶으면 반복 작업을 한 번에 실행할 수 있습니다.", "만들기") { showBuilder = true } }
            }
        } else {
            items(skills, key = { it.id }) { s ->
                SkillCard(
                    s = s,
                    onRun = { vm.runSkill(s) },
                    onDelete = { vm.deleteSkill(s.id) },
                    onEdit = { editing = s }
                )
                HairlineDivider()
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    editing?.let { skill ->
        SkillEditDialog(
            skill = skill,
            onDismiss = { editing = null },
            onSave = { name, keywords, description ->
                vm.updateSkill(skill.copy(name = name, keywords = keywords, description = description))
                editing = null
            }
        )
    }

    if (showBuilder) {
        PipelineBuilderDialog(
            vm = vm,
            onDismiss = { showBuilder = false },
            onCreate = { name, desc, steps ->
                vm.createPipeline(name, desc, steps)
                showBuilder = false
            }
        )
    }
}

@Composable
private fun PipelineCard(
    p: Pipeline,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRun: (Map<String, String>) -> Unit,
    onDelete: () -> Unit
) {
    val vars = remember(p.id) { p.params.associate { it.name to "" }.toMutableMap() }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.ink)
                Text(p.description, style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.inkSoft)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${p.steps.size}단계 · " + if (p.lastRunAt > 0) "마지막 실행 " + TimeFmt.stamp(p.lastRunAt) + if (p.lastRunOk) " 성공" else " 실패" else "실행 기록 없음",
                    style = MaterialTheme.typography.labelSmall,
                    color = Hjtk.palette.inkSoft
                )
            }
            IconButton(onClick = {
                val missing = p.params.filter { it.required && vars[it.name].isNullOrBlank() }
                if (missing.isNotEmpty()) {
                    error = "${missing.joinToString(", ") { it.labelKo }}을(를) 입력해 주세요."
                } else {
                    error = null
                    onRun(vars.filterValues { it.isNotBlank() })
                }
            }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "실행", tint = Hjtk.palette.accent)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().background(Hjtk.palette.surfaceAlt, RoundedCornerShape(12.dp)).padding(12.dp)
            ) {
                p.steps.forEachIndexed { i, s ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft, modifier = Modifier.width(18.dp))
                        Text(s.titleKo.ifBlank { s.commandId }, style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.ink)
                        Spacer(Modifier.width(6.dp))
                        Text(s.commandId, style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft)
                    }
                }
                if (p.params.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    p.params.forEach { param ->
                        var v by remember(p.id, param.name) { mutableStateOf(vars[param.name].orEmpty()) }
                        OutlinedTextField(
                            value = v,
                            onValueChange = { v = it; vars[param.name] = it },
                            label = { Text(param.labelKo) },
                            placeholder = { Text(param.example) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hjtk.palette.accent, unfocusedBorderColor = Hjtk.palette.line)
                        )
                    }
                }
                if (error != null) {
                    Text(error!!, style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.danger)
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (!p.builtIn) {
                        TextButton(onClick = onDelete) { Text("삭제", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.danger) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillCard(s: Skill, onRun: () -> Unit, onDelete: () -> Unit, onEdit: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.ink)
                    if (s.builtIn) {
                        Spacer(Modifier.width(6.dp))
                        Text("기본", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.accent)
                    }
                }
                Text(s.description, style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.inkSoft)
                if (s.keywords.isNotEmpty()) {
                    Text("호출: " + s.keywords.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft)
                }
            }
            IconButton(onClick = onRun) { Icon(Icons.Filled.PlayArrow, contentDescription = "실행", tint = Hjtk.palette.accent) }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().background(Hjtk.palette.surfaceAlt, RoundedCornerShape(12.dp)).padding(12.dp)) {
                s.steps.forEachIndexed { i, st ->
                    Text("${i + 1}. ${st.titleKo.ifBlank { st.commandId }}  (${st.commandId})", style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.ink)
                }
                if (!s.builtIn) {
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onEdit) { Text("편집", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.accent) }
                        TextButton(onClick = onDelete) { Text("삭제", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.danger) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineBuilderDialog(
    vm: HjtkViewModel,
    onDismiss: () -> Unit,
    onCreate: (String, String, List<SkillStep>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableListOf<SkillStep>() }
    var refresh by remember { mutableIntStateOf(0) }
    val results = remember(query, refresh) { vm.commands(query).take(12) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().height(560.dp).background(Hjtk.palette.surface, RoundedCornerShape(20.dp)).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("새 파이프라인", style = MaterialTheme.typography.titleLarge, color = Hjtk.palette.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "닫기", tint = Hjtk.palette.inkSoft) }
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("이름") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hjtk.palette.accent, unfocusedBorderColor = Hjtk.palette.line)
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it }, label = { Text("설명") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hjtk.palette.accent, unfocusedBorderColor = Hjtk.palette.line)
            )
            Spacer(Modifier.height(8.dp))
            Text("단계 ${chosen.size}개", style = MaterialTheme.typography.labelLarge, color = Hjtk.palette.inkSoft)
            chosen.forEachIndexed { i, st ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${st.commandId}", style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { chosen.removeAt(i); refresh++ }) { Text("빼기", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.danger) }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, label = { Text("명령 추가") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hjtk.palette.accent, unfocusedBorderColor = Hjtk.palette.line)
            )
            LazyColumn(Modifier.weight(1f)) {
                items(results, key = { it.id }) { spec ->
                    CommandPickRow(spec) {
                        chosen.add(SkillStep(spec.id, emptyMap(), spec.labelKo))
                        refresh++
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("취소", color = Hjtk.palette.inkSoft) }
                TextButton(
                    onClick = {
                        if (name.isNotBlank() && chosen.isNotEmpty()) onCreate(name, description, chosen.toList())
                    },
                    enabled = name.isNotBlank() && chosen.isNotEmpty()
                ) { Text("저장", color = Hjtk.palette.accent) }
            }
        }
    }
}

@Composable
private fun SkillEditDialog(
    skill: Skill,
    onDismiss: () -> Unit,
    onSave: (String, List<String>, String) -> Unit
) {
    var name by remember { mutableStateOf(skill.name) }
    var keywords by remember { mutableStateOf(skill.keywords.joinToString(", ")) }
    var description by remember { mutableStateOf(skill.description) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(Hjtk.palette.surface, RoundedCornerShape(20.dp)).padding(18.dp)) {
            Text("스킬 편집", style = MaterialTheme.typography.titleLarge, color = Hjtk.palette.ink)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("이름") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hjtk.palette.accent, unfocusedBorderColor = Hjtk.palette.line)
            )
            OutlinedTextField(
                value = keywords, onValueChange = { keywords = it }, label = { Text("호출 키워드(쉼표)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hjtk.palette.accent, unfocusedBorderColor = Hjtk.palette.line)
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it }, label = { Text("설명") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hjtk.palette.accent, unfocusedBorderColor = Hjtk.palette.line)
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("취소", color = Hjtk.palette.inkSoft) }
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            onSave(
                                name.trim(),
                                keywords.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                                description.trim()
                            )
                        }
                    }
                ) { Text("저장", color = Hjtk.palette.accent) }
            }
        }
    }
}

@Composable
private fun CommandPickRow(spec: CommandSpec, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(spec.labelKo, style = MaterialTheme.typography.bodyLarge, color = Hjtk.palette.ink)
            Text(spec.id, style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft)
        }
        EffectBadge(spec.effect)
    }
}
