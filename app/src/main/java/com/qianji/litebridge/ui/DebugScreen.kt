package com.qianji.litebridge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qianji.litebridge.service.RateLimitQueue
import com.qianji.litebridge.ui.components.LogConsole
import com.qianji.litebridge.ui.components.SettingsCard
import com.qianji.litebridge.ui.components.StatusCard
import com.qianji.litebridge.util.PreferencesManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    ipAddress: String,
    port: Int,
    queue: RateLimitQueue,
    prefs: PreferencesManager,
    logs: List<String>,
    onClearLogs: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queueSize by queue.queueSize.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置与调试控制台",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回主页"
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
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 1. 网络与队列状态卡片 (IP, 端口, 队列计数)
            StatusCard(
                ipAddress = ipAddress,
                port = port,
                queueSize = queueSize
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 2. 实时详细调试日志控制台 (包含一键复制与清空)
            LogConsole(
                logs = logs,
                onClearLogs = onClearLogs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 3. 配置参数卡片 (API Key, 分类, 账户, 账本, 模型等)
            SettingsCard(
                prefs = prefs
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
