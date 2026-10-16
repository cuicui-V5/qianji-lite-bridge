package com.qianji.litebridge.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 手机系统原生语音识别封装 (SpeechRecognizer)
 * 深度适配三星 One UI、Galaxy AI 本地离线语言包、Google 语音服务及第三方引擎
 */
class SystemSpeechRecognizer(private val context: Context) {

    data class AsrEngineItem(
        val id: String,
        val displayName: String,
        val componentName: ComponentName?,
        val isInstalled: Boolean,
        val description: String = ""
    )

    data class DiagnosticInfo(
        val isAvailable: Boolean,
        val activeEngineName: String,
        val allEngines: List<AsrEngineItem>
    )

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var recognizedTextBuffer = ""

    companion object {
        val GOOGLE_COMPONENT = ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        )
        val GOOGLE_TTS_COMPONENT = ComponentName(
            "com.google.android.tts",
            "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
        )
    }

    /**
     * 枚举当前系统内已安装的所有语音识别服务
     */
    fun getAvailableEngines(): List<AsrEngineItem> {
        val pm = context.packageManager
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val resolveList = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
            }
        } catch (_: Exception) {
            emptyList()
        }

        val engines = mutableListOf<AsrEngineItem>()
        val isSamsungDevice = Build.MANUFACTURER.contains("samsung", ignoreCase = true)
        val isOnDeviceAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        // 1. 自动优选 (若是三星设备优先选用三星本地离线语言包，零延迟且无网络要求)
        engines.add(
            AsrEngineItem(
                id = "auto",
                displayName = "自动优选 (三星离线语音优先)",
                componentName = null,
                isInstalled = true,
                description = "优先匹配系统已下载的三星官方离线语言包，零延迟极速识别"
            )
        )

        // 2. 三星本地端侧离线语音 (直接使用设置里下载的「中文(中国) 语音输入」语言包)
        engines.add(
            AsrEngineItem(
                id = "samsung_ondevice",
                displayName = "三星本地端侧语音 (已装语言包·离线)",
                componentName = null,
                isInstalled = isOnDeviceAvailable || isSamsungDevice,
                description = "直接调用系统「中文(中国) 语音输入」离线模型，无网络请求，毫秒级响应"
            )
        )

        // 3. 三星 Bixby 语音识别 (One UI 默认通道)
        val samsungResolved = resolveList.find {
            it.serviceInfo.packageName.contains("bixby") ||
                    it.serviceInfo.packageName.contains("samsung") ||
                    it.serviceInfo.packageName.contains("svoice")
        }
        val isSamsungInstalled = samsungResolved != null || isSamsungDevice || try {
            pm.getPackageInfo("com.samsung.android.bixby.agent", 0) != null
        } catch (_: Exception) {
            false
        }
        val samsungComp = samsungResolved?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            ?: ComponentName("com.samsung.android.bixby.agent", "com.samsung.android.bixby.agent.mainui.voiceinteraction.RecognitionServiceTrampoline")

        engines.add(
            AsrEngineItem(
                id = "samsung_bixby",
                displayName = "三星 Bixby 语音服务 (One UI 原厂)",
                componentName = samsungComp,
                isInstalled = isSamsungInstalled,
                description = "通过三星 Bixby 系统服务通道进行识别"
            )
        )

        // 4. Google 语音识别 (Google App 官方组件)
        val isGoogleAppInstalled = try {
            pm.getPackageInfo("com.google.android.googlequicksearchbox", 0) != null
        } catch (_: Exception) {
            false
        }
        val isGoogleTtsInstalled = try {
            pm.getPackageInfo("com.google.android.tts", 0) != null
        } catch (_: Exception) {
            false
        }
        val resolvedGoogle = resolveList.find { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
            ?: resolveList.find { it.serviceInfo.packageName == "com.google.android.tts" }
        val googleTargetComp = resolvedGoogle?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            ?: if (isGoogleAppInstalled) GOOGLE_COMPONENT else if (isGoogleTtsInstalled) GOOGLE_TTS_COMPONENT else null

        engines.add(
            AsrEngineItem(
                id = "google",
                displayName = "Google 语音识别 (需海外网络/Google语言包)",
                componentName = googleTargetComp,
                isInstalled = isGoogleAppInstalled || isGoogleTtsInstalled || resolvedGoogle != null,
                description = "调用 Google App 官方服务（若未下载 Google 专用离线包需能连通 Google）"
            )
        )

        // 5. 解析系统内发现的其它所有 RecognitionService
        resolveList.forEach { ri ->
            val pkg = ri.serviceInfo.packageName
            val comp = ComponentName(pkg, ri.serviceInfo.name)
            if (pkg != "com.google.android.googlequicksearchbox" &&
                pkg != "com.google.android.tts" &&
                !pkg.contains("bixby") &&
                !pkg.contains("samsung")) {
                val label = when {
                    pkg == "io.homeassistant.companion.android" -> "Home Assistant 语音 (需配置Assist)"
                    pkg.contains("iflytek") -> "科大讯飞语音服务"
                    pkg.contains("baidu") -> "百度语音服务"
                    else -> "${ri.loadLabel(pm)} ($pkg)"
                }
                engines.add(
                    AsrEngineItem(
                        id = "component:${pkg}/${ri.serviceInfo.name}",
                        displayName = label,
                        componentName = comp,
                        isInstalled = true,
                        description = "第三方应用注册的语音服务"
                    )
                )
            }
        }

        // 6. 系统原生默认通道 (不指定 ComponentName)
        engines.add(
            AsrEngineItem(
                id = "system_default",
                displayName = "系统原生默认通道 (随系统数字助手)",
                componentName = null,
                isInstalled = true,
                description = "由 Android 系统按照 VOICE_RECOGNITION_SERVICE 规则自动指派"
            )
        )

        return engines
    }

    /**
     * 诊断当前可用引擎与当前选中状态
     */
    fun diagnose(preferredChoice: String = "auto"): DiagnosticInfo {
        val engines = getAvailableEngines()
        val active = resolveActiveEngine(preferredChoice, engines)
        val isAnyAvailable = engines.any { it.isInstalled && it.id != "auto" && it.id != "system_default" } ||
                SpeechRecognizer.isRecognitionAvailable(context)

        return DiagnosticInfo(
            isAvailable = isAnyAvailable,
            activeEngineName = active.displayName,
            allEngines = engines
        )
    }

    private fun resolveActiveEngine(preferredChoice: String, engines: List<AsrEngineItem>): AsrEngineItem {
        val isSamsungDevice = Build.MANUFACTURER.contains("samsung", ignoreCase = true)
        return when (preferredChoice) {
            "samsung_ondevice" -> engines.find { it.id == "samsung_ondevice" } ?: engines.first()
            "samsung_bixby", "samsung" -> engines.find { it.id == "samsung_bixby" } ?: engines.first()
            "google" -> engines.find { it.id == "google" } ?: engines.first()
            "system_default" -> engines.find { it.id == "system_default" } ?: engines.first()
            "auto" -> {
                // 自动模式：三星设备首选三星本地离线端侧语言包！
                if (isSamsungDevice) {
                    engines.find { it.id == "samsung_ondevice" && it.isInstalled }
                        ?: engines.find { it.id == "samsung_bixby" && it.isInstalled }
                        ?: engines.find { it.id == "google" && it.isInstalled }
                        ?: engines.find { it.id == "system_default" }
                        ?: engines.first()
                } else {
                    engines.find { it.id == "google" && it.isInstalled }
                        ?: engines.find { it.id == "samsung_ondevice" && it.isInstalled }
                        ?: engines.find { it.id == "system_default" }
                        ?: engines.first()
                }
            }
            else -> engines.find { it.id == preferredChoice }
                ?: engines.find { it.id == "auto" }
                ?: engines.first()
        }
    }

    fun isAvailable(): Boolean {
        return diagnose().isAvailable
    }

    fun startListening(
        preferredChoice: String = "auto",
        onPartial: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            try {
                cancel()
                destroy()

                recognizedTextBuffer = ""

                val engines = getAvailableEngines()
                val targetEngine = resolveActiveEngine(preferredChoice, engines)
                val targetComp = targetEngine.componentName

                val recognizer = when (targetEngine.id) {
                    "samsung_ondevice" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            try {
                                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                            } catch (_: Exception) {
                                SpeechRecognizer.createSpeechRecognizer(context)
                            }
                        } else {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        }
                    }
                    else -> {
                        if (targetComp != null) {
                            SpeechRecognizer.createSpeechRecognizer(context, targetComp)
                        } else {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        }
                    }
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
                            SpeechRecognizer.ERROR_CLIENT -> {
                                if (targetEngine.id == "google") {
                                    "Google 语音异常 (ERROR_CLIENT)。国内网络无法直连 Google，建议在设置中切换为「三星本地端侧语音」！"
                                } else if (targetEngine.displayName.contains("Home Assistant")) {
                                    "Home Assistant 异常 (ERROR_CLIENT)。未连接到局域网 HA 语音流水线，请在设置中切换为「三星本地端侧语音」"
                                } else {
                                    "客户端调用异常 (ERROR_CLIENT)"
                                }
                            }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "无麦克风录音权限"
                            SpeechRecognizer.ERROR_NETWORK -> "网络连接异常 (ERROR_NETWORK，请检查网络)"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络连接超时 (ERROR_NETWORK_TIMEOUT)"
                            SpeechRecognizer.ERROR_NO_MATCH -> "未检测到清晰语音 (ERROR_NO_MATCH)"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别引擎正忙 (ERROR_RECOGNIZER_BUSY)"
                            SpeechRecognizer.ERROR_SERVER -> "服务端识别错误 (ERROR_SERVER)"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "等待说话超时 (ERROR_SPEECH_TIMEOUT)"
                            10 -> "当前语言未支持 (ERROR_LANGUAGE_NOT_SUPPORTED)。Google 未下载离线包，请在设置中切换为已安装好的「三星本地端侧语音」！"
                            11 -> "语言模型包未就绪 (ERROR_LANGUAGE_UNAVAILABLE)"
                            12 -> "语音引擎断开连接 (ERROR_SERVER_DISCONNECTED)"
                            13 -> "调用频次受限 (ERROR_TOO_MANY_REQUESTS)"
                            else -> "语音异常 (错误码: $error)"
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
                    
                    // 传入全套中文语言标识以确保各家引擎兼容
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "zh-CN")
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("zh-CN", "zh_CN", "cmn-Hans-CN", "zh"))
                    
                    if (targetEngine.id == "samsung_ondevice" || targetEngine.id == "auto") {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                    
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                recognizer.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                isListening = false
                onError("启动语音引擎失败: ${e.message}")
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
