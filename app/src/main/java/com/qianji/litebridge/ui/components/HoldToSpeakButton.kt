package com.qianji.litebridge.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.qianji.litebridge.QianjiApp
import com.qianji.litebridge.service.GeminiService
import com.qianji.litebridge.service.RateLimitQueue
import com.qianji.litebridge.ui.theme.DangerRed
import com.qianji.litebridge.ui.theme.PrimaryBlue
import com.qianji.litebridge.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class RecordState {
    IDLE,
    RECORDING,
    PROCESSING
}

@Composable
fun HoldToSpeakButton(
    geminiService: GeminiService,
    queue: RateLimitQueue,
    prefs: PreferencesManager,
    onLog: (String) -> Unit,
    modifier: Modifier = Modifier,
    quickRecordTrigger: Boolean = false,
    onQuickRecordHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var recordState by remember { mutableStateOf(RecordState.IDLE) }
    var pressStartTime by remember { mutableLongStateOf(0L) }
    var currentRecordFile by remember { mutableStateOf<File?>(null) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var isTapToStopMode by remember { mutableStateOf(false) }
    var pendingAutoRecordAfterPermission by remember { mutableStateOf(false) }

    // 录音权限检查
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        if (isGranted) {
            if (pendingAutoRecordAfterPermission) {
                pendingAutoRecordAfterPermission = false
                isTapToStopMode = true
            }
        } else {
            pendingAutoRecordAfterPermission = false
            Toast.makeText(context, "需要录音权限以支持语音记账", Toast.LENGTH_SHORT).show()
        }
    }

    // 录音时的呼吸动画
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val buttonBgColor by animateColorAsState(
        targetValue = when (recordState) {
            RecordState.IDLE -> PrimaryBlue
            RecordState.RECORDING -> DangerRed
            RecordState.PROCESSING -> Color(0xFF6750A4)
        },
        label = "buttonBgColor"
    )

    val currentRecordState by rememberUpdatedState(recordState)
    val currentIsTapToStop by rememberUpdatedState(isTapToStopMode)

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun vibratePress() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    fun vibrateRelease() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25)
            }
        } catch (_: Exception) {}
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
        }
    }

    fun startRecording() {
        if (recordState != RecordState.IDLE) return
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        pressStartTime = System.currentTimeMillis()

        try {
            val audioFile = File(context.cacheDir, "record_${pressStartTime}.m4a")
            currentRecordFile = audioFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordState = RecordState.RECORDING

            val aiName = if (prefs.aiProvider == "custom") prefs.customAiModel.ifBlank { "自定义AI" } else "Gemini"
            val asrName = if (prefs.asrProvider == "baidu") "百度语音" else "Gemini转写"
            val modeDesc = if (prefs.aiProvider == "custom" || prefs.asrModeEnabled) "$asrName + $aiName" else "Gemini原生多模态"
            if (!isTapToStopMode) {
                onLog("🎙️ [$modeDesc] 开始录音，按住说话...")
            }
        } catch (e: Exception) {
            recordState = RecordState.IDLE
            isTapToStopMode = false
            onLog("❌ 启动录音失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // 快捷方式呼起时自动进入录音状态
    LaunchedEffect(quickRecordTrigger) {
        if (quickRecordTrigger) {
            onQuickRecordHandled()
            if (recordState == RecordState.IDLE) {
                if (hasAudioPermission) {
                    isTapToStopMode = true
                    startRecording()
                    onLog("🎙️ [快捷记账] 已自动开启录音，正在倾听... 说完请轻触屏幕按钮结束并记账")
                } else {
                    pendingAutoRecordAfterPermission = true
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    fun stopAndProcessRecording() {
        if (recordState != RecordState.RECORDING) return
        val duration = System.currentTimeMillis() - pressStartTime

        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
        }

        val file = currentRecordFile
        if (duration < 600) {
            onLog("⚠️ 录音时间太短 (${duration}ms)，已自动忽略")
            file?.delete()
            recordState = RecordState.IDLE
            isTapToStopMode = false
            return
        }

        if (file == null || !file.exists() || file.length() == 0L) {
            onLog("❌ 录音文件无效")
            recordState = RecordState.IDLE
            isTapToStopMode = false
            return
        }

        recordState = RecordState.PROCESSING
        val asrDesc = if (prefs.asrProvider == "baidu") "百度语音识别" else "gemini-3.5-transcribe"
        if (prefs.aiProvider == "custom" || prefs.asrModeEnabled) {
            onLog("🤖 录音完成 (${duration}ms)，正在调用 $asrDesc 转写...")
        } else {
            onLog("🤖 录音完成 (${duration}ms)，正在调用 Gemini 进行多模态解析...")
        }

        scope.launch {
            try {
                val bills = withContext(Dispatchers.IO) {
                    QianjiApp.instance.aiServiceHub.parseAudio(file) { progressMsg ->
                        onLog(progressMsg)
                    }
                }

                if (bills.isEmpty()) {
                    onLog("⚠️ 未从语音中识别出有效账单")
                } else {
                    onLog("✨ 成功识别出 ${bills.size} 笔账单，加入限流队列...")
                    queue.enqueueAll(bills)
                }
            } catch (e: Exception) {
                onLog("❌ AI 识别异常: ${e.message}")
                e.printStackTrace()
            } finally {
                file.delete()
                recordState = RecordState.IDLE
                isTapToStopMode = false
            }
        }
    }

    // 交互区域
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)

                    if (currentRecordState == RecordState.PROCESSING) {
                        return@awaitEachGesture
                    }

                    if (currentIsTapToStop && currentRecordState == RecordState.RECORDING) {
                        // 桌面快捷方式一键直达模式：点击即可结束并开始记账
                        vibrateRelease()
                        isTapToStopMode = false
                        stopAndProcessRecording()
                        waitForUpOrCancellation()
                    } else {
                        // 主界面常规模式：长按说话，松开结束
                        if (currentRecordState == RecordState.IDLE) {
                            vibratePress()
                            startRecording()

                            val upOrCancel = waitForUpOrCancellation()
                            vibrateRelease()
                            if (upOrCancel != null) {
                                stopAndProcessRecording()
                            } else {
                                try {
                                    mediaRecorder?.stop()
                                    mediaRecorder?.release()
                                } catch (_: Exception) {}
                                mediaRecorder = null
                                currentRecordFile?.delete()
                                recordState = RecordState.IDLE
                                onLog("⚠️ 录音手势已取消")
                            }
                        }
                    }
                }
            },
        color = buttonBgColor,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .scale(if (recordState == RecordState.RECORDING) pulseScale else 1.0f)
            ) {
                // 响应式订阅配置版本变动
                val currentVersion = prefs.settingsVersion

                when (recordState) {
                    RecordState.IDLE -> {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "按住说话",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "按住 说话记账",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = prefs.getModeSummary(),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp
                        )
                    }

                    RecordState.RECORDING -> {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "正在倾听",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isTapToStopMode) "🔴 正在倾听... 点击此区域结束" else if (prefs.asrModeEnabled) "请说话... 松开立即高速转写" else "正在录音中... 松开立即记账",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isTapToStopMode) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "（说完后轻点屏幕即可开始识别）",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 13.sp
                            )
                        }
                    }

                    RecordState.PROCESSING -> {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(46.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = if (prefs.asrModeEnabled) "AI 正在转写与提取账单..." else "AI 正在提取账单...",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
