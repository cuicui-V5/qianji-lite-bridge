package com.qianji.litebridge.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qianji.litebridge.model.BillItem
import com.qianji.litebridge.util.PreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BillPromptHelper {
    private val gson = Gson()

    fun getCurrentDateTimeString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    /**
     * 系统指令 System Prompt：保持完全静态！
     * 不包含动态当前时间，确保 DeepSeek Context Caching 和 Gemini Prefix Caching 达到 100% 命中率
     */
    fun buildSystemPrompt(prefs: PreferencesManager): String {
        val rawCategories = prefs.categories.ifBlank { PreferencesManager.DEFAULT_CATEGORIES }
        val categoryList = rawCategories.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
        val categoriesCompact = categoryList.joinToString(", ")
        val defaultBook = prefs.defaultBook.ifBlank { PreferencesManager.DEFAULT_BOOK }

        return """
你是一个精准、高效的个人记账助理，专为“钱迹”提取单账本支出账单。
用户通过语音或文本描述消费，请识别全部支出项目（支持一句话多笔消费拆分），以纯 JSON 数组格式输出，严禁输出任何 Markdown 标记或多余文字。

【可用分类列表】（必须严格从以下列表中选择最吻合的一项，若无法精准匹配则填"其它"）：
$categoriesCompact

【账单字段规范】（输出为 JSON 数组，每个对象包含以下 6 个字段）：
- "type": 固定为 0（表示支出）。
- "money": 浮点数，消费金额，必须 > 0，保留最多两位小数。
  * 准确解析阿拉伯数字或中文数词（如 2000元、两千元、五千块、三块五、两万等），金额大小无限制。
  * 若用户未说明具体花费金额（如仅说"中午吃了饭"），禁止猜测！直接丢弃；若整段话均无花费金额，直接输出空数组 []。忽略"大概/好像"等模糊词，以报出的数值为准。
- "time": 字符串，格式 yyyy-MM-dd HH:mm:ss。
  * 结合用户输入的系统时间推算日期（今天/昨天/前天等）。若提及具体时段，对齐到典型时刻（早晨 08:30、中午 12:30、下午 15:30、晚上 19:30、夜宵 22:30）；未提及则直接使用系统时间。
- "remark": 消费对象或事件名称（简短纯净名词短语，如"星巴克咖啡"、"买手机"、"洗澡"、"麦当劳午餐"，过滤掉"买了/花了/吃了"等冗余动词）。
- "catename": 分类名称（必须从可用分类列表中选择）。
- "bookname": 账本名称，固定填写 "$defaultBook"。

【输出示例】：
单笔消费示例：
[
  {
    "type": 0,
    "money": 28.50,
    "time": "2026-10-16 12:30:00",
    "remark": "星巴克咖啡",
    "catename": "三餐",
    "bookname": "$defaultBook"
  }
]

多笔消费或大额消费示例：
[
  {
    "type": 0,
    "money": 2000.00,
    "time": "2026-10-15 15:30:00",
    "remark": "买手机",
    "catename": "电器数码",
    "bookname": "$defaultBook"
  },
  {
    "type": 0,
    "money": 35.00,
    "time": "2026-10-15 19:30:00",
    "remark": "晚饭快餐",
    "catename": "三餐",
    "bookname": "$defaultBook"
  }
]
""".trimIndent()
    }

    /**
     * 构造用户输入载荷：将动态当前时间放置在用户输入内容之前，方便系统 Prompt 上下文缓存
     */
    fun buildUserContent(userInput: String, currentDateTime: String): String {
        return """
当前手机系统准确时间: $currentDateTime (格式 yyyy-MM-dd HH:mm:ss)
用户记账输入内容: "$userInput"
""".trimIndent()
    }

    fun buildPrompt(prefs: PreferencesManager, currentDateTime: String): String {
        return buildSystemPrompt(prefs) + "\n\n当前手机系统准确时间: $currentDateTime"
    }

    fun parseJsonToBills(
        rawText: String,
        defaultTime: String,
        prefs: PreferencesManager,
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

        val userCategories = prefs.categories.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
        val userAccounts = prefs.accounts.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
        val defaultBookName = prefs.defaultBook.ifBlank { PreferencesManager.DEFAULT_BOOK }

        val mappedList = validList.map { item ->
            // 1. 分类有效性校验与纠偏映射
            val rawCate = item.catename?.trim()
            val validatedCate = if (userCategories.isEmpty()) {
                rawCate?.takeIf { it.isNotBlank() } ?: "其它"
            } else if (rawCate.isNullOrBlank()) {
                if (userCategories.contains("其它")) "其它" else userCategories.first()
            } else {
                val exact = userCategories.firstOrNull { it.equals(rawCate, ignoreCase = true) }
                if (exact != null) {
                    exact
                } else {
                    val partial = userCategories.firstOrNull {
                        rawCate.contains(it, ignoreCase = true) || it.contains(rawCate, ignoreCase = true)
                    }
                    if (partial != null) {
                        onProgress("ℹ️ 分类「$rawCate」自动模糊匹配为设置中的「$partial」")
                        partial
                    } else {
                        val fallback = if (userCategories.contains("其它")) "其它" else userCategories.first()
                        onProgress("⚠️ 分类「$rawCate」未在设置列表中，已自动纠错为「$fallback」")
                        fallback
                    }
                }
            }

            // 2. 资产账户有效性校验
            val validatedAccount = if (userAccounts.isEmpty()) {
                item.accountname?.takeIf { it.isNotBlank() }
            } else if (item.accountname.isNullOrBlank()) {
                null
            } else {
                val rawAcc = item.accountname.trim()
                val exact = userAccounts.firstOrNull { it.equals(rawAcc, ignoreCase = true) }
                if (exact != null) {
                    exact
                } else {
                    val partial = userAccounts.firstOrNull {
                        rawAcc.contains(it, ignoreCase = true) || it.contains(rawAcc, ignoreCase = true)
                    }
                    if (partial != null) {
                        partial
                    } else {
                        onProgress("⚠️ 资产账户「$rawAcc」不在设置列表中，已重置为空")
                        null
                    }
                }
            }

            // 3. 账单类型安全约束 (0=支出, 1=收入, 2=转账, 3=还款, 5=报销)
            val validatedType = if (item.type in listOf(0, 1, 2, 3, 5)) item.type else 0

            item.copy(
                type = validatedType,
                time = if (item.time.isNullOrBlank()) defaultTime else item.time,
                bookname = if (item.bookname.isNullOrBlank()) defaultBookName else item.bookname,
                catename = validatedCate,
                accountname = validatedAccount
            )
        }

        val billSummaries = mappedList.joinToString("\n") { "  • ${it.summary()}" }
        onProgress("✨ [结构化账单已生成 (${mappedList.size}笔)]:\n$billSummaries")

        return mappedList
    }
}
