package com.qianji.litebridge.service

import com.qianji.litebridge.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

class BaiduSpeechService(
    private val prefs: PreferencesManager
) {
    @Volatile
    private var cachedToken: String? = null
    @Volatile
    private var tokenExpireTime: Long = 0L

    /**
     * 获取百度 Access Token (自动缓存并在过期前复用，避免每次重复申请鉴权)
     */
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val apiKey = prefs.baiduApiKey.trim()
        val secretKey = prefs.baiduSecretKey.trim()

        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            throw IllegalStateException("未配置百度语音识别 API Key 或 Secret Key，请在设置中配置！")
        }

        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpireTime - 60_000L) {
            return@withContext cachedToken!!
        }

        val urlStr = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials" +
                "&client_id=${URLEncoder.encode(apiKey, "UTF-8")}" +
                "&client_secret=${URLEncoder.encode(secretKey, "UTF-8")}"

        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 10000
            doInput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        val responseCode = conn.responseCode
        val responseBody = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $responseCode"
            throw IllegalStateException("获取百度 Access Token 失败 (HTTP $responseCode): $err")
        }

        val json = JSONObject(responseBody)
        if (json.has("error")) {
            val desc = json.optString("error_description", json.optString("error"))
            throw IllegalStateException("百度鉴权认证失败: $desc")
        }

        val token = json.getString("access_token")
        val expiresIn = json.optLong("expires_in", 2592000L) // 默认 30 天有效期
        cachedToken = token
        tokenExpireTime = now + expiresIn * 1000L

        token
    }

    /**
     * 调用百度短语音识别接口 (支持标准版 dev_pid=1537 / 极速版 dev_pid=80001)
     */
    suspend fun transcribeAudio(
        audioFile: File,
        onProgress: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        val audioBytes = audioFile.readBytes()
        val audioKb = String.format(Locale.US, "%.1f", audioBytes.size / 1024.0)
        val devPid = prefs.baiduDevPid
        val isPro = devPid == 80001
        val endpoint = if (isPro) "https://vop.baidu.com/pro_api" else "https://vop.baidu.com/server_api"
        val engineDesc = if (isPro) "极速版(80001)" else "标准版($devPid)"

        val requestTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        onProgress("🛫 [百度 ASR 请求] 时间戳: $requestTime | 引擎: $engineDesc")
        onProgress("📦 [载荷数据] 录音音频: ${audioKb} KB (${audioFile.name})")

        val startTime = System.currentTimeMillis()
        val base64Audio = Base64.getEncoder().encodeToString(audioBytes)

        val payload = JSONObject().apply {
            put("format", "m4a")
            put("rate", 16000)
            put("channel", 1)
            put("cuid", "qianji_ai_bridge_android")
            put("token", token)
            put("dev_pid", devPid)
            put("speech", base64Audio)
            put("len", audioBytes.size)
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 15000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Connection", "Keep-Alive")
        }

        val responseCode = try {
            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            conn.responseCode
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            throw IllegalStateException("连接百度语音服务器超时或失败 (${elapsed}ms): ${e.message}", e)
        }

        val elapsed = System.currentTimeMillis() - startTime
        val responseBody = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $responseCode"
            throw IllegalStateException("百度语音识别 HTTP 失败 ($responseCode, 耗时 ${elapsed}ms): $err")
        }

        onProgress("⏱️ [百度 ASR 耗时] ${elapsed}ms")

        val root = JSONObject(responseBody)
        val errNo = root.optInt("err_no", -1)
        if (errNo != 0) {
            val errMsg = root.optString("err_msg", "未知错误")
            val explanation = when (errNo) {
                3301 -> "音频质量过差或全为静音"
                3302 -> "Token 鉴权失败，请检查 API Key/Secret Key"
                3303 -> "语音服务器后端异常"
                3307 -> "语音识别模型不存在或未开通权限"
                3308 -> "音频格式或采样率不受支持 (要求 16000Hz 单声道 m4a)"
                3310 -> "输入的音频时长过长 (最大支持 60 秒)"
                else -> errMsg
            }
            throw IllegalStateException("百度语音识别失败 (err_no=$errNo): $explanation")
        }

        val resultArray = root.optJSONArray("result")
        if (resultArray == null || resultArray.length() == 0) {
            throw IllegalStateException("百度语音识别未返回有效文字内容")
        }

        val transcribedText = resultArray.getString(0).trim()
        onProgress("📝 [百度 ASR 转录结果]: \"$transcribedText\"")
        transcribedText
    }
}
