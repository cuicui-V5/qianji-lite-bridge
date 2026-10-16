package com.qianji.litebridge.service

import com.qianji.litebridge.model.BillItem
import com.qianji.litebridge.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        val systemPrompt = BillPromptHelper.buildSystemPrompt(prefs)
        val userContent = BillPromptHelper.buildUserContent(textPrompt, currentDateTime)

        // 思考模式智能决策 (关 / 低 / 高 / 自动)
        val textLength = textPrompt.length
        val thinkingMode = prefs.customAiThinkingMode
        val (effectiveThinkingType, effectiveEffort, thinkingDesc) = when (thinkingMode) {
            "disabled" -> Triple("disabled", null, "关闭思考 (极速直出)")
            "low" -> Triple("enabled", "low", "轻度思考 (Low)")
            "high" -> Triple("enabled", "high", "深度思考 (High)")
            else -> { // "auto" 自动模式
                val threshold = prefs.customAiAutoThreshold
                val autoEffort = prefs.customAiAutoEffort
                if (textLength > threshold) {
                    Triple("enabled", autoEffort, "自动决策: 字数($textLength) > 阈值($threshold)，开启 $autoEffort 推理")
                } else {
                    Triple("disabled", null, "自动决策: 字数($textLength) <= 阈值($threshold)，关闭思考(极速直出)")
                }
            }
        }

        val requestTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        onProgress("🛫 [自定义 AI 请求] 时间戳: $requestTime | 模型: $modelName | 思考: $thinkingDesc")
        onProgress("📦 [载荷数据] 文本输入 ($textLength 字): \"$textPrompt\"")

        // 构造 OpenAI 兼容请求体 (开启 json_object 响应约束与思考模式配置)
        // systemPrompt 保持完全静态，极大提升服务端 Context Caching 命中率
        val payloadObj = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 2048)
            put("response_format", JSONObject().apply {
                put("type", "json_object")
            })

            // 根据 DeepSeek 规范注入思考模式控制参数
            if (effectiveThinkingType == "disabled") {
                put("thinking", JSONObject().apply {
                    put("type", "disabled")
                })
            } else if (effectiveThinkingType == "enabled") {
                put("thinking", JSONObject().apply {
                    put("type", "enabled")
                })
                if (effectiveEffort != null) {
                    put("reasoning_effort", effectiveEffort)
                }
            }
        }

        // 自动重试机制 (最多 2 次尝试：初次 + 1次自动重试)
        var currentThinkingType = effectiveThinkingType
        var lastException: Exception? = null
        for (attempt in 1..2) {
            val startTime = System.currentTimeMillis()
            if (attempt > 1) {
                if (effectiveThinkingType == "disabled" && currentThinkingType == "disabled") {
                    currentThinkingType = "enabled"
                    payloadObj.put("thinking", JSONObject().apply {
                        put("type", "enabled")
                    })
                    payloadObj.put("reasoning_effort", "low")
                    onProgress("🔄 极速直出未提取到有效金额，自动重试升格为 [low 推理模式] 深度分析...")
                } else {
                    onProgress("🔄 正在自动重试第 ${attempt - 1} 次请求...")
                }
                delay(600)
            }

            var (responseCode, responseBody) = try {
                sendChatRequest(targetUrl, apiKey, payloadObj.toString())
            } catch (e: Exception) {
                lastException = e
                if (attempt == 1) {
                    onProgress("⚠️ 连接自定义 AI 异常 (${e.message})，正在自动重试...")
                    continue
                } else {
                    throw IllegalStateException("连接自定义 AI 服务器失败: ${e.message}", e)
                }
            }

            // 兼容性保护 1：如果服务商报错不支持 thinking 参数，剔除后重试
            if (responseCode == 400 && responseBody.contains("thinking", ignoreCase = true)) {
                onProgress("⚠️ 服务商不支持 thinking 参数，自动回退普通模式重试...")
                payloadObj.remove("thinking")
                payloadObj.remove("reasoning_effort")
                val fallbackResult = sendChatRequest(targetUrl, apiKey, payloadObj.toString())
                responseCode = fallbackResult.first
                responseBody = fallbackResult.second
            }

            // 兼容性保护 2：如果服务商报错不支持 response_format，自动剔除后重试
            if (responseCode == 400 && responseBody.contains("response_format", ignoreCase = true)) {
                onProgress("⚠️ 服务商不支持 response_format，自动回退普通模式重试...")
                payloadObj.remove("response_format")
                val fallbackResult = sendChatRequest(targetUrl, apiKey, payloadObj.toString())
                responseCode = fallbackResult.first
                responseBody = fallbackResult.second
            }

            val elapsed = System.currentTimeMillis() - startTime
            if (responseCode !in 200..299) {
                lastException = IllegalStateException("HTTP $responseCode: $responseBody")
                if (attempt == 1) {
                    onProgress("⚠️ 自定义 AI 响应失败 (HTTP $responseCode, 耗时 ${elapsed}ms)，正在自动重试...")
                    continue
                } else {
                    throw IllegalStateException("自定义 AI 请求失败 (HTTP $responseCode, 耗时 ${elapsed}ms): $responseBody")
                }
            }

            onProgress("⏱️ [自定义 AI 耗时] ${elapsed}ms")

            val rootObj = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                lastException = e
                if (attempt == 1) {
                    onProgress("⚠️ 自定义 AI 响应非 JSON 结构，正在自动重试...")
                    continue
                } else {
                    throw IllegalStateException("自定义 AI 响应数据格式非法: $responseBody")
                }
            }

            val choices = rootObj.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                lastException = IllegalStateException("未返回有效 choices 响应")
                if (attempt == 1) {
                    onProgress("⚠️ 未返回有效 choices，正在自动重试...")
                    continue
                } else {
                    throw IllegalStateException("自定义 AI 未返回有效 choices 响应: $responseBody")
                }
            }

            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message")
            val content = message?.optString("content") ?: ""

            // 容错保护：针对 DeepSeek 文档指出的 JSON 模式偶发返回空 content 进行重试
            if (content.isBlank()) {
                lastException = IllegalStateException("自定义 AI 返回的内容为空")
                if (attempt == 1) {
                    onProgress("⚠️ 模型返回内容为空 (命中 DeepSeek 偶发空 content 现象)，正在自动重试...")
                    continue
                } else {
                    throw IllegalStateException("自定义 AI 返回的内容为空")
                }
            }

            onProgress("📥 [自定义 AI 原始响应]:\n$content")

            try {
                return@withContext BillPromptHelper.parseJsonToBills(content, currentDateTime, prefs, onProgress)
            } catch (e: Exception) {
                lastException = e
                if (attempt == 1) {
                    onProgress("⚠️ 账单解析异常 (${e.message})，正在自动重试...")
                    continue
                } else {
                    throw e
                }
            }
        }

        throw lastException ?: IllegalStateException("自定义 AI 处理失败")
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
