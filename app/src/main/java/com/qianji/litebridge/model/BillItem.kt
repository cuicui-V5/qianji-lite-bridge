package com.qianji.litebridge.model

import com.google.gson.annotations.SerializedName

/**
 * 钱迹账单数据模型
 * 遵循钱迹自动化记账接口规范: qianji://publicapi/addbill?...
 */
data class BillItem(
    @SerializedName("type")
    val type: Int = 0, // 0支出，1收入，2转账，3信用卡还款，5报销

    @SerializedName("money")
    val money: Double = 0.0, // 账单金额 (> 0)

    @SerializedName("time")
    val time: String? = null, // yyyy-MM-dd HH:mm:ss

    @SerializedName("remark")
    val remark: String? = null, // 备注

    @SerializedName("catename")
    val catename: String? = null, // 分类名称，支持 /::/ 二级分类，如 "三餐/::/午餐"

    @SerializedName("catechoose")
    val catechoose: Int? = null, // 是否弹出分类选择面板 (1: 弹出)

    @SerializedName("catetheme")
    val catetheme: String? = null, // light / auto / dark

    @SerializedName("bookname")
    val bookname: String? = null, // 账本名称

    @SerializedName("accountname")
    val accountname: String? = null, // 资产名称 / 转出账户

    @SerializedName("accountname2")
    val accountname2: String? = null, // 转入账户 (转账/还款时必填)

    @SerializedName("fee")
    val fee: Double? = null, // 手续费 (> 0 且 <= money)

    @SerializedName("coupon")
    val coupon: Double? = null, // 优惠券金额 (> 0 且 <= money)

    @SerializedName("showresult")
    val showresult: Int? = 1, // 1: 显示记账Toast, 0: 不显示

    /**
     * 记账后是否自动跳回本网关主界面
     * (悬浮窗触发设为 false，保留在宿主应用；主界面触发设为 true，保持常亮)
     */
    @Transient
    val autoReturn: Boolean = true
) {
    fun typeLabel(): String = when (type) {
        0 -> "支出"
        1 -> "收入"
        2 -> "转账"
        3 -> "信用卡还款"
        5 -> "报销"
        else -> "账单"
    }

    fun summary(): String {
        val cat = if (!catename.isNullOrBlank()) "[$catename]" else ""
        val acc = if (!accountname.isNullOrBlank()) "[$accountname]" else ""
        val rem = if (!remark.isNullOrBlank()) " - $remark" else ""
        return "${typeLabel()} ¥%.2f $cat$acc$rem".format(money)
    }
}
