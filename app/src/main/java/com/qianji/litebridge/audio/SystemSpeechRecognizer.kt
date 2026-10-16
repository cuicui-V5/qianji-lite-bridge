package com.qianji.litebridge.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 手机系统原生语音识别封装 (SpeechRecognizer)
 * 深度适配三星 One UI、Google 语音服务及国内厂商系统引擎，支持端侧离线与云端识别
 */
class SystemSpeechRecognizer(private val context: Context) {

    data class DiagnosticInfo(
        val isAvailable: Boolean,
        val isOnDeviceAvailable: Boolean,
        val serviceCount: Int,
        val servicePackages: List<String>,
        val summaryText: String
    )

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var recognizedTextBuffer = ""

    /**
     * 诊断当前系统语音识别引擎的安装与支持状态
     */
    fun diagnose(): DiagnosticInfo {
        val pm = context.packageManager
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val resolveInfos = try {
            pm.queryIntentServices(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }

        val packages = resolveInfos.map { "${it.serviceInfo.packageName} (${it.serviceInfo.name.substringAfterLast('.')})" }
        val isStandardAvailable = SpeechRecognizer.isRecognitionAvailable(context) || resolveInfos.isNotEmpty()
        val isOnDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        val summary = when {
            resolveInfos.any { it.serviceInfo.packageName.contains("samsung") || it.serviceInfo.packageName.contains("bixby") } ->
                if (isOnDevice) "三星 Bixby 语音 (支持端侧离线加速)" else "三星 Bixby 语音服务"
            resolveInfos.any { it.serviceInfo.packageName.contains("google") } ->
                if (isOnDevice) "Google 语音服务 (支持端侧离线加速)" else "Google 语音服务"
            resolveInfos.any { it.serviceInfo.packageName.contains("iflytek") } -> "科大讯飞语音服务"
            resolveInfos.any { it.serviceInfo.packageName.contains("baidu") } -> "百度语音服务"
            resolveInfos.isNotEmpty() -> "系统语音服务 (${resolveInfos.first().serviceInfo.packageName})"
            isStandardAvailable -> "系统默认语音服务"
            else -> "未检测到可用语音服务"
        }

        return DiagnosticInfo(
            isAvailable = isStandardAvailable,
            isOnDeviceAvailable = isOnDevice,
            serviceCount = resolveInfos.size,
            servicePackages = packages,
            summaryText = summary
        )
    }

    fun isAvailable(): Boolean {
        return diagnose().isAvailable
    }

    fun startListening(
        onPartial: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            try {
                cancel()
                destroy()

                recognizedTextBuffer = ""

                // 优先创建 Android 12+ 端侧离线极速识别实例；若不支持则使用标准系统识别实例
                val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    try {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } catch (_: Exception) {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }

                speechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        isListening = false
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "音频录制错误 (ERROR_AUDIO)"
                            SpeechRecognizer.ERROR_CLIENT -> "客户端异常 (ERROR_CLIENT)"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "无麦克风录音权限"
                            SpeechRecognizer.ERROR_NETWORK -> "网络连接异常 (ERROR_NETWORK)"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络连接超时 (ERROR_NETWORK_TIMEOUT)"
                            SpeechRecognizer.ERROR_NO_MATCH -> "未检测到清晰语音 (ERROR_NO_MATCH)"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别引擎正忙 (ERROR_RECOGNIZER_BUSY)"
                            SpeechRecognizer.ERROR_SERVER -> "服务端识别错误 (ERROR_SERVER)"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "等待说话超时 (ERROR_SPEECH_TIMEOUT)"
                            10 -> "当前语言未支持 (ERROR_LANGUAGE_NOT_SUPPORTED)"
                            11 -> "语言模型包未就绪 (ERROR_LANGUAGE_UNAVAILABLE)"
                            12 -> "语音引擎断开连接 (ERROR_SERVER_DISCONNECTED)"
                            13 -> "调用频次受限 (ERROR_TOO_MANY_REQUESTS)"
                            else -> "识别异常 (错误码: $error)"
                        }
                        // 若在出错前已捕获到部分文字（例如超时前已经说完了金额），优先采纳现有文字
                        if (recognizedTextBuffer.isNotBlank()) {
                            onResult(recognizedTextBuffer)
                        } else {
                            onError(errorMsg)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: recognizedTextBuffer
                        if (text.isNotBlank()) {
                            onResult(text)
                        } else {
                            onError("未识别出有效语音文本")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim()
                        if (!partial.isNullOrEmpty()) {
                            recognizedTextBuffer = partial
                            onPartial(partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "zh-CN")
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("zh-CN", "cmn-Hans-CN", "en-US"))
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                recognizer.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                isListening = false
                onError("启动系统语音识别失败: ${e.message}")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            if (isListening) {
                try {
                    speechRecognizer?.stopListening()
                } catch (_: Exception) {}
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            isListening = false
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
        }
    }

    fun destroy() {
        mainHandler.post {
            isListening = false
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }
    }
}
