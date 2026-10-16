package com.qianji.litebridge.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianji.litebridge.ui.theme.PrimaryBlue
import com.qianji.litebridge.ui.theme.TerminalBackground
import com.qianji.litebridge.ui.theme.TerminalText

@Composable
fun LogConsole(
    logs: List<String>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 自动滚动到最新日志底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // 构造单次完整富文本样式，彻底杜绝成百上千个 Text 节点产生的布局测量与掉帧
    val annotatedLogText = remember(logs.size, logs.lastOrNull()) {
        buildAnnotatedString {
            logs.forEachIndexed { index, log ->
                val color = when {
                    log.contains("❌") -> Color(0xFFF87171)
                    log.contains("⚠️") -> Color(0xFFFBBF24)
                    log.contains("✅") || log.contains("✨") -> Color(0xFF4ADE80)
                    log.contains("🛫") || log.contains("🔗") -> Color(0xFF60A5FA)
                    log.contains("📄") || log.contains("📥") -> Color(0xFFA78BFA)
                    else -> TerminalText
                }
                withStyle(SpanStyle(color = color)) {
                    append(log)
                }
                if (index < logs.size - 1) {
                    append("\n\n")
                }
            }
        }
    }

    fun copyAllLogs() {
        if (logs.isEmpty()) {
            Toast.makeText(context, "当前日志为空", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val fullLogText = logs.joinToString("\n\n")
        val clip = ClipData.newPlainText("钱迹网关详细日志", fullLogText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "已成功复制全部详细日志 (${logs.size} 条)", Toast.LENGTH_SHORT).show()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // 控制台标题栏与快捷动作
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Console",
                        tint = PrimaryBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "实时网关调试日志 (${logs.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 一键复制全部日志按钮
                    IconButton(
                        onClick = { copyAllLogs() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "一键复制全部日志",
                            tint = PrimaryBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 清空日志按钮
                    IconButton(
                        onClick = onClearLogs,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "清空日志",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 终端日志框：单节点直接渲染全量日志，原生流畅滑动
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TerminalBackground)
                    .verticalScroll(scrollState)
                    .padding(10.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "等待请求接入中...\n• 长按悬浮窗或主界面麦克风开始语音记账\n• 通过局域网 HTTP POST 提交账单\n• 点击右上角可一键复制全部请求与响应数据",
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(4.dp)
                    )
                } else {
                    Text(
                        text = annotatedLogText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.5.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
