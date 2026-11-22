package com.hjtk.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hjtk.app.core.model.ApprovalRequest
import com.hjtk.app.core.model.EffectClass
import com.hjtk.app.core.model.NoticeTone
import com.hjtk.app.core.model.Plan
import com.hjtk.app.core.model.PlanState
import com.hjtk.app.core.model.ProgressReport
import com.hjtk.app.ui.theme.Hjtk

/**
 * Small, opinionated building blocks. Every screen is assembled from these so
 * spacing, corner radii and emphasis stay identical across the app.
 */

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.ink)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft)
            }
        }
        action?.invoke()
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Hjtk.palette.line))
}

@Composable
fun StatusDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft)
    }
}

@Composable
fun EffectBadge(effect: EffectClass) {
    val (label, color) = when (effect) {
        EffectClass.READ -> "읽기" to Hjtk.palette.inkSoft
        EffectClass.LOCAL_WRITE -> "앱 내부" to Hjtk.palette.accent
        EffectClass.EXTERNAL -> "외부 전송" to Hjtk.palette.warn
        EffectClass.SPEND -> "비용" to Hjtk.palette.warn
        EffectClass.DESTRUCTIVE -> "되돌릴 수 없음" to Hjtk.palette.danger
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
fun Pill(text: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val bg = if (selected) Hjtk.palette.accent else Hjtk.palette.surfaceAlt
    val fg = if (selected) Hjtk.palette.accentInk else Hjtk.palette.inkSoft
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

/** Minimal markdown: paragraphs, `·` bullets and **bold** runs. */
@Composable
fun RichText(text: String, color: Color = Hjtk.palette.ink) {
    val lines = text.split("\n")
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (raw in lines) {
            if (raw.isBlank()) {
                Spacer(Modifier.height(4.dp))
                continue
            }
            val trimmed = raw.trim()
            Text(
                text = inlineBold(trimmed),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (trimmed.startsWith("·") || trimmed.startsWith("-")) 14.sp else 15.sp),
                color = if (trimmed.startsWith("·") || trimmed.startsWith("-")) color.copy(alpha = 0.88f) else color
            )
        }
    }
}

private fun inlineBold(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val start = text.indexOf("**", i)
        if (start < 0) {
            append(text.substring(i))
            break
        }
        append(text.substring(i, start))
        val end = text.indexOf("**", start + 2)
        if (end < 0) {
            append(text.substring(start))
            break
        }
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(start + 2, end)) }
        i = end + 2
    }
}

@Composable
fun ApprovalCardView(
    request: ApprovalRequest,
    onRun: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Hjtk.palette.surface, RoundedCornerShape(18.dp))
            .border(1.dp, Hjtk.palette.line, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("확인 필요", style = MaterialTheme.typography.labelLarge, color = Hjtk.palette.warn)
            Spacer(Modifier.width(8.dp))
            EffectBadge(request.effect)
        }
        Spacer(Modifier.height(8.dp))
        Text(request.titleKo, style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.ink)
        Spacer(Modifier.height(4.dp))
        Text(request.summaryKo, style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.inkSoft)
        if (request.details.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Hjtk.palette.surfaceAlt, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                request.details.take(4).forEach { (k, v) ->
                    Row {
                        Text(k, style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft, modifier = Modifier.width(70.dp))
                        Text(v.take(160), style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.ink)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("취소", color = Hjtk.palette.inkSoft) }
            Surface(
                color = Hjtk.palette.accent,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "실행",
                    style = MaterialTheme.typography.titleMedium,
                    color = Hjtk.palette.accentInk,
                    modifier = Modifier
                        .clickable { onRun() }
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun PlanCardView(plan: Plan, doneSteps: Int, state: PlanState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Hjtk.palette.surfaceAlt, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(plan.titleKo, style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.ink)
        Spacer(Modifier.height(6.dp))
        plan.steps.forEachIndexed { i, step ->
            val done = i < doneSteps
            val active = i == doneSteps && state == PlanState.RUNNING
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(
                    Modifier
                        .size(16.dp)
                        .background(
                            when {
                                done -> Hjtk.palette.accent
                                active -> Hjtk.palette.warn.copy(alpha = 0.25f)
                                else -> Hjtk.palette.line
                            },
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (done) "✓" else "${i + 1}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = if (done) Hjtk.palette.accentInk else Hjtk.palette.inkSoft
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    step.titleKo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done || active) Hjtk.palette.ink else Hjtk.palette.inkSoft
                )
                Spacer(Modifier.weight(1f))
                if (step.effect.needsApproval) EffectBadge(step.effect)
            }
        }
    }
}

@Composable
fun ReportCardView(report: ProgressReport) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Hjtk.palette.accentSoft, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("진행 보고", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.accent)
            Spacer(Modifier.weight(1f))
            Text("${report.doneSteps}/${report.totalSteps}", style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.accent)
        }
        Spacer(Modifier.height(4.dp))
        Text(report.headline, style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.ink)
        report.lines.forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Hjtk.palette.inkSoft)
        }
    }
}

@Composable
fun NoticeCardView(title: String, body: String, tone: NoticeTone) {
    val color = when (tone) {
        NoticeTone.NEUTRAL -> Hjtk.palette.inkSoft
        NoticeTone.WARN -> Hjtk.palette.warn
        NoticeTone.ERROR -> Hjtk.palette.danger
        NoticeTone.SUCCESS -> Hjtk.palette.success
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = color)
        Spacer(Modifier.height(2.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.ink.copy(alpha = 0.9f))
    }
}

@Composable
fun EmptyState(title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(Hjtk.palette.surfaceAlt, CircleShape)
        )
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = Hjtk.palette.inkSoft,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onAction) { Text(actionLabel, color = Hjtk.palette.accent) }
        }
    }
}

@Composable
fun IconCircle(icon: ImageVector, tint: Color = Hjtk.palette.accent, bg: Color = Hjtk.palette.accentSoft) {
    Box(
        Modifier.size(34.dp).background(bg, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Hjtk.palette.ink)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Hjtk.palette.inkSoft)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
