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
        get() = prefs.getString(KEY_CATEGORIES, DEFAULT_CATEGORIES) ?: DEFAULT_CATEGORIES
        set(value) = prefs.edit().putString(KEY_CATEGORIES, value.trim()).apply()

    var accounts: String
        get() = prefs.getString(KEY_ACCOUNTS, DEFAULT_ACCOUNTS) ?: DEFAULT_ACCOUNTS
        set(value) = prefs.edit().putString(KEY_ACCOUNTS, value.trim()).apply()

    var defaultBook: String
        get() = prefs.getString(KEY_DEFAULT_BOOK, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEFAULT_BOOK, value.trim()).apply()

    var apiToken: String
        get() = prefs.getString(KEY_API_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_TOKEN, value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value.trim()).apply()

    companion object {
        private const val PREF_NAME = "qianji_lite_bridge_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_DEFAULT_BOOK = "default_book"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_GEMINI_MODEL = "gemini_model"

        const val DEFAULT_CATEGORIES = "三餐/::/早餐,三餐/::/午餐,三餐/::/晚餐,交通,日用,零食,其它"
        const val DEFAULT_ACCOUNTS = "微信,支付宝,银行卡"
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }
}
