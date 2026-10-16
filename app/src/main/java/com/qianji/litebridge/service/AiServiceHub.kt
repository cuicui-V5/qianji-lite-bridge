package com.qianji.litebridge.service

import com.qianji.litebridge.model.BillItem
import com.qianji.litebridge.util.PreferencesManager
import java.io.File

class AiServiceHub(
    val prefs: PreferencesManager,
    val geminiService: GeminiService,
    val customAiService: CustomAiService,
    val baiduSpeechService: BaiduSpeechService
) {
    /**
     * 调用当前配置的 ASR 服务转录音频 (百度语音识别 vs Gemini ASR)
     */
    suspend fun transcribeAudio(
        audioFile: File,
        onProgress: (String) -> Unit = {}
    ): String {
        return when (prefs.asrProvider) {
            "baidu" -> baiduSpeechService.transcribeAudio(audioFile, onProgress)
            else -> geminiService.transcribeAudio(audioFile, onProgress)
        }
    }

    /**
     * 调用当前配置的 AI 模型解析纯文本账单 (自定义/DeepSeek vs Gemini)
     */
    suspend fun parseText(
        text: String,
        onProgress: (String) -> Unit = {}
    ): List<BillItem> {
        return when (prefs.aiProvider) {
            "custom" -> customAiService.parseText(text, onProgress)
            else -> geminiService.parseText(text, onProgress)
        }
    }

    /**
     * 统一录音解析入口：
     * 1. 自定义 AI 模式 (如 DeepSeek)：因其为纯文本模型，自动以两阶段模式运行（选定 ASR 转录 -> 自定义 AI 提取）；
     * 2. Gemini 模式：
     *    - 开启 ASR 开关：选定 ASR 转录 (Baidu 或 Gemini) -> Gemini 提取；
     *    - 关闭 ASR 开关：走 Gemini 原生音频多模态直接上传解析。
     */
    suspend fun parseAudio(
        audioFile: File,
        onProgress: (String) -> Unit = {}
    ): List<BillItem> {
        val mustUseAsr = prefs.aiProvider == "custom"
        val useAsr = prefs.asrModeEnabled || mustUseAsr

        if (useAsr) {
            val text = transcribeAudio(audioFile, onProgress)
            return parseText(text, onProgress)
        } else {
            return geminiService.parseAudio(audioFile, onProgress)
        }
    }
}
