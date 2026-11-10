package com.deskpet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** First-time creation starts at a form card, then enters the familiar bone editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPetFormsEntry(onCreateFirst: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("🌱 创建桌面伙伴") },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回") } })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("先创建第一种形态", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge)
            Text("形态是桌宠的一套完整外观。完成第一种形态后，可以复制它，换耳朵、尾巴和翅膀，保留原来的绑骨设置。",
                style = MaterialTheme.typography.bodyMedium)
            ElevatedCard(shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("🐾", style = MaterialTheme.typography.displayMedium)
                    Text("形态 1 · 默认形态", style = MaterialTheme.typography.titleMedium)
                    Text("尚未绑定图片和骨骼", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onCreateFirst, modifier = Modifier.fillMaxWidth()) {
                        Text("＋ 创建形态 · 进入绑骨")
                    }
                }
            }
            Text("💡 完成后回到「形态管理」，可以一键复制成形态 2、形态 3……",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
