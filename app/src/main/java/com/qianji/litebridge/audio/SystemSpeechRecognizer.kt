package com.qianji.litebridge.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 手机系统原生语音识别封装 (SpeechRecognizer)
 * 优先调用手机本地/厂商语音引擎（小爱/讯飞/小艺等），毫秒级输出文本
 */
class SystemSpeechRecognizer(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var recognizedTextBuffer = ""

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
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
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
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
                            SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                            SpeechRecognizer.ERROR_CLIENT -> "语音客户端错误"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "无麦克风权限"
                            SpeechRecognizer.ERROR_NETWORK -> "网络连接异常"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络连接超时"
                            SpeechRecognizer.ERROR_NO_MATCH -> "未检测到清晰语音"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别引擎正忙"
                            SpeechRecognizer.ERROR_SERVER -> "服务端识别错误"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "等待说话超时"
                            else -> "识别异常(错误码: $error)"
                        }
                        // 若在出错前已捕获到部分文字，优先采纳现有文字
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
