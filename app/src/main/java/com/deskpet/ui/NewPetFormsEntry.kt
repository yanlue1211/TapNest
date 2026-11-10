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
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NestHero(
                title = "它的第一种模样",
                subtitle = "先做好基础形态，再复制、换装，慢慢收集更多模样",
                emoji = "🐾"
            )
            NestSection("开始制作", "先绑定身体，再按需添加头部、耳朵、尾巴或翅膀")
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
            NestNote("完成第一种形态之后，形态管理里可以复制、重命名和继续编辑更多形态。")
        }
    }
}
