package com.qianji.litebridge.service

import com.google.genai.Client
import com.google.genai.gaos.models.interactions.AudioContent
import com.google.genai.gaos.models.interactions.AudioContentMimeType
import com.google.genai.gaos.models.interactions.CreateModelInteraction
import com.google.genai.gaos.models.interactions.InteractionsInput
import com.google.genai.gaos.models.interactions.Model
import com.google.genai.gaos.models.interactions.TextContent
import com.google.genai.gaos.models.operations.CreateInteractionRequestBody
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

        val audioBytes = audioFile.readBytes()
        val base64Audio = Base64.getEncoder().encodeToString(audioBytes)

        val textContent = TextContent.builder().text(promptText).build()
        val audioContent = AudioContent.builder()
            .data(base64Audio)
            .mimeType(AudioContentMimeType.AUDIO_M4A)
            .build()

        val modelName = prefs.geminiModel.ifBlank { PreferencesManager.DEFAULT_MODEL }
        val params = CreateModelInteraction.builder()
            .model(Model.of(modelName))
            .input(InteractionsInput.ofContent(listOf(textContent, audioContent)))
            .build()

        val response = client.interactions.create(CreateInteractionRequestBody.of(params))
        val interaction = response.interaction().orElseThrow {
            IllegalStateException("Gemini returned empty interaction")
        }
        val jsonResult = interaction.outputText().orElse("")

        parseJsonToBills(jsonResult, currentDateTime)
    }

    /**
     * 解析纯自然语言文本中的账单
     */
    suspend fun parseText(textPrompt: String): List<BillItem> = withContext(Dispatchers.IO) {
        val client = getClient()
        val currentDateTime = getCurrentDateTimeString()
        val promptText = "${buildPrompt(currentDateTime)}\n\n用户文本输入内容：$textPrompt"

        val textContent = TextContent.builder().text(promptText).build()
        val modelName = prefs.geminiModel.ifBlank { PreferencesManager.DEFAULT_MODEL }
        val params = CreateModelInteraction.builder()
            .model(Model.of(modelName))
            .input(InteractionsInput.ofContent(listOf(textContent)))
            .build()

        val response = client.interactions.create(CreateInteractionRequestBody.of(params))
        val interaction = response.interaction().orElseThrow {
            IllegalStateException("Gemini returned empty interaction")
        }
        val jsonResult = interaction.outputText().orElse("")

        parseJsonToBills(jsonResult, currentDateTime)
    }

    private fun getCurrentDateTimeString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun buildPrompt(currentDateTime: String): String {
        val categories = prefs.categories.ifBlank { PreferencesManager.DEFAULT_CATEGORIES }
        val accounts = prefs.accounts.ifBlank { PreferencesManager.DEFAULT_ACCOUNTS }
        val defaultBook = prefs.defaultBook

        return """
你是一个精准、严谨的专业个人记账助理。
当前手机系统准确时间是: $currentDateTime (格式 yyyy-MM-dd HH:mm:ss)。

用户通过语音或文本向你描述财务收支情况。
请识别其中的全部收支账目（支持单次多笔账单拆分），并严格以 JSON 数组格式输出，不要输出任何 Markdown 标记或多余的解释文字。

规则要求：
1. 可用分类列表（必须严格从以下列表中选择最吻合的一项，支持一级/::/二级格式；若无法匹配则填写"其它"）：
$categories

2. 可用资产账户列表（必须从以下资产中选择，若未提及则默认使用"微信"）：
$accounts

3. 默认账本名称（若用户未指定特定账本，填此账本）：
$defaultBook

4. JSON 数组中每个对象字段规范：
- "type": 整数。0=支出，1=收入，2=转账，3=信用卡还款，5=报销。默认 0。
- "money": 浮点数，账单金额，必须 > 0，精确到小数点后2位。
- "time": 字符串，格式 yyyy-MM-dd HH:mm:ss。若提到了"刚才/今天中午/昨天晚上/前天"等相对时间，请结合当前时间换算；若未提及则直接使用当前时间。
- "remark": 简要备注说明。
- "catename": 分类名称（转账或还款时不需要）。
- "accountname": 资产名称（转账时的转出账户）。
- "accountname2": 转入账户（转账或还款时必填）。
- "bookname": 账本名称。
- "fee": 浮点数（可选，手续费）。
- "coupon": 浮点数（可选，优惠券）。

严格返回纯 JSON 数组，例如：
[
  {
    "type": 0,
    "money": 28.50,
    "time": "$currentDateTime",
    "remark": "星巴克咖啡",
    "catename": "三餐/::/午餐",
    "accountname": "微信",
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

        // 提取 JSON Array
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
            JSONArray(clean)
        }

        val type = object : TypeToken<List<BillItem>>() {}.type
        val list: List<BillItem> = gson.fromJson(jsonArray.toString(), type)

        return list.map { item ->
            // 校验与默认值修正
            item.copy(
                time = if (item.time.isNullOrBlank()) defaultTime else item.time,
                bookname = if (item.bookname.isNullOrBlank()) prefs.defaultBook else item.bookname,
                accountname = if (item.accountname.isNullOrBlank()) "微信" else item.accountname,
                catename = if (item.catename.isNullOrBlank()) "其它" else item.catename
            )
        }
    }
}
