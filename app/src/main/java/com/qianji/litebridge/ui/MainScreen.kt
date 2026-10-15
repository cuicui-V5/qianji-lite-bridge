package com.qianji.litebridge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qianji.litebridge.service.GeminiService
import com.qianji.litebridge.service.RateLimitQueue
import com.qianji.litebridge.ui.components.HoldToSpeakButton
import com.qianji.litebridge.ui.components.LogConsole
import com.qianji.litebridge.ui.components.SettingsCard
import com.qianji.litebridge.ui.components.StatusCard
import com.qianji.litebridge.util.PreferencesManager

@Composable
fun MainScreen(
    ipAddress: String,
    port: Int,
    queue: RateLimitQueue,
    geminiService: GeminiService,
    prefs: PreferencesManager,
    logs: List<String>,
    onClearLogs: () -> Unit,
    onAddLog: (String) -> Unit
) {
    val queueSize by queue.queueSize.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 1. 顶部状态卡片 (IP, 端口, 队列计数)
            StatusCard(
                ipAddress = ipAddress,
                port = port,
                queueSize = queueSize
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 可折叠配置区 (输入即刻自动保存，亦可点击下方按钮收起)
            SettingsCard(
                prefs = prefs
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. 中部实时日志控制台 (固定高度，自适应滑动)
            LogConsole(
                logs = logs,
                onClearLogs = onClearLogs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 4. 底部超大交互区：按住说话记账
            HoldToSpeakButton(
                geminiService = geminiService,
                queue = queue,
                onLog = onAddLog,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
