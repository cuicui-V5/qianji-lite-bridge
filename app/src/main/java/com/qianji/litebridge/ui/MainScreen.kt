package com.qianji.litebridge.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.qianji.litebridge.QianjiApp
import com.qianji.litebridge.model.BillItem
import com.qianji.litebridge.service.FloatingCapsuleService
import com.qianji.litebridge.service.GeminiService
import com.qianji.litebridge.service.RateLimitQueue
import com.qianji.litebridge.ui.components.HoldToSpeakButton
import com.qianji.litebridge.ui.theme.DangerRed
import com.qianji.litebridge.ui.theme.PrimaryBlue
import com.qianji.litebridge.ui.theme.SuccessGreen
import com.qianji.litebridge.util.PreferencesManager
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    prefs: PreferencesManager,
    queue: RateLimitQueue,
    geminiService: GeminiService,
    onAddLog: (String) -> Unit,
    modifier: Modifier = Modifier,
    quickRecordTrigger: Boolean = false,
    onQuickRecordHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val isFloatingRunning by FloatingCapsuleService.isRunning

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            FloatingCapsuleService.start(context)
            prefs.floatingCapsuleEnabled = true
            Toast.makeText(context, "悬浮胶囊已启动", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "未授予录音权限，无法开启悬浮窗语音记账", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleFloating() {
        if (!isFloatingRunning) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "请在系统设置中允许“显示在其他应用上层 / 悬浮窗”权限", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
                return
            }
            val hasAudio = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasAudio) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            FloatingCapsuleService.start(context)
            prefs.floatingCapsuleEnabled = true
            Toast.makeText(context, "悬浮胶囊已开启", Toast.LENGTH_SHORT).show()
        } else {
            FloatingCapsuleService.stop(context)
            prefs.floatingCapsuleEnabled = false
            Toast.makeText(context, "悬浮胶囊已关闭", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "钱迹 AI 语音记账",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "设置与调试界面",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 极简首页控件：精简为一个极简直接的开启/关闭悬浮窗按钮
            if (!isFloatingRunning) {
                Button(
                    onClick = { toggleFloating() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "开启桌面悬浮窗模式",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            } else {
                OutlinedButton(
                    onClick = { toggleFloating() },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "桌面悬浮窗已开启 (点击关闭)",
                        color = SuccessGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            // 响应式跟踪设置版本，实时刷新当前模式文本
            val settingsVersion = prefs.settingsVersion

            // 首页中上方空白区域：识别成功的账单历史列表
            val recognizedBills = QianjiApp.instance.recognizedBills

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已识别账单记录 (${recognizedBills.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (recognizedBills.isNotEmpty()) {
                        Text(
                            text = "清空记录",
                            fontSize = 12.sp,
                            color = PrimaryBlue,
                            modifier = Modifier
                                .clickable { recognizedBills.clear() }
                                .padding(4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (recognizedBills.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.ReceiptLong,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "暂无识别记录",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "长按下方按钮或使用桌面快捷方式即可语音记账",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recognizedBills) { bill ->
                            RecognizedBillCard(bill)
                        }
                    }
                }
            }

            // 首页最主要的核心控件：超大按住记账按钮
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HoldToSpeakButton(
                    geminiService = geminiService,
                    queue = queue,
                    prefs = prefs,
                    onLog = onAddLog,
                    quickRecordTrigger = quickRecordTrigger,
                    onQuickRecordHandled = onQuickRecordHandled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "当前引擎: ${prefs.getModeSummary()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun RecognizedBillCard(bill: BillItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 上行：[分类标签] 备注
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(PrimaryBlue.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = bill.catename ?: "未分类",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryBlue
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = bill.remark ?: bill.catename ?: "无备注",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 下行：时间（不破坏横向布局） + 账本
                Text(
                    text = "${bill.time ?: "刚才"}${if (!bill.bookname.isNullOrBlank()) " · ${bill.bookname}" else ""}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // 右侧金额：-¥25.00 / +¥100.00
            val isIncome = bill.type == 1
            val prefix = if (isIncome) "+" else "-"
            val amountColor = if (isIncome) SuccessGreen else DangerRed
            Text(
                text = "$prefix¥${String.format(Locale.getDefault(), "%.2f", bill.money)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = amountColor
            )
        }
    }
}
