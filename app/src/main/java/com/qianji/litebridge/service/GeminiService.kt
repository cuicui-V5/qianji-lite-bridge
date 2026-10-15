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
import com.google.genai.types.Part
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qianji.litebridge.model.BillItem
import com.qianji.litebridge.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
     * 解析录音音频文件中的账单
     */
    suspend fun parseAudio(audioFile: File): List<BillItem> = withContext(Dispatchers.IO) {
        val client = getClient()
        val currentDateTime = getCurrentDateTimeString()
        val promptText = buildPrompt(currentDateTime)
        val modelName = prefs.geminiModel.ifBlank { PreferencesManager.DEFAULT_MODEL }
        val audioBytes = audioFile.readBytes()

        var jsonResult = ""
        var lastError: Exception? = null

        // 策略 1：使用官方核心标准模型接口 client.models.generateContent
        try {
            val partAudio = Part.fromBytes(audioBytes, "audio/mp4")
            val partPrompt = Part.fromText(promptText)
            val content = TypesContent.fromParts(partPrompt, partAudio)

            val response = client.models.generateContent(modelName, listOf(content), null)
            val generatedText = response.text()
            if (!generatedText.isNullOrBlank()) {
                jsonResult = generatedText
            }
        } catch (e: Exception) {
            lastError = e
            e.printStackTrace()
        }

        // 策略 2：若策略 1 未返回或报错，回退到 client.interactions.create (并深度提取 outputText 与 steps)
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

                    // 若 outputText 为空，从 ModelOutputStep 中提取 TextContent
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
                    } else {
                        // 提取可能存在的错误信息
                        interaction.errors().ifPresent { errorList ->
                            if (errorList.isNotEmpty()) {
                                val errStr = errorList.joinToString("; ") { it.toString() }
                                throw IllegalStateException("Gemini Interaction 报错: $errStr")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (lastError == null) lastError = e
                e.printStackTrace()
            }
        }

        if (jsonResult.isBlank()) {
            val errorMsg = lastError?.message ?: "模型响应为空"
            throw IllegalStateException("AI 未返回有效内容: $errorMsg (请确认 API Key 是否正确及网络可用)")
        }

        parseJsonToBills(jsonResult, currentDateTime)
    }

    /**
     * 解析纯自然语言文本中的账单
     */
    suspend fun parseText(textPrompt: String): List<BillItem> = withContext(Dispatchers.IO) {
        val client = getClient()
        val currentDateTime = getCurrentDateTimeString()
        val fullPrompt = "${buildPrompt(currentDateTime)}\n\n用户文本输入内容：$textPrompt"
        val modelName = prefs.geminiModel.ifBlank { PreferencesManager.DEFAULT_MODEL }

        var jsonResult = ""
        var lastError: Exception? = null

        // 策略 1: client.models.generateContent
        try {
            val partPrompt = Part.fromText(fullPrompt)
            val content = TypesContent.fromParts(partPrompt)
            val response = client.models.generateContent(modelName, listOf(content), null)
            val generatedText = response.text()
            if (!generatedText.isNullOrBlank()) {
                jsonResult = generatedText
            }
        } catch (e: Exception) {
            lastError = e
            e.printStackTrace()
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
                e.printStackTrace()
            }
        }

        if (jsonResult.isBlank()) {
            val errorMsg = lastError?.message ?: "模型响应为空"
            throw IllegalStateException("AI 未返回有效内容: $errorMsg (请确认 API Key 是否正确及网络可用)")
        }

        parseJsonToBills(jsonResult, currentDateTime)
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
    fun parseJsonToBills(rawText: String, defaultTime: String): List<BillItem> {
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

        // 提取有效 JSON 块
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

        val type = object : TypeToken<List<BillItem>>() {}.type
        val list: List<BillItem> = gson.fromJson(jsonArray.toString(), type)

        return list.map { item ->
            item.copy(
                time = if (item.time.isNullOrBlank()) defaultTime else item.time,
                bookname = if (item.bookname.isNullOrBlank()) prefs.defaultBook.ifBlank { PreferencesManager.DEFAULT_BOOK } else item.bookname,
                catename = if (item.catename.isNullOrBlank()) "其它" else item.catename,
                // 仅当用户明确配置了账户或原有账户非空时才保留，否则遵从用户指示默认不留账户
                accountname = if (prefs.accounts.isBlank()) item.accountname?.takeIf { it.isNotBlank() } else (item.accountname ?: prefs.accounts.split(",", "\n").firstOrNull())
            )
        }
    }
}
