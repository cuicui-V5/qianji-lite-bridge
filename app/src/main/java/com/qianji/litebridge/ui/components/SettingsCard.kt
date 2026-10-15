package com.qianji.litebridge.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    var apiKey by remember { mutableStateOf(prefs.geminiApiKey) }
    var categories by remember { mutableStateOf(prefs.categories) }
    var accounts by remember { mutableStateOf(prefs.accounts) }
    var defaultBook by remember { mutableStateOf(prefs.defaultBook) }
    var apiToken by remember { mutableStateOf(prefs.apiToken) }
    var geminiModel by remember { mutableStateOf(prefs.geminiModel) }
    var thinkingBudgetText by remember { mutableStateOf(prefs.thinkingBudget.toString()) }

    var isApiKeyVisible by remember { mutableStateOf(false) }
    var showAutoSavedHint by remember { mutableStateOf(false) }

    fun saveAll(showToast: Boolean = false) {
        prefs.geminiApiKey = apiKey
        prefs.geminiModel = geminiModel
        prefs.categories = categories
        prefs.accounts = accounts
        prefs.defaultBook = defaultBook
        prefs.apiToken = apiToken
        val budget = thinkingBudgetText.toIntOrNull() ?: 0
        prefs.thinkingBudget = budget
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
                    // Gemini API Key
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            saveAll(showToast = false)
                        },
                        label = { Text("Gemini API Key (实时自动保存)") },
                        placeholder = { Text("AIzaSy...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                Icon(
                                    imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle API Key visibility"
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Gemini Model
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

                    Spacer(modifier = Modifier.height(10.dp))

                    // 思考预算 / 推理等级 (Thinking Budget)
                    OutlinedTextField(
                        value = thinkingBudgetText,
                        onValueChange = {
                            thinkingBudgetText = it.filter { char -> char.isDigit() }
                            saveAll(showToast = false)
                        },
                        label = { Text("思考预算 / Thinking Budget (0=极速关闭深度思考)") },
                        placeholder = { Text("0") },
                        supportingText = { Text("0=关闭深度思考 (1~2秒极速返回，推荐用于记账)；>0 则启用 CoT 深度思考") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

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

                    Spacer(modifier = Modifier.height(10.dp))

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

                    Spacer(modifier = Modifier.height(10.dp))

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

                    Spacer(modifier = Modifier.height(10.dp))

                    // Bearer Token
                    OutlinedTextField(
                        value = apiToken,
                        onValueChange = {
                            apiToken = it
                            saveAll(showToast = false)
                        },
                        label = { Text("局域网 API Bearer Token (可选鉴权)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 保存并收起按钮
                    Button(
                        onClick = {
                            saveAll(showToast = true)
                            isExpanded = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
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
