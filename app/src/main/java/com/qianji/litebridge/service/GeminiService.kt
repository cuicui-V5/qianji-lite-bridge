package com.qianji.litebridge.service

import com.google.genai.Client
import com.google.genai.gaos.models.interactions.AudioContent
import com.google.genai.gaos.models.interactions.AudioContentMimeType
import com.google.genai.gaos.models.interactions.CreateModelInteraction
import com.google.genai.gaos.models.interactions.InteractionsInput
import com.google.genai.gaos.models.interactions.Model
import com.google.genai.gaos.models.interactions.ModelOutputStep
import com.google.genai.gaos.models.interactions.TextContent
import com.google.genai.gaos.models.operations.CreateInteractionRequestBody
import com.google.genai.types.Content as TypesContent
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qianji.litebridge.model.BillItem
import com.qianji.litebridge.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

class GeminiService(
    private val prefs: PreferencesManager
) {
    private val gson = Gson()

    @Volatile
    private var cachedClient: Client? = null
    @Volatile
    private var cachedApiKey: String? = null

    /**
     * 构建或获取官方 Client (复用底层连接池与 TLS 会话，避免重复握手)
     */
    @Synchronized
    private fun getClient(): Client {
        val apiKey = prefs.geminiApiKey.trim()
        if (cachedClient != null && cachedApiKey == apiKey) {
            return cachedClient!!
        }
        val builder = Client.builder()
        if (apiKey.isNotEmpty()) {
            builder.apiKey(apiKey)
        }
        val newClient = builder.build()
        cachedClient = newClient
        cachedApiKey = apiKey
        return newClient
    }

    /**
     * 调用 Google 官方专用语音转录大模型 gemini-3.5-transcribe
     * 将录音音频极速转写为规范化文本
     */
    suspend fun transcribeAudio(
        audioFile: File,
        onProgress: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val apiKey = prefs.geminiApiKey.trim()
        if (apiKey.isEmpty()) {
            throw IllegalStateException("未配置 Gemini API Key，请在设置中配置！")
        }

        val audioBytes = audioFile.readBytes()
        val audioKb = String.format(Locale.US, "%.1f", audioBytes.size / 1024.0)
        val requestTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        onProgress("🛫 [Gemini ASR 请求] 时间戳: $requestTime | 模型: gemini-3.5-transcribe")
        onProgress("📦 [载荷数据] 录音音频: ${audioKb} KB (${audioFile.name})")

        val startTime = System.currentTimeMillis()
        val base64Audio = Base64.getEncoder().encodeToString(audioBytes)

        val payloadObj = JSONObject().apply {
            put("model", "gemini-3.5-transcribe")
            val inputArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "audio")
                    put("data", base64Audio)
                    put("mime_type", "audio/mp4")
                })
            }
            put("input", inputArray)

            val genConfig = JSONObject().apply {
                val transConfig = JSONObject().apply {
                    put("mode", "smart") // 智能转写：去除填充词、标点规整、逆文本归一化
                    put("language_codes", JSONArray().put("zh-CN"))
                }
                put("transcription_config", transConfig)
            }
            put("generation_config", genConfig)
        }

        val payloadString = payloadObj.toString()
        val url = URL("https://generativelanguage.googleapis.com/v1beta/interactions?key=${URLEncoder.encode(apiKey, "UTF-8")}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Connection", "Keep-Alive")
        }

        val responseCode = try {
            conn.outputStream.use { os ->
                os.write(payloadString.toByteArray(Charsets.UTF_8))
            }
            conn.responseCode
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            throw IllegalStateException("连接 Gemini ASR 失败 (${elapsed}ms): ${e.message}", e)
        }

        val elapsed = System.currentTimeMillis() - startTime
        val responseBody = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            val errText = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $responseCode"
            throw IllegalStateException("Gemini ASR 请求失败 (HTTP $responseCode, 耗时 ${elapsed}ms): $errText")
        }

        onProgress("⏱️ [Gemini ASR 耗时] ${elapsed}ms")

        val rootObj = JSONObject(responseBody)
        var transcribedText = ""

        if (rootObj.has("output_text")) {
            transcribedText = rootObj.optString("output_text")
        }

        if (transcribedText.isBlank() && rootObj.has("steps")) {
            val steps = rootObj.optJSONArray("steps")
            if (steps != null) {
                val sb = StringBuilder()
                for (i in 0 until steps.length()) {
                    val step = steps.optJSONObject(i) ?: continue
                    if (step.optString("type") == "model_output") {
                        val contentArray = step.optJSONArray("content") ?: continue
                        for (j in 0 until contentArray.length()) {
                            val contentObj = contentArray.optJSONObject(j) ?: continue
                            if (contentObj.optString("type") == "text") {
                                sb.append(contentObj.optString("text"))
                            }
                        }
                    }
                }
                transcribedText = sb.toString().trim()
            }
        }

        if (transcribedText.isBlank()) {
            throw IllegalStateException("Gemini ASR 未返回转写文本内容")
        }

        onProgress("📝 [Gemini ASR 转录结果]: \"$transcribedText\"")
        transcribedText
    }

    /**
     * 高速两阶段模式：
     * 1. gemini-3.5-transcribe: 语音 -> 规范文本
     * 2. gemini-3.5-flash-lite: 文本 -> 结构化账单
     */
    suspend fun parseAudioWithAsr(
        audioFile: File,
        onProgress: (String) -> Unit = {}
    ): List<BillItem> {
        val transcribedText = transcribeAudio(audioFile, onProgress)
        return parseText(transcribedText, onProgress)
    }

    /**
     * 原生多模态解析（直接将录音音频文件作为多模态载荷上传给主模型）
     */
    suspend fun parseAudio(
        audioFile: File,
        onProgress: (String) -> Unit = {}
    ): List<BillItem> = withContext(Dispatchers.IO) {
        val client = getClient()
        val currentDateTime = getCurrentDateTimeString()
        val systemPrompt = BillPromptHelper.buildSystemPrompt(prefs)
        val userContent = BillPromptHelper.buildUserContent("（请根据所上传录音音频识别账单）", currentDateTime)
        val promptText = "$systemPrompt\n\n$userContent"
        val modelName = prefs.geminiModel.ifBlank { PreferencesManager.DEFAULT_MODEL }
        val thinkingBudget = prefs.thinkingBudget
        val audioBytes = audioFile.readBytes()

        val requestTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val audioKb = String.format(Locale.US, "%.1f", audioBytes.size / 1024.0)
        onProgress("🛫 [AI 请求] 时间戳: $requestTime | 模型: $modelName | 思考预算: $thinkingBudget")
        onProgress("📦 [载荷数据] 录音音频: ${audioKb} KB (${audioFile.name}) [已过滤Base64数据]")
        val startTime = System.currentTimeMillis()

        var jsonResult = ""
        var lastError: Exception? = null

        // 策略 1：使用标准 client.models.generateContent（通过 thinkingBudget=0 彻底关闭 CoT 思考延迟，实现秒级响应）
        try {
            val partAudio = Part.fromBytes(audioBytes, "audio/mp4")
            val partPrompt = Part.fromText(promptText)
            val content = TypesContent.fromParts(partPrompt, partAudio)

            val configBuilder = GenerateContentConfig.builder()
                .temperature(0.1f)
                .responseMimeType("application/json")

            if (thinkingBudget > 0) {
                try {
                    configBuilder.thinkingConfig(
                        ThinkingConfig.builder()
                            .thinkingBudget(thinkingBudget)
                            .build()
                    )
                } catch (_: Exception) {}
            }

            val response = client.models.generateContent(modelName, listOf(content), configBuilder.build())
            val text = response.text()
            if (!text.isNullOrBlank()) {
                jsonResult = text
            }
        } catch (e: Exception) {
            lastError = e
            onProgress("⚠️ [AI 解析策略 1 异常] ${e.message}，正在尝试备用策略...")
            try {
                val partAudio = Part.fromBytes(audioBytes, "audio/mp4")
                val partPrompt = Part.fromText(promptText)
                val content = TypesContent.fromParts(partPrompt, partAudio)
                val fallbackResponse = client.models.generateContent(modelName, listOf(content), null)
                val text = fallbackResponse.text()
                if (!text.isNullOrBlank()) {
                    jsonResult = text
                }
            } catch (ex: Exception) {
                lastError = ex
            }
        }

        // 策略 2：回退到 client.interactions
        if (jsonResult.isBlank()) {
            try {
                val base64Audio = Base64.getEncoder().encodeToString(audioBytes)
                val textContent = TextContent.builder().text(promptText).build()
                val audioContent = AudioContent.builder()
                    .data(base64Audio)
                    .mimeType(AudioContentMimeType.AUDIO_M4A)
                    .build()

                val params = CreateModelInteraction.builder()
                    .model(Model.of(modelName))
                    .input(InteractionsInput.ofContent(listOf(textContent, audioContent)))
                    .build()

                val response = client.interactions.create(CreateInteractionRequestBody.of(params))
                val interaction = response.interaction().orElse(null)
                if (interaction != null) {
                    var extracted = interaction.outputText().orElse("")
                    if (extracted.isBlank()) {
                        interaction.steps().ifPresent { stepList ->
                            val sb = StringBuilder()
                            for (step in stepList) {
                                if (step is ModelOutputStep) {
                                    step.content().ifPresent { contents ->
                                        for (c in contents) {
                                            if (c is TextContent) {
                                                c.text().ifPresent { t -> sb.append(t) }
                                            }
                                        }
                                    }
                                }
                            }
                            extracted = sb.toString()
                        }
                    }
                    if (extracted.isNotBlank()) {
                        jsonResult = extracted
                    }
                }
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }

        val elapsed = System.currentTimeMillis() - startTime

        if (jsonResult.isBlank()) {
            val errorMsg = lastError?.message ?: "模型响应为空"
            throw IllegalStateException("AI 未返回有效内容 (耗时 ${elapsed}ms): $errorMsg")
        }

        onProgress("⏱️ [AI 处理耗时] ${elapsed}ms")
        onProgress("📥 [AI 原始响应]:\n$jsonResult")

        parseJsonToBills(jsonResult, currentDateTime, onProgress)
    }

    /**
     * 解析纯自然语言文本中的账单
     */
    suspend fun parseText(
        textPrompt: String,
        onProgress: (String) -> Unit = {}
    ): List<BillItem> = withContext(Dispatchers.IO) {
        val client = getClient()
        val currentDateTime = getCurrentDateTimeString()
        val systemPrompt = BillPromptHelper.buildSystemPrompt(prefs)
        val userContent = BillPromptHelper.buildUserContent(textPrompt, currentDateTime)
        val fullPrompt = "$systemPrompt\n\n$userContent"
        val modelName = prefs.geminiModel.ifBlank { PreferencesManager.DEFAULT_MODEL }
        val thinkingBudget = prefs.thinkingBudget

        val requestTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        onProgress("🛫 [AI 请求] 时间戳: $requestTime | 模型: $modelName | 思考预算: $thinkingBudget")
        onProgress("📦 [载荷数据] 文本输入: \"$textPrompt\"")

        val startTime = System.currentTimeMillis()
        var jsonResult = ""
        var lastError: Exception? = null

        // 策略 1: client.models.generateContent
        try {
            val partPrompt = Part.fromText(fullPrompt)
            val content = TypesContent.fromParts(partPrompt)

            val configBuilder = GenerateContentConfig.builder()
                .temperature(0.1f)
                .responseMimeType("application/json")

            if (thinkingBudget > 0) {
                try {
                    configBuilder.thinkingConfig(
                        ThinkingConfig.builder()
                            .thinkingBudget(thinkingBudget)
                            .build()
                    )
                } catch (_: Exception) {}
            }

            val response = client.models.generateContent(modelName, listOf(content), configBuilder.build())
            val text = response.text()
            if (!text.isNullOrBlank()) {
                jsonResult = text
            }
        } catch (e: Exception) {
            lastError = e
            onProgress("⚠️ [AI 解析策略 1 异常] ${e.message}，正在尝试备用策略...")
            try {
                val partPrompt = Part.fromText(fullPrompt)
                val content = TypesContent.fromParts(partPrompt)
                val fallbackResponse = client.models.generateContent(modelName, listOf(content), null)
                val text = fallbackResponse.text()
                if (!text.isNullOrBlank()) {
                    jsonResult = text
                }
            } catch (ex: Exception) {
                lastError = ex
            }
        }

        // 策略 2: client.interactions.create
        if (jsonResult.isBlank()) {
            try {
                val textContent = TextContent.builder().text(fullPrompt).build()
                val params = CreateModelInteraction.builder()
                    .model(Model.of(modelName))
                    .input(InteractionsInput.ofContent(listOf(textContent)))
                    .build()

                val response = client.interactions.create(CreateInteractionRequestBody.of(params))
                val interaction = response.interaction().orElse(null)
                if (interaction != null) {
                    var extracted = interaction.outputText().orElse("")
                    if (extracted.isBlank()) {
                        interaction.steps().ifPresent { stepList ->
                            val sb = StringBuilder()
                            for (step in stepList) {
                                if (step is ModelOutputStep) {
                                    step.content().ifPresent { contents ->
                                        for (c in contents) {
                                            if (c is TextContent) {
                                                c.text().ifPresent { t -> sb.append(t) }
                                            }
                                        }
                                    }
                                }
                            }
                            extracted = sb.toString()
                        }
                    }
                    if (extracted.isNotBlank()) {
                        jsonResult = extracted
                    }
                }
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }

        val elapsed = System.currentTimeMillis() - startTime

        if (jsonResult.isBlank()) {
            val errorMsg = lastError?.message ?: "模型响应为空"
            throw IllegalStateException("AI 未返回有效内容 (耗时 ${elapsed}ms): $errorMsg")
        }

        onProgress("⏱️ [AI 处理耗时] ${elapsed}ms")
        onProgress("📥 [AI 原始响应]:\n$jsonResult")

        parseJsonToBills(jsonResult, currentDateTime, onProgress)
    }

    private fun getCurrentDateTimeString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    /**
     * 将 Gemini 返回的文本解析清洗为 BillItem 列表
     */
    fun parseJsonToBills(
        rawText: String,
        defaultTime: String,
        onProgress: (String) -> Unit = {}
    ): List<BillItem> {
        var clean = rawText.trim()
        if (clean.startsWith("```json")) {
            clean = clean.removePrefix("```json").trim()
        } else if (clean.startsWith("```")) {
            clean = clean.removePrefix("```").trim()
        }
        if (clean.endsWith("```")) {
            clean = clean.removeSuffix("```").trim()
        }

        if (clean.isBlank()) {
            throw IllegalStateException("模型返回的文本为空")
        }

        val startBracket = clean.indexOf('[')
        val startBrace = clean.indexOf('{')
        val jsonArray: JSONArray = if (startBracket != -1 && (startBrace == -1 || startBracket < startBrace)) {
            val endBracket = clean.lastIndexOf(']')
            if (endBracket != -1) {
                JSONArray(clean.substring(startBracket, endBracket + 1))
            } else {
                JSONArray(clean)
            }
        } else if (startBrace != -1) {
            val endBrace = clean.lastIndexOf('}')
            if (endBrace != -1) {
                val obj = JSONObject(clean.substring(startBrace, endBrace + 1))
                JSONArray().put(obj)
            } else {
                JSONArray(clean)
            }
        } else {
            throw IllegalStateException("未从 AI 回复中匹配到有效的 JSON 结构: $clean")
        }

        val extractedJsonStr = jsonArray.toString(2)
        onProgress("📄 [提取的 JSON 结构]:\n$extractedJsonStr")

        val type = object : TypeToken<List<BillItem>>() {}.type
        val list: List<BillItem> = gson.fromJson(jsonArray.toString(), type)

        // 严格过滤金额有效性：钱迹接口强制要求 money > 0
        val validList = list.filter { it.money > 0.0 }
        if (validList.isEmpty()) {
            throw IllegalStateException("未检测到有效消费金额（钱迹要求金额必须大于0），请在语音中说明花费金额，例如：'吃午餐花了25元'")
        }

        val mappedList = validList.map { item ->
            item.copy(
                time = if (item.time.isNullOrBlank()) defaultTime else item.time,
                bookname = if (item.bookname.isNullOrBlank()) prefs.defaultBook.ifBlank { PreferencesManager.DEFAULT_BOOK } else item.bookname,
                catename = if (item.catename.isNullOrBlank()) "其它" else item.catename,
                accountname = if (prefs.accounts.isBlank()) item.accountname?.takeIf { it.isNotBlank() } else (item.accountname ?: prefs.accounts.split(",", "\n").firstOrNull())
            )
        }

        val billSummaries = mappedList.joinToString("\n") { "  • ${it.summary()}" }
        onProgress("✨ [结构化账单已生成 (${mappedList.size}笔)]:\n$billSummaries")

        return mappedList
    }
}
