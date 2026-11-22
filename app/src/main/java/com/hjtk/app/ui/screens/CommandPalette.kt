package com.hjtk.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.CommandCategory
import com.hjtk.app.ui.HjtkViewModel
import com.hjtk.app.ui.components.EffectBadge
import com.hjtk.app.ui.components.HairlineDivider
import com.hjtk.app.ui.components.Pill
import com.hjtk.app.ui.components.SectionHeader
import com.hjtk.app.ui.theme.Hjtk

/**
 * The command palette is the honest surface of the engine: everything HJTK can
 * do is listed here with its effect class, and picking one fills its slots in a
 * second dialog instead of relying on the router guessing.
 */
@Composable
fun CommandPaletteDialog(vm: HjtkViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CommandSpec?>(null) }
    var category by remember { mutableStateOf<CommandCategory?>(null) }

    val results = remember(query, category) {
        val list = if (query.isBlank()) vm.commands("") else vm.commands(query)
        if (category == null) list else list.filter { it.category == category }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 620.dp)
                .background(Hjtk.palette.surface, RoundedCornerShape(22.dp))
                .padding(vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("명령", style = MaterialTheme.typography.titleLarge, color = Hjtk.palette.ink, modifier = Modifier.weight(1f))
                Text("${vm.commands("").size}개", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft)
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "닫기", tint = Hjtk.palette.inkSoft) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("palette-search"),
                placeholder = { Text("명령 검색", style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.inkSoft) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Hjtk.palette.inkSoft) },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Hjtk.palette.accent,
                    unfocusedBorderColor = Hjtk.palette.line,
                    focusedContainerColor = Hjtk.palette.background,
                    unfocusedContainerColor = Hjtk.palette.background
                )
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Pill(text = "전체", selected = category == null, onClick = { category = null })
                CommandCategory.entries.forEach { c ->
                    Pill(text = c.labelKo, selected = category == c, onClick = { category = if (category == c) null else c })
                }
            }
            Spacer(Modifier.height(6.dp))
            HairlineDivider()
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(results, key = { it.id }) { spec ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = spec }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(spec.labelKo, style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.ink)
                            Text(
                                spec.category.labelKo + " · /" + spec.id,
                                style = MaterialTheme.typography.labelSmall,
                                color = Hjtk.palette.inkSoft
                            )
                        }
                        EffectBadge(spec.effect)
                    }
                    HairlineDivider()
                }
                if (results.isEmpty()) {
                    item {
                        Text(
                            "일치하는 명령이 없습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Hjtk.palette.inkSoft,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            }
        }
    }

    selected?.let { spec ->
        CommandArgsDialog(
            spec = spec,
            onDismiss = { selected = null },
            onRun = { args ->
                selected = null
                onDismiss()
                vm.runCommand(spec.id, args)
            }
        )
    }
}

@Composable
private fun CommandArgsDialog(
    spec: CommandSpec,
    onDismiss: () -> Unit,
    onRun: (Map<String, String>) -> Unit
) {
    val values = remember {
        spec.params.associate { it.name to "" }.toMutableMap()
    }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Hjtk.palette.surface, RoundedCornerShape(20.dp))
                .padding(18.dp)
        ) {
            Text(spec.labelKo, style = MaterialTheme.typography.titleLarge, color = Hjtk.palette.ink)
            Spacer(Modifier.height(4.dp))
            Text(spec.description, style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.inkSoft)
            Spacer(Modifier.height(12.dp))
            if (spec.params.isEmpty()) {
                Text("추가 입력 없이 실행할 수 있습니다.", style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.inkSoft)
            }
            spec.params.forEach { p ->
                var v by remember { mutableStateOf(values[p.name].orEmpty()) }
                OutlinedTextField(
                    value = v,
                    onValueChange = { v = it; values[p.name] = it },
                    label = { Text(p.labelKo + if (p.required) " *" else "") },
                    placeholder = { if (p.example.isNotBlank()) Text(p.example) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = p.name != "body" && p.name != "content" && p.name != "text",
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Hjtk.palette.accent,
                        unfocusedBorderColor = Hjtk.palette.line
                    )
                )
            }
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.danger)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("취소", color = Hjtk.palette.inkSoft) }
                TextButton(onClick = {
                    val missing = spec.params.filter { it.required && values[it.name].isNullOrBlank() }
                    if (missing.isNotEmpty()) {
                        error = "${missing.joinToString(", ") { it.labelKo }}이(가) 필요합니다."
                    } else {
                        onRun(values.filterValues { it.isNotBlank() })
                    }
                }) { Text("실행", color = Hjtk.palette.accent) }
            }
        }
    }
}
