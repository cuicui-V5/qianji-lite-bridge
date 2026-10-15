package com.qianji.litebridge

import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.qianji.litebridge.service.FloatingCapsuleService
import com.qianji.litebridge.ui.MainScreen
import com.qianji.litebridge.ui.theme.QianjiLiteBridgeTheme
import com.qianji.litebridge.util.NetworkUtils

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保持屏幕常亮 (前台运行模式)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = QianjiApp.instance
        val localIp = NetworkUtils.getLocalIpAddress(this)
        val port = 8848

        // 确保 HTTP 服务启动
        app.ensureHttpServerStarted(port)

        // 若用户配置了开机或常驻开启悬浮窗且拥有权限，自动唤起胶囊
        if (app.prefs.floatingCapsuleEnabled && Settings.canDrawOverlays(this) && !FloatingCapsuleService.isRunning.value) {
            FloatingCapsuleService.start(this)
        }

        setContent {
            QianjiLiteBridgeTheme {
                MainScreen(
                    ipAddress = localIp,
                    port = port,
                    queue = app.queue,
                    geminiService = app.geminiService,
                    prefs = app.prefs,
                    logs = app.logs,
                    onClearLogs = { app.logs.clear() },
                    onAddLog = { message -> app.addLog(message) }
                )
            }
        }
    }
}
