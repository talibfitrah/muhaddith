package org.murabbie.muhaddith.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.murabbie.muhaddith.data.Engine
import org.murabbie.muhaddith.search.ArabicText

@Composable
fun EngineChip(engine: Engine, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(engine.label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
fun EngineBadge(engine: Engine) {
    val color = when (engine) {
        Engine.LITERAL -> MaterialTheme.colorScheme.primary
        Engine.MORPH -> MaterialTheme.colorScheme.secondary
        Engine.SEMANTIC -> MaterialTheme.colorScheme.tertiary
    }
    Box(
        Modifier
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(engine.label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun GradePill(grade: String?) {
    if (grade.isNullOrBlank()) return
    val color = when {
        grade.contains("صحيح") -> MaterialTheme.colorScheme.primary
        grade.contains("حسن") -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.13f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(grade, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** رأس قسم مطويّ: يُضغط ليُفتح أو يُطوى، مع عدد اختياري */
@Composable
fun FoldHeader(title: String, open: Boolean, onToggle: () -> Unit, count: Int? = null, trailing: (@Composable () -> Unit)? = null) {
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) { SectionLabel(title + (count?.let { " (${org.murabbie.muhaddith.search.ArabicText.arabicDigits(it)})" } ?: "")) }
        trailing?.invoke()
        Text(if (open) "▲" else "▼", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
fun NoticeBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⚠", modifier = Modifier.padding(end = 8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/** إعدادات العرض الحالية (التشكيل والإبراز) */
val LocalDisplay = androidx.compose.runtime.staticCompositionLocalOf { org.murabbie.muhaddith.data.AppSettings() }

/** نص للعرض بحسب إعداد التشكيل */
@Composable
fun display(text: String?): String {
    if (text.isNullOrEmpty()) return ""
    return if (LocalDisplay.current.showTashkeel) text else ArabicText.stripDiacritics(text)
}

/** يبرز مواضع الكلمات المطلوبة داخل المتن مع مراعاة التطبيع */
fun highlight(text: String, terms: List<String>, color: Color): AnnotatedString {
    if (terms.isEmpty()) return AnnotatedString(text)
    val words = text.split(" ")
    return buildAnnotatedString {
        words.forEachIndexed { i, w ->
            val n = ArabicText.normalize(w)
            val st = ArabicText.stripArticle(n)
            val hit = n.isNotEmpty() && terms.any { t ->
                n == t || st == t || (t.length >= 3 && (n.startsWith(t) || st.startsWith(t)))
            }
            if (hit) {
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(w) }
            } else append(w)
            if (i < words.lastIndex) append(" ")
        }
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ClickableRow(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** نص مع إبراز ألفاظ البحث (الألفاظ تُطبَّع داخليًّا) */
@Composable
fun HighlightedText(text: String, terms: List<String>, style: androidx.compose.ui.text.TextStyle, maxLines: Int = Int.MAX_VALUE) {
    val norm = terms.map { ArabicText.stripArticle(ArabicText.normalize(it)) }.filter { it.length > 1 }
    Text(highlight(text, norm, MaterialTheme.colorScheme.primary), style = style, maxLines = maxLines)
}
