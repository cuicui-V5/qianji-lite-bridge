package com.qianji.litebridge.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianji.litebridge.ui.theme.PrimaryBlue
import com.qianji.litebridge.ui.theme.SuccessGreen
import com.qianji.litebridge.util.PreferencesManager

@Composable
fun SettingsCard(
    prefs: PreferencesManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    var showAutoSavedHint by remember { mutableStateOf(false) }

    // 1. AI 提供商
    var aiProvider by remember { mutableStateOf(prefs.aiProvider) }
    var geminiApiKey by remember { mutableStateOf(prefs.geminiApiKey) }
    var geminiModel by remember { mutableStateOf(prefs.geminiModel) }
    var thinkingBudgetText by remember { mutableStateOf(prefs.thinkingBudget.toString()) }

    var customAiBaseUrl by remember { mutableStateOf(prefs.customAiBaseUrl) }
    var customAiApiKey by remember { mutableStateOf(prefs.customAiApiKey) }
    var customAiModel by remember { mutableStateOf(prefs.customAiModel) }
    var customAiThinkingMode by remember { mutableStateOf(prefs.customAiThinkingMode) }
    var customAiAutoThresholdText by remember { mutableStateOf(prefs.customAiAutoThreshold.toString()) }
    var customAiAutoEffort by remember { mutableStateOf(prefs.customAiAutoEffort) }

    // 2. 语音识别 (ASR)
    var asrModeEnabled by remember { mutableStateOf(prefs.asrModeEnabled) }
    var asrProvider by remember { mutableStateOf(prefs.asrProvider) }
    var baiduApiKey by remember { mutableStateOf(prefs.baiduApiKey) }
    var baiduSecretKey by remember { mutableStateOf(prefs.baiduSecretKey) }
    var baiduDevPid by remember { mutableIntStateOf(prefs.baiduDevPid) }

    // 3. 账本与偏好
    var defaultBook by remember { mutableStateOf(prefs.defaultBook) }
    var autoReturn by remember { mutableStateOf(prefs.autoReturn) }
    var categories by remember { mutableStateOf(prefs.categories) }
    var accounts by remember { mutableStateOf(prefs.accounts) }

    // 4. 局域网网关
    var apiToken by remember { mutableStateOf(prefs.apiToken) }

    // 密码可见性
    var isGeminiKeyVisible by remember { mutableStateOf(false) }
    var isCustomKeyVisible by remember { mutableStateOf(false) }
    var isBaiduSecretVisible by remember { mutableStateOf(false) }

    fun saveAll(showToast: Boolean = false) {
        prefs.aiProvider = aiProvider
        prefs.geminiApiKey = geminiApiKey
        prefs.geminiModel = geminiModel
        prefs.thinkingBudget = thinkingBudgetText.toIntOrNull() ?: 0

        prefs.customAiBaseUrl = customAiBaseUrl
        prefs.customAiApiKey = customAiApiKey
        prefs.customAiModel = customAiModel
        prefs.customAiThinkingMode = customAiThinkingMode
        prefs.customAiAutoThreshold = customAiAutoThresholdText.toIntOrNull() ?: 20
        prefs.customAiAutoEffort = customAiAutoEffort

        prefs.asrModeEnabled = asrModeEnabled
        prefs.asrProvider = asrProvider
        prefs.baiduApiKey = baiduApiKey
        prefs.baiduSecretKey = baiduSecretKey
        prefs.baiduDevPid = baiduDevPid

        prefs.defaultBook = defaultBook
        prefs.autoReturn = autoReturn
        prefs.categories = categories
        prefs.accounts = accounts
        prefs.apiToken = apiToken

        showAutoSavedHint = true
        if (showToast) {
            Toast.makeText(context, "配置已成功保存", Toast.LENGTH_SHORT).show()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 折叠控制标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = PrimaryBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "服务与模型配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showAutoSavedHint) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "已自动保存",
                            tint = SuccessGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "已自动保存",
                            fontSize = 11.sp,
                            color = SuccessGreen
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "折叠" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 可折叠内容区
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    // ==========================================
                    // 分区 1：AI 账单解析模型 (LLM)
                    // ==========================================
                    SectionCard(
                        icon = Icons.Default.Psychology,
                        title = "AI 账单提取大模型",
                        subtitle = "负责理解语义并结构化输出记账参数"
                    ) {
                        // AI 提供商切换 Tab
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProviderTab(
                                title = "Google Gemini",
                                selected = aiProvider == "gemini",
                                onClick = {
                                    aiProvider = "gemini"
                                    saveAll(showToast = false)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ProviderTab(
                                title = "自定义 / DeepSeek",
                                selected = aiProvider == "custom",
                                onClick = {
                                    aiProvider = "custom"
                                    saveAll(showToast = false)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (aiProvider == "gemini") {
                            // Gemini 配置
                            OutlinedTextField(
                                value = geminiApiKey,
                                onValueChange = {
                                    geminiApiKey = it
                                    saveAll(showToast = false)
                                },
                                label = { Text("Gemini API Key") },
                                placeholder = { Text("AIzaSy...") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (isGeminiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { isGeminiKeyVisible = !isGeminiKeyVisible }) {
                                        Icon(
                                            imageVector = if (isGeminiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "切换可见性"
                                        )
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = geminiModel,
                                onValueChange = {
                                    geminiModel = it
                                    saveAll(showToast = false)
                                },
                                label = { Text("Gemini 模型名称") },
                                placeholder = { Text("gemini-3.5-flash-lite") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = thinkingBudgetText,
                                onValueChange = {
                                    thinkingBudgetText = it.filter { c -> c.isDigit() }
                                    saveAll(showToast = false)
                                },
                                label = { Text("思考预算 (0=极速 Minimal 模式)") },
                                placeholder = { Text("0") },
                                supportingText = { Text("0=极速 Minimal 模式 (推荐用于秒级记账)；>0 则开启深度思考") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        } else {
                            // 自定义 / DeepSeek 配置 (OpenAI 兼容协议)
                            Text(
                                text = "快捷预设：",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = customAiBaseUrl == "https://api.deepseek.com",
                                    onClick = {
                                        customAiBaseUrl = "https://api.deepseek.com"
                                        customAiModel = "deepseek-flash"
                                        saveAll(showToast = false)
                                    },
                                    label = { Text("DeepSeek 官方", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = customAiBaseUrl == "https://api.siliconflow.cn/v1",
                                    onClick = {
                                        customAiBaseUrl = "https://api.siliconflow.cn/v1"
                                        customAiModel = "deepseek-ai/DeepSeek-V3"
                                        saveAll(showToast = false)
                                    },
                                    label = { Text("硅基流动", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = customAiBaseUrl == "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                    onClick = {
                                        customAiBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
                                        customAiModel = "deepseek-v3"
                                        saveAll(showToast = false)
                                    },
                                    label = { Text("阿里百炼", fontSize = 11.sp) }
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = customAiBaseUrl,
                                onValueChange = {
                                    customAiBaseUrl = it
                                    saveAll(showToast = false)
                                },
                                label = { Text("API Base URL (OpenAI 兼容端点)") },
                                placeholder = { Text("https://api.deepseek.com") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = customAiApiKey,
                                onValueChange = {
                                    customAiApiKey = it
                                    saveAll(showToast = false)
                                },
                                label = { Text("API Key (Bearer Token)") },
                                placeholder = { Text("sk-...") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (isCustomKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { isCustomKeyVisible = !isCustomKeyVisible }) {
                                        Icon(
                                            imageVector = if (isCustomKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "切换可见性"
                                        )
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = customAiModel,
                                onValueChange = {
                                    customAiModel = it
                                    saveAll(showToast = false)
                                },
                                label = { Text("模型名称 (Model Name)") },
                                placeholder = { Text("deepseek-flash") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "思考模式 (DeepSeek 思维链推理)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = customAiThinkingMode == "disabled",
                                    onClick = {
                                        customAiThinkingMode = "disabled"
                                        saveAll(showToast = false)
                                    },
                                    label = { Text("关 (极速)", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = customAiThinkingMode == "low",
                                    onClick = {
                                        customAiThinkingMode = "low"
                                        saveAll(showToast = false)
                                    },
                                    label = { Text("低 (Low)", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = customAiThinkingMode == "high",
                                    onClick = {
                                        customAiThinkingMode = "high"
                                        saveAll(showToast = false)
                                    },
                                    label = { Text("高 (High)", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = customAiThinkingMode == "auto",
                                    onClick = {
                                        customAiThinkingMode = "auto"
                                        saveAll(showToast = false)
                                    },
                                    label = { Text("自动 (默认)", fontSize = 11.sp) }
                                )
                            }

                            if (customAiThinkingMode == "auto") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "🪄 自动推理策略：短文本极速直出，长文本开启推理",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryBlue
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = customAiAutoThresholdText,
                                            onValueChange = {
                                                customAiAutoThresholdText = it.filter { c -> c.isDigit() }
                                                saveAll(showToast = false)
                                            },
                                            label = { Text("字数阈值 (字)") },
                                            placeholder = { Text("20") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )

                                        Column(modifier = Modifier.weight(1.2f)) {
                                            Text(
                                                text = "超阈值强度:",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                FilterChip(
                                                    selected = customAiAutoEffort == "low",
                                                    onClick = {
                                                        customAiAutoEffort = "low"
                                                        saveAll(showToast = false)
                                                    },
                                                    label = { Text("Low", fontSize = 10.sp) }
                                                )
                                                FilterChip(
                                                    selected = customAiAutoEffort == "high",
                                                    onClick = {
                                                        customAiAutoEffort = "high"
                                                        saveAll(showToast = false)
                                                    },
                                                    label = { Text("High", fontSize = 10.sp) }
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "规则：<= ${customAiAutoThresholdText.ifBlank { "20" }} 字不开启推理 (极速直出)；> ${customAiAutoThresholdText.ifBlank { "20" }} 字自动启用 ${customAiAutoEffort.uppercase()} 推理 (增强多账单理解)。",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // ==========================================
                    // 分区 2：语音识别服务 (ASR)
                    // ==========================================
                    SectionCard(
                        icon = Icons.Default.Mic,
                        title = "语音识别服务 (ASR)",
                        subtitle = "负责将录音极速转录为中文文本"
                    ) {
                        // 语音识别开关
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (asrModeEnabled || aiProvider == "custom") "启用两阶段转文字模式" else "原生多模态直接上传模式",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (asrModeEnabled || aiProvider == "custom") SuccessGreen else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (aiProvider == "custom") {
                                        "当前选择自定义大模型 (纯文本)，必须经由 ASR 转写为文字后提取"
                                    } else if (asrModeEnabled) {
                                        "开启：录音后先由专用 ASR 转为文字，再交由 Gemini 提取账单"
                                    } else {
                                        "关闭：直接将录音音频多模态上传至 Gemini（需等待音频特征编码）"
                                    },
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = asrModeEnabled || aiProvider == "custom",
                                enabled = aiProvider != "custom", // 自定义模型强制走 ASR
                                onCheckedChange = {
                                    asrModeEnabled = it
                                    prefs.asrModeEnabled = it
                                    saveAll(showToast = false)
                                }
                            )
                        }

                        if (asrModeEnabled || aiProvider == "custom") {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "选择语音识别服务商：",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ProviderTab(
                                    title = "百度语音识别 (国内极速)",
                                    selected = asrProvider == "baidu",
                                    onClick = {
                                        asrProvider = "baidu"
                                        saveAll(showToast = false)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                ProviderTab(
                                    title = "Google Gemini ASR",
                                    selected = asrProvider == "gemini",
                                    onClick = {
                                        asrProvider = "gemini"
                                        saveAll(showToast = false)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (asrProvider == "baidu") {
                                // 百度语音识别凭证
                                OutlinedTextField(
                                    value = baiduApiKey,
                                    onValueChange = {
                                        baiduApiKey = it
                                        saveAll(showToast = false)
                                    },
                                    label = { Text("百度 API Key (Client ID)") },
                                    placeholder = { Text("百度智能云应用 API Key") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = baiduSecretKey,
                                    onValueChange = {
                                        baiduSecretKey = it
                                        saveAll(showToast = false)
                                    },
                                    label = { Text("百度 Secret Key (Client Secret)") },
                                    placeholder = { Text("百度智能云应用 Secret Key") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = if (isBaiduSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { isBaiduSecretVisible = !isBaiduSecretVisible }) {
                                            Icon(
                                                imageVector = if (isBaiduSecretVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = "切换可见性"
                                            )
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "识别模型版本：",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                baiduDevPid = 1537
                                                saveAll(showToast = false)
                                            }
                                            .padding(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = baiduDevPid == 1537,
                                            onClick = {
                                                baiduDevPid = 1537
                                                saveAll(showToast = false)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Column {
                                            Text("标准版 (1537)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            Text("普通话·带标点·通用稳定", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                baiduDevPid = 80001
                                                saveAll(showToast = false)
                                            }
                                            .padding(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = baiduDevPid == 80001,
                                            onClick = {
                                                baiduDevPid = 80001
                                                saveAll(showToast = false)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Column {
                                            Text("极速版 (80001)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            Text("GPU集群·更快响应·高准确率", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "⚡ 使用 Google 官方转写专用大模型 gemini-3.5-transcribe，共享上方配置的 Gemini API Key，自动启用 Smart 智能规整模式。",
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = SuccessGreen
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // ==========================================
                    // 分区 3：账本与分类偏好
                    // ==========================================
                    SectionCard(
                        icon = Icons.Default.Book,
                        title = "账本与分类偏好",
                        subtitle = "设置钱迹记账时的默认账本、分类和资产账户"
                    ) {
                        // 记账后自动跳回本网关开关
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "记账后自动跳回本网关",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "唤起钱迹保存账单后，自动切回本网关保持常亮",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = autoReturn,
                                onCheckedChange = {
                                    autoReturn = it
                                    prefs.autoReturn = it
                                    showAutoSavedHint = true
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 默认账本
                        OutlinedTextField(
                            value = defaultBook,
                            onValueChange = {
                                defaultBook = it
                                saveAll(showToast = false)
                            },
                            label = { Text("默认账本名称 (例如: 日常账本)") },
                            placeholder = { Text("日常账本") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 分类列表
                        OutlinedTextField(
                            value = categories,
                            onValueChange = {
                                categories = it
                                saveAll(showToast = false)
                            },
                            label = { Text("分类枚举列表 (支持换行或逗号)") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 5
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 资产列表
                        OutlinedTextField(
                            value = accounts,
                            onValueChange = {
                                accounts = it
                                saveAll(showToast = false)
                            },
                            label = { Text("资产账户列表 (留空则不记录账户)") },
                            placeholder = { Text("留空默认不记录账户") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // ==========================================
                    // 分区 4：局域网网关设置
                    // ==========================================
                    SectionCard(
                        icon = Icons.Default.Dns,
                        title = "局域网网关与安全",
                        subtitle = "供外部设备、快捷指令调用本机的 API 凭证"
                    ) {
                        OutlinedTextField(
                            value = apiToken,
                            onValueChange = {
                                apiToken = it
                                saveAll(showToast = false)
                            },
                            label = { Text("局域网 API Bearer Token (可选鉴权)") },
                            placeholder = { Text("留空表示局域网内免密访问") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // 保存并收起按钮
                    Button(
                        onClick = {
                            saveAll(showToast = true)
                            isExpanded = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = "保存")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("保存并收起设置", fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PrimaryBlue,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
        )

        content()
    }
}

@Composable
private fun ProviderTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (selected) PrimaryBlue else MaterialTheme.colorScheme.surface
    val contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    val borderColor = if (selected) PrimaryBlue else MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = contentColor
        )
    }
}
