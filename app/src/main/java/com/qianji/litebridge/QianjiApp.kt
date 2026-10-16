package com.qianji.litebridge

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import com.qianji.litebridge.service.AiServiceHub
import com.qianji.litebridge.service.BaiduSpeechService
import com.qianji.litebridge.service.CustomAiService
import com.qianji.litebridge.service.GeminiService
import com.qianji.litebridge.service.HttpServer
import com.qianji.litebridge.service.RateLimitQueue
import com.qianji.litebridge.util.NetworkUtils
import com.qianji.litebridge.util.PreferencesManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QianjiApp : Application() {

    companion object {
        lateinit var instance: QianjiApp
            private set
    }

    lateinit var prefs: PreferencesManager
        private set
    lateinit var queue: RateLimitQueue
        private set
    lateinit var geminiService: GeminiService
        private set
    lateinit var customAiService: CustomAiService
        private set
    lateinit var baiduSpeechService: BaiduSpeechService
        private set
    lateinit var aiServiceHub: AiServiceHub
        private set

    var httpServer: HttpServer? = null
        private set

    val logs = mutableStateListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PreferencesManager(this)
        queue = RateLimitQueue(this, prefs) { msg -> addLog(msg) }
        geminiService = GeminiService(prefs)
        customAiService = CustomAiService(prefs)
        baiduSpeechService = BaiduSpeechService(prefs)
        aiServiceHub = AiServiceHub(prefs, geminiService, customAiService, baiduSpeechService)

        // 默认启动内置 HTTP 局域网网关
        ensureHttpServerStarted(8848)
    }

    fun addLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $message"
        if (Looper.myLooper() == Looper.getMainLooper()) {
            logs.add(entry)
            if (logs.size > 250) {
                logs.removeAt(0)
            }
        } else {
            mainHandler.post {
                logs.add(entry)
                if (logs.size > 250) {
                    logs.removeAt(0)
                }
            }
        }
    }

    fun ensureHttpServerStarted(port: Int = 8848) {
        if (httpServer != null) return
        try {
            val localIp = NetworkUtils.getLocalIpAddress(this)
            httpServer = HttpServer(
                host = "0.0.0.0",
                port = port,
                prefs = prefs,
                queue = queue,
                geminiService = geminiService,
                onLog = { msg -> addLog(msg) }
            ).apply {
                start()
            }
            addLog("🚀 局域网 HTTP 网关就绪: http://$localIp:$port")
        } catch (e: Exception) {
            addLog("❌ 启动 HTTP 网关失败: ${e.message}")
            e.printStackTrace()
        }
    }

    fun stopHttpServer() {
        try {
            httpServer?.stop()
            httpServer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
