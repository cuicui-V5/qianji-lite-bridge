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

    /**
     * 构建或获取官方 Client
     */
    private fun getClient(): Client {
        val apiKey = prefs.geminiApiKey.trim()
        val builder = Client.builder()
        if (apiKey.isNotEmpty()) {
            builder.apiKey(apiKey)
        }
        return builder.build()
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
        val promptText = buildPrompt(currentDateTime)
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

            if (thinkingBudget >= 0) {
                configBuilder.thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(thinkingBudget)
                        .build()
                )
            }

            val response = client.models.generateContent(modelName, listOf(content), configBuilder.build())
            val text = response.text()
            if (!text.isNullOrBlank()) {
                jsonResult = text
            }
        } catch (e: Exception) {
            lastError = e
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
        val fullPrompt = "${buildPrompt(currentDateTime)}\n\n用户文本输入内容：$textPrompt"
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

            if (thinkingBudget >= 0) {
                configBuilder.thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(thinkingBudget)
                        .build()
                )
            }

            val response = client.models.generateContent(modelName, listOf(content), configBuilder.build())
            val text = response.text()
            if (!text.isNullOrBlank()) {
                jsonResult = text
            }
        } catch (e: Exception) {
            lastError = e
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

    private fun buildPrompt(currentDateTime: String): String {
        val categories = prefs.categories.ifBlank { PreferencesManager.DEFAULT_CATEGORIES }
        val accounts = prefs.accounts.trim()
        val defaultBook = prefs.defaultBook.ifBlank { PreferencesManager.DEFAULT_BOOK }

        val accountsRule = if (accounts.isNotEmpty()) {
            "2. 可用资产账户列表（必须从以下资产中选择）：\n$accounts"
        } else {
            "2. 资产账户规范：用户未配置固定资产账户，对于普通支出/收入，'accountname' 和 'accountname2' 字段请直接设为 null；只有发生转账/还款或用户明确报出账户时才填入提及的账户名。"
        }

        val bookRule = if (defaultBook.isNotEmpty()) {
            "3. 默认账本名称为 \"$defaultBook\"。如果用户未指定特定账本，\"bookname\" 请直接填写 \"$defaultBook\"。"
        } else {
            "3. 默认账本：用户未指定特定账本时，\"bookname\" 填空字符串 \"\"。"
        }

        return """
你是一个精准、严谨的专业个人记账助理。
当前手机系统准确时间是: $currentDateTime (格式 yyyy-MM-dd HH:mm:ss)。

用户通过语音或文本向你描述财务收支情况。
请识别其中的全部收支账目（支持单次多笔账单拆分），并严格以 JSON 数组格式输出，不要输出任何 Markdown 解释文字或多余问候。

规则要求：
1. 可用分类列表（必须严格从以下列表中选择最吻合的一项，若无法精确匹配则填写"其它"）：
$categories

$accountsRule

$bookRule

4. JSON 数组中每个对象字段规范：
- "type": 整数。0=支出，1=收入，2=转账，3=信用卡还款，5=报销。默认 0。
- "money": 浮点数，账单金额，必须 > 0，精确到小数点后2位（例如 25.50）。
  * 核心注意：如果用户未明确说出具体的消费金额（例如仅说'中午吃了饭'或'打车回家'），禁止猜测金额为0！未说明金额的收支请直接丢弃，若整段话均无具体金额，请直接返回空数组 []！
- "time": 字符串，格式 yyyy-MM-dd HH:mm:ss。若提到了"刚才/今天中午/昨天晚上/前天"等相对时间，请结合当前系统时间推算绝对时间；若未提及则直接使用当前时间。
- "remark": 简要备注说明。
- "catename": 分类名称（转账或还款时为 null）。
- "accountname": 资产名称（无指定或普通支出时设为 null，转账时为转出账户）。
- "accountname2": 转入账户（转账或还款时必填，其余设为 null）。
- "bookname": 账本名称。
- "fee": 浮点数（可选，转账手续费）。
- "coupon": 浮点数（可选，优惠券金额）。

严格返回纯 JSON 数组，例如：
[
  {
    "type": 0,
    "money": 28.50,
    "time": "$currentDateTime",
    "remark": "星巴克咖啡",
    "catename": "三餐",
    "accountname": null,
    "bookname": "$defaultBook"
  }
]
""".trimIndent()
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
