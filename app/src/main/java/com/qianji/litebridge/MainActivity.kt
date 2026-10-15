package com.qianji.litebridge

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateListOf
import com.qianji.litebridge.service.GeminiService
import com.qianji.litebridge.service.HttpServer
import com.qianji.litebridge.service.RateLimitQueue
import com.qianji.litebridge.ui.MainScreen
import com.qianji.litebridge.ui.theme.QianjiLiteBridgeTheme
import com.qianji.litebridge.util.NetworkUtils
import com.qianji.litebridge.util.PreferencesManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var queue: RateLimitQueue
    private lateinit var geminiService: GeminiService
    private var httpServer: HttpServer? = null

    private val logs = mutableStateListOf<String>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保持屏幕常亮 (前台运行模式)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = PreferencesManager(this)
        queue = RateLimitQueue(this, prefs) { message -> addLog(message) }
        geminiService = GeminiService(prefs)

        val localIp = NetworkUtils.getLocalIpAddress(this)
        val port = 8848

        // 启动内置 NanoHTTPD 局域网服务
        startHttpServer(port)

        addLog("🚀 钱迹网关就绪，局域网监听地址: http://$localIp:$port")

        setContent {
            QianjiLiteBridgeTheme {
                MainScreen(
                    ipAddress = localIp,
                    port = port,
                    queue = queue,
                    geminiService = geminiService,
                    prefs = prefs,
                    logs = logs,
                    onClearLogs = { logs.clear() },
                    onAddLog = { message -> addLog(message) }
                )
            }
        }
    }

    private fun startHttpServer(port: Int) {
        try {
            httpServer = HttpServer(
                host = "0.0.0.0",
                port = port,
                prefs = prefs,
                queue = queue,
                geminiService = geminiService,
                onLog = { msg -> runOnUiThread { addLog(msg) } }
            ).apply {
                start()
            }
        } catch (e: Exception) {
            addLog("❌ 启动 HTTP 网关失败: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun addLog(message: String) {
        val timestamp = timeFormat.format(Date())
        logs.add("[$timestamp] $message")
        if (logs.size > 200) {
            logs.removeAt(0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            httpServer?.stop()
            httpServer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
