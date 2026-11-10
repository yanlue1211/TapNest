package com.deskpet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared design pieces; they do not own navigation or any persistent state. */
@Composable
internal fun NestHero(
    title: String,
    subtitle: String,
    emoji: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
    compact: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.primaryContainer,
        shape = RoundedCornerShape(if (compact) 20.dp else 26.dp),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.12f))
    ) {
        Row(
            Modifier.padding(horizontal = if (compact) 14.dp else 18.dp,
                vertical = if (compact) 13.dp else 19.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("点 点 栖  /  TAP NEST", style = MaterialTheme.typography.labelSmall,
                    color = colors.primary, fontWeight = FontWeight.SemiBold)
                Text(title, style = if (compact) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.headlineSmall,
                    color = colors.onPrimaryContainer, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = colors.onPrimaryContainer.copy(alpha = 0.78f))
                if (tag != null) {
                    Spacer(Modifier.height(3.dp))
                    Surface(shape = RoundedCornerShape(50),
                        color = colors.surface.copy(alpha = 0.72f)) {
                        Text(tag, style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            Box(
                Modifier.size(if (compact) 50.dp else 66.dp)
                    .background(colors.surface.copy(alpha = 0.76f), RoundedCornerShape(20.dp))
                    .border(1.dp, colors.primary.copy(alpha = 0.10f),
                        RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, style = if (compact) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

@Composable
internal fun NestSection(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        if (!description.isNullOrBlank()) {
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun NestPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun NestNote(message: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = colors.secondaryContainer) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("💡", style = MaterialTheme.typography.titleMedium)
            Text(message, color = colors.onSecondaryContainer,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}
