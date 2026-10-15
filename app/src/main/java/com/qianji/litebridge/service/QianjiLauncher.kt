package com.qianji.litebridge.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.qianji.litebridge.model.BillItem
import java.util.Locale

object QianjiLauncher {

    const val QIANJI_PACKAGE = "com.mutangtech.qianji"

    /**
     * 根据钱迹接口文档规范构建 URL Scheme:
     * qianji://publicapi/addbill?&type=0&money=26.5...
     */
    fun buildUri(bill: BillItem): Uri {
        val params = mutableListOf<Pair<String, String>>()

        // type: 0支出，1收入，2转账，3信用卡还款，5报销
        params.add("type" to bill.type.toString())

        // money: 必须大于 0，格式化为最多2位小数
        val moneyStr = if (bill.money % 1.0 == 0.0) {
            bill.money.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", bill.money).trimEnd('0').trimEnd('.')
        }
        params.add("money" to moneyStr)

        // time: yyyy-MM-dd HH:mm:ss
        bill.time?.takeIf { it.isNotBlank() }?.let {
            params.add("time" to it)
        }

        // remark: 备注信息
        bill.remark?.takeIf { it.isNotBlank() }?.let {
            params.add("remark" to it)
        }

        // catename / catechoose (转账与还款不需要分类)
        if (bill.type != 2 && bill.type != 3) {
            if (bill.catechoose == 1) {
                params.add("catechoose" to "1")
                bill.catetheme?.takeIf { it.isNotBlank() }?.let {
                    params.add("catetheme" to it)
                }
            } else {
                bill.catename?.takeIf { it.isNotBlank() }?.let {
                    params.add("catename" to it)
                }
            }
        }

        // bookname: 账本名称
        bill.bookname?.takeIf { it.isNotBlank() }?.let {
            params.add("bookname" to it)
        }

        // accountname: 资产账户 / 转出账户
        bill.accountname?.takeIf { it.isNotBlank() }?.let {
            params.add("accountname" to it)
        }

        // accountname2: 转入账户
        bill.accountname2?.takeIf { it.isNotBlank() }?.let {
            params.add("accountname2" to it)
        }

        // fee: 手续费 (仅转账/还款有效)
        bill.fee?.takeIf { it > 0 }?.let {
            val feeStr = if (it % 1.0 == 0.0) it.toLong().toString() else String.format(Locale.US, "%.2f", it)
            params.add("fee" to feeStr)
        }

        // coupon: 优惠券金额
        bill.coupon?.takeIf { it > 0 }?.let {
            val couponStr = if (it % 1.0 == 0.0) it.toLong().toString() else String.format(Locale.US, "%.2f", it)
            params.add("coupon" to couponStr)
        }

        // showresult: 控制是否显示记账成功提示
        bill.showresult?.let {
            params.add("showresult" to it.toString())
        }

        // 拼接文档规范: [固定前缀]+&[参数一]=[值一]&[参数二]=[值二]
        val queryString = params.joinToString("&") { (key, value) ->
            "$key=${Uri.encode(value)}"
        }

        return Uri.parse("qianji://publicapi/addbill?&$queryString")
    }

    /**
     * 发送 Intent 唤起钱迹客户端
     */
    fun launch(context: Context, bill: BillItem): Boolean {
        val uri = buildUri(bill)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setPackage(QIANJI_PACKAGE)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // 若显式指定包名失败，则尝试系统广播/隐式匹配
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
                true
            } catch (ex: Exception) {
                ex.printStackTrace()
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
