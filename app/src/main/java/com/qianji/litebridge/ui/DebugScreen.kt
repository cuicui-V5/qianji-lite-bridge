package com.qianji.litebridge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianji.litebridge.service.RateLimitQueue
import com.qianji.litebridge.ui.components.LogConsole
import com.qianji.litebridge.ui.components.SettingsCard
import com.qianji.litebridge.ui.components.StatusCard
import com.qianji.litebridge.ui.theme.PrimaryBlue
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
    var saveAction by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

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
                actions = {
                    Button(
                        onClick = { saveAction?.invoke(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "保存配置",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "保存",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
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

            // 3. 配置参数卡片 (直接全量展开各设置板块)
            SettingsCard(
                prefs = prefs,
                onRegisterSave = { action -> saveAction = action }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
