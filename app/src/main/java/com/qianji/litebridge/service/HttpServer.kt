package com.qianji.litebridge.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qianji.litebridge.model.BillItem
import com.qianji.litebridge.util.PreferencesManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject

class HttpServer(
    private val host: String = "0.0.0.0",
    private val port: Int = 8848,
    private val prefs: PreferencesManager,
    private val queue: RateLimitQueue,
    private val geminiService: GeminiService,
    private val onLog: (String) -> Unit
) : NanoHTTPD(host, port) {

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        onLog("🌐 收到 HTTP 请求: $method $uri (来自: ${session.remoteIpAddress})")

        // 鉴权检查 (若配置了 Bearer Token)
        val expectedToken = prefs.apiToken.trim()
        if (expectedToken.isNotEmpty()) {
            val authHeader = session.headers["authorization"] ?: ""
            val queryToken = session.parms["token"] ?: ""
            val isValid = authHeader == "Bearer $expectedToken" || queryToken == expectedToken
            if (!isValid) {
                onLog("🔒 HTTP 鉴权失败: 缺少或错误的 Token")
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "application/json",
                    """{"code": 401, "message": "Unauthorized: Invalid or missing Bearer token"}"""
                )
            }
        }

        return try {
            when {
                // 1. GET /api/v1/status
                method == Method.GET && (uri == "/api/v1/status" || uri == "/status" || uri == "/") -> {
                    handleStatus()
                }

                // 2. POST /api/v1/bill/raw (结构化账单直通入队)
                method == Method.POST && uri == "/api/v1/bill/raw" -> {
                    handleRawBill(session)
                }

                // 3. POST /api/v1/bill/ai (自然语言文本调用 Gemini 解析后入队)
                method == Method.POST && uri == "/api/v1/bill/ai" -> {
                    handleAiBill(session)
                }

                else -> {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "application/json",
                        """{"code": 404, "message": "Not Found: $method $uri"}"""
                    )
                }
            }
        } catch (e: Exception) {
            onLog("❌ HTTP 处理异常: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"code": 500, "message": "${e.message?.replace("\"", "\\\"")}"}"""
            )
        }
    }

    private fun handleStatus(): Response {
        val json = JSONObject().apply {
            put("code", 200)
            put("status", "running")
            put("port", port)
            put("queue_size", queue.queueSize.value)
            put("version", "1.0.3")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun handleRawBill(session: IHTTPSession): Response {
        val body = readBody(session)
        if (body.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"code": 400, "message": "Empty request body"}"""
            )
        }

        val bills = mutableListOf<BillItem>()
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            val type = object : TypeToken<List<BillItem>>() {}.type
            val parsedList: List<BillItem> = gson.fromJson(trimmed, type)
            bills.addAll(parsedList)
        } else {
            val single = gson.fromJson(trimmed, BillItem::class.java)
            bills.add(single)
        }

        // 过滤非正数账单
        val validBills = bills.filter { it.money > 0.0 }
        if (validBills.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"code": 400, "message": "All bills rejected: money must be greater than 0"}"""
            )
        }

        queue.enqueueAll(validBills)
        onLog("📥 局域网入队 ${validBills.size} 笔账单 (Raw 直通)")

        val responseJson = JSONObject().apply {
            put("code", 200)
            put("message", "Queued")
            put("count", validBills.size)
            put("queue_size", queue.queueSize.value)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
    }

    private fun handleAiBill(session: IHTTPSession): Response {
        val body = readBody(session)
        if (body.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"code": 400, "message": "Empty request body"}"""
            )
        }

        var textPrompt = body.trim()
        try {
            val json = JSONObject(textPrompt)
            if (json.has("text")) {
                textPrompt = json.getString("text")
            }
        } catch (_: Exception) {}

        onLog("🤖 局域网发起 AI 账单解析: \"$textPrompt\"")

        return try {
            val bills = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                com.qianji.litebridge.QianjiApp.instance.aiServiceHub.parseText(textPrompt) { msg -> onLog(msg) }
            }
            queue.enqueueAll(bills)
            onLog("✨ AI 成功提取并入队 ${bills.size} 笔账单")

            val jsonArray = JSONArray()
            bills.forEach { b ->
                jsonArray.put(JSONObject(gson.toJson(b)))
            }

            val responseJson = JSONObject().apply {
                put("code", 200)
                put("message", "Queued")
                put("count", bills.size)
                put("bills", jsonArray)
                put("queue_size", queue.queueSize.value)
            }

            newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
        } catch (e: Exception) {
            onLog("❌ AI 解析错误: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"code": 500, "message": "Gemini inference failed: ${e.message?.replace("\"", "\\\"")}"}"""
            )
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }
}
