package com.qianji.litebridge.util

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()

    var categories: String
        get() {
            val v = prefs.getString(KEY_CATEGORIES, null)
            return if (!v.isNullOrBlank()) v else DEFAULT_CATEGORIES
        }
        set(value) = prefs.edit().putString(KEY_CATEGORIES, value.trim()).apply()

    var accounts: String
        get() = prefs.getString(KEY_ACCOUNTS, DEFAULT_ACCOUNTS) ?: DEFAULT_ACCOUNTS
        set(value) = prefs.edit().putString(KEY_ACCOUNTS, value.trim()).apply()

    var defaultBook: String
        get() {
            val v = prefs.getString(KEY_DEFAULT_BOOK, null)
            return if (!v.isNullOrBlank()) v else DEFAULT_BOOK
        }
        set(value) = prefs.edit().putString(KEY_DEFAULT_BOOK, value.trim()).apply()

    var apiToken: String
        get() = prefs.getString(KEY_API_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_TOKEN, value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value.trim()).apply()

    var thinkingBudget: Int
        get() = prefs.getInt(KEY_THINKING_BUDGET, DEFAULT_THINKING_BUDGET)
        set(value) = prefs.edit().putInt(KEY_THINKING_BUDGET, value).apply()

    var autoReturn: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RETURN, DEFAULT_AUTO_RETURN)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RETURN, value).apply()

    var floatingCapsuleEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_CAPSULE, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_CAPSULE, value).apply()

    var floatingX: Int
        get() = prefs.getInt(KEY_FLOATING_X, -1)
        set(value) = prefs.edit().putInt(KEY_FLOATING_X, value).apply()

    var floatingY: Int
        get() = prefs.getInt(KEY_FLOATING_Y, -1)
        set(value) = prefs.edit().putInt(KEY_FLOATING_Y, value).apply()

    init {
        // 若之前保存了旧版默认资产账户，按最新需求重置为空（默认不留账户）
        val savedAccounts = prefs.getString(KEY_ACCOUNTS, null)
        if (savedAccounts == "微信,支付宝,银行卡") {
            accounts = ""
        }
        // 若之前保存的分类是旧版逗号列表，更新为用户指定的分类列表
        val savedCategories = prefs.getString(KEY_CATEGORIES, null)
        if (savedCategories == "三餐/::/早餐,三餐/::/午餐,三餐/::/晚餐,交通,日用,零食,其它") {
            categories = DEFAULT_CATEGORIES
        }
    }

    companion object {
        private const val PREF_NAME = "qianji_lite_bridge_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_DEFAULT_BOOK = "default_book"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_THINKING_BUDGET = "thinking_budget"
        private const val KEY_AUTO_RETURN = "auto_return"
        private const val KEY_FLOATING_CAPSULE = "floating_capsule_enabled"
        private const val KEY_FLOATING_X = "floating_capsule_x"
        private const val KEY_FLOATING_Y = "floating_capsule_y"

        const val DEFAULT_CATEGORIES = """三餐
交通
零食
衣服
日用品
旅行
学习
住房
美妆
话费网费
医疗
娱乐
汽车/加油
请客送礼
电器数码
水电煤
其它
数字订阅"""

        const val DEFAULT_ACCOUNTS = ""
        const val DEFAULT_BOOK = "日常账本"
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_THINKING_BUDGET = 0 // 0 = 关闭深度思考，极速 1~2 秒响应
        const val DEFAULT_AUTO_RETURN = true // 记账后自动跳回本应用
    }
}
