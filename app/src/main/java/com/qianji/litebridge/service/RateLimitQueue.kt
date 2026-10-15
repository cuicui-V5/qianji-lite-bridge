package com.qianji.litebridge.service

import android.content.Context
import com.qianji.litebridge.model.BillItem
import com.qianji.litebridge.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class RateLimitQueue(
    private val context: Context,
    private val prefs: PreferencesManager,
    private val onLog: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val channel = Channel<BillItem>(Channel.UNLIMITED)

    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    private val atomicCount = AtomicInteger(0)

    init {
        startConsumer()
    }

    /**
     * 将账单加入限流队列
     */
    fun enqueue(bill: BillItem) {
        // 严格拦截过滤非正数账单
        if (bill.money <= 0.0) {
            onLog("⚠️ 丢弃未指明金额的非法账单: ${bill.remark ?: "未知"}")
            return
        }

        val count = atomicCount.incrementAndGet()
        _queueSize.value = count
        scope.launch {
            channel.send(bill)
        }
    }

    /**
     * 批量加入队列
     */
    fun enqueueAll(bills: List<BillItem>) {
        bills.filter { it.money > 0.0 }.forEach { enqueue(it) }
    }

    private fun startConsumer() {
        scope.launch {
            while (isActive) {
                val bill = channel.receive()
                try {
                    val uri = QianjiLauncher.buildUri(bill)
                    onLog("🔗 [钱迹 URL Scheme]:\n$uri")
                    val shouldAutoReturn = bill.autoReturn && prefs.autoReturn
                    val success = QianjiLauncher.launch(context, bill, autoReturn = shouldAutoReturn)
                    if (success) {
                        onLog("✅ 已成功唤起钱迹保存: ${bill.summary()}${if (shouldAutoReturn) " (随后自动切回本网关)" else " (保留在当前应用)"}")
                    } else {
                        onLog("❌ 唤起钱迹失败 (未找到钱迹App或权限受限): ${bill.summary()}")
                    }
                } catch (e: Exception) {
                    onLog("❌ 唤起异常: ${e.message}")
                    e.printStackTrace()
                } finally {
                    val remaining = atomicCount.decrementAndGet().coerceAtLeast(0)
                    _queueSize.value = remaining
                }

                // 钱迹接口 3s 防刷保护，严格间隔 3200ms
                delay(3200)
            }
        }
    }
}
