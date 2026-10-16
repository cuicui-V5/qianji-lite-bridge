package com.qianji.litebridge

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.qianji.litebridge.service.FloatingCapsuleService
import com.qianji.litebridge.ui.DebugScreen
import com.qianji.litebridge.ui.MainScreen
import com.qianji.litebridge.ui.theme.QianjiLiteBridgeTheme
import com.qianji.litebridge.util.NetworkUtils

enum class AppScreen {
    MAIN,
    DEBUG
}

class MainActivity : ComponentActivity() {

    private var quickRecordTrigger by mutableStateOf(false)
    private var requestedScreen by mutableStateOf(AppScreen.MAIN)

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

        checkShortcutIntent(intent)

        setContent {
            QianjiLiteBridgeTheme {
                var currentScreen by remember { mutableStateOf(requestedScreen) }

                // 如果有快捷意图打断，强制切回主页面
                if (requestedScreen == AppScreen.MAIN && currentScreen != AppScreen.MAIN && quickRecordTrigger) {
                    currentScreen = AppScreen.MAIN
                }

                // 系统物理返回键 / 手势返回监听：在调试页面时返回主页面
                BackHandler(enabled = currentScreen == AppScreen.DEBUG) {
                    currentScreen = AppScreen.MAIN
                }

                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        if (targetState == AppScreen.DEBUG) {
                            (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> -width / 3 } + fadeOut()
                            )
                        } else {
                            (slideInHorizontally { width -> -width / 3 } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> width } + fadeOut()
                            )
                        }
                    },
                    label = "ScreenTransition"
                ) { screen ->
                    when (screen) {
                        AppScreen.MAIN -> {
                            MainScreen(
                                onNavigateToSettings = { currentScreen = AppScreen.DEBUG },
                                prefs = app.prefs,
                                queue = app.queue,
                                geminiService = app.geminiService,
                                onAddLog = { message -> app.addLog(message) },
                                quickRecordTrigger = quickRecordTrigger,
                                onQuickRecordHandled = { quickRecordTrigger = false }
                            )
                        }

                        AppScreen.DEBUG -> {
                            DebugScreen(
                                ipAddress = localIp,
                                port = port,
                                queue = app.queue,
                                prefs = app.prefs,
                                logs = app.logs,
                                onClearLogs = { app.logs.clear() },
                                onNavigateBack = { currentScreen = AppScreen.MAIN }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkShortcutIntent(intent)
    }

    private fun checkShortcutIntent(intent: Intent?) {
        if (intent == null) return
        val isQuickRecord = intent.getBooleanExtra("action_quick_record", false) ||
                intent.action == "com.qianji.litebridge.ACTION_QUICK_RECORD"
        if (isQuickRecord) {
            requestedScreen = AppScreen.MAIN
            quickRecordTrigger = true
        }
    }
}
