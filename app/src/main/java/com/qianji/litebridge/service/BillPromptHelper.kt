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
用户通过语音或文本向你描述财务收支情况。
请识别其中的全部账目（支持单次多笔账单拆分），并严格以 JSON 数组格式输出，不要输出任何 Markdown 解释文字或多余问候。

规则要求：
1. 可用分类列表（必须严格从以下列表中选择最吻合的一项，若无法精确匹配则填写"其它"）：
$categories

$accountsRule

$bookRule

4. JSON 数组中每个对象字段规范：
- "type": 整数。0=支出，1=收入，2=转账，3=信用卡还款，5=报销。默认 0。
- "money": 浮点数，账单金额，必须 > 0，精确到小数点后2位（例如 25.50，2000.00）。
  * 金额解析规则：准确识别阿拉伯数字（如 2000元、50块）或中文数词（如 两千元、五千块、三块五、两万），如实转换为浮点数。
  * 金额大小无限制：无论金额大小（小到几元、大到数千或上万元，如买手机、大额日常消费等），只要明确说明了花费金额，均必须如实记录！
  * 核心注意：如果用户未明确说出具体的金额（例如仅说'中午吃了饭'或'打车回家'），禁止猜测金额为0！未说明金额的账目请直接丢弃，若整段话均无具体金额，请直接返回空数组 []！
- "time": 字符串，格式 yyyy-MM-dd HH:mm:ss。若输入中提到了"刚才/今天中午/昨天/昨天晚上/前天"等相对时间，请结合随用户输入一并给出的当前手机系统时间推算绝对时间；若未提及则直接使用该系统时间。
- "remark": 简要备注说明（例如："星巴克咖啡"、"买手机"、"洗澡"）。
- "catename": 分类名称（必须严格从可用分类列表中选择最吻合的一项，无法精确匹配时填"其它"）。
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
    "time": "2026-10-16 12:30:00",
    "remark": "星巴克咖啡",
    "catename": "三餐",
    "accountname": null,
    "bookname": "$defaultBook"
  },
  {
    "type": 0,
    "money": 2000.00,
    "time": "2026-10-15 15:00:00",
    "remark": "买手机",
    "catename": "电器数码",
    "accountname": null,
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
