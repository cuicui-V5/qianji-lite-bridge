package com.qianji.litebridge.service

import com.qianji.litebridge.model.BillItem
import com.qianji.litebridge.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CustomAiService(
    private val prefs: PreferencesManager
) {
    /**
     * 调用兼容 OpenAI 格式的 Chat Completions 接口
     * （支持 DeepSeek 官方 API、硅基流动 SiliconFlow、阿里云百炼、Moonshot、Ollama 等）
     */
    suspend fun parseText(
        textPrompt: String,
        onProgress: (String) -> Unit = {}
    ): List<BillItem> = withContext(Dispatchers.IO) {
        val apiKey = prefs.customAiApiKey.trim()
        val rawBaseUrl = prefs.customAiBaseUrl.trim().ifEmpty { PreferencesManager.DEFAULT_CUSTOM_AI_BASE_URL }
        val modelName = prefs.customAiModel.trim().ifEmpty { PreferencesManager.DEFAULT_CUSTOM_AI_MODEL }

        if (apiKey.isEmpty()) {
            throw IllegalStateException("未配置自定义 AI 的 API Key，请在设置中配置！")
        }

        // 规范化 URL 地址 (兼容用户填入域名根目录、/v1 或直接填入 /chat/completions)
        var targetUrl = rawBaseUrl.trimEnd('/')
        if (!targetUrl.endsWith("/chat/completions")) {
            targetUrl = if (targetUrl.endsWith("/v1")) {
                "$targetUrl/chat/completions"
            } else {
                "$targetUrl/v1/chat/completions"
            }
        }

        val currentDateTime = BillPromptHelper.getCurrentDateTimeString()
        val systemPrompt = BillPromptHelper.buildPrompt(prefs, currentDateTime)

        val requestTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        onProgress("🛫 [自定义 AI 请求] 时间戳: $requestTime | 模型: $modelName | 接口: $targetUrl")
        onProgress("📦 [载荷数据] 文本输入: \"$textPrompt\"")

        val startTime = System.currentTimeMillis()

        // 构造 OpenAI 兼容请求体 (尝试开启 json_object 响应约束加速解析)
        val payloadObj = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "用户文本输入内容：$textPrompt")
                })
            })
            put("temperature", 0.1)
            put("response_format", JSONObject().apply {
                put("type", "json_object")
            })
        }

        var (responseCode, responseBody) = try {
            sendChatRequest(targetUrl, apiKey, payloadObj.toString())
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            throw IllegalStateException("连接自定义 AI 服务器失败 (${elapsed}ms): ${e.message}", e)
        }

        // 如果服务端提示不支持 response_format，自动回退普通请求重试
        if (responseCode == 400 && responseBody.contains("response_format", ignoreCase = true)) {
            onProgress("⚠️ 服务商不支持 response_format，自动回退普通模式重试...")
            payloadObj.remove("response_format")
            val fallbackResult = sendChatRequest(targetUrl, apiKey, payloadObj.toString())
            responseCode = fallbackResult.first
            responseBody = fallbackResult.second
        }

        val elapsed = System.currentTimeMillis() - startTime
        if (responseCode !in 200..299) {
            throw IllegalStateException("自定义 AI 请求失败 (HTTP $responseCode, 耗时 ${elapsed}ms): $responseBody")
        }

        onProgress("⏱️ [自定义 AI 耗时] ${elapsed}ms")

        val rootObj = JSONObject(responseBody)
        val choices = rootObj.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw IllegalStateException("自定义 AI 未返回有效 choices 响应: $responseBody")
        }

        val firstChoice = choices.getJSONObject(0)
        val message = firstChoice.optJSONObject("message")
        val content = message?.optString("content") ?: ""

        if (content.isBlank()) {
            throw IllegalStateException("自定义 AI 返回的内容为空")
        }

        onProgress("📥 [自定义 AI 原始响应]:\n$content")

        BillPromptHelper.parseJsonToBills(content, currentDateTime, prefs, onProgress)
    }

    private fun sendChatRequest(targetUrl: String, apiKey: String, jsonPayload: String): Pair<Int, String> {
        val url = URL(targetUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 30000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Connection", "Keep-Alive")
        }

        val code = try {
            conn.outputStream.use { os ->
                os.write(jsonPayload.toByteArray(Charsets.UTF_8))
            }
            conn.responseCode
        } catch (e: Exception) {
            throw e
        }

        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $code"
        }

        return Pair(code, body)
    }
}
