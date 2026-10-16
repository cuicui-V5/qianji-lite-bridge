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

    fun buildPrompt(prefs: PreferencesManager, currentDateTime: String): String {
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
