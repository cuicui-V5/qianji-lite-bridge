package com.qianji.litebridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.qianji.litebridge.MainActivity
import com.qianji.litebridge.QianjiApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FloatingCapsuleService : Service() {

    enum class CapsuleState {
        IDLE,
        RECORDING,
        PROCESSING,
        SUCCESS,
        ERROR
    }

    private var windowManager: WindowManager? = null
    private var capsuleView: LinearLayout? = null
    private var iconView: TextView? = null
    private var labelView: TextView? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentState = CapsuleState.IDLE
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordFile: File? = null
    private var recordStartTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning.value = true

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QianjiLiteBridge:FloatingCapsuleRecording")

        startForegroundWithNotification()
        createCapsuleOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "floating_capsule_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "钱迹悬浮胶囊服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "全局悬浮胶囊保活与交互"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingCapsuleService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("钱迹语音悬浮胶囊已启动")
            .setContentText("按住悬浮胶囊随时记账，点按返回应用主页")
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭胶囊", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createCapsuleOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val prefs = QianjiApp.instance.prefs
        val initialX = prefs.floatingX
        val initialY = prefs.floatingY

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        val density = resources.displayMetrics.density
        val dpToPx = { dp: Int -> (dp * density).toInt() }

        // 构建胶囊根布局 (水平 LinearLayout)
        capsuleView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), dpToPx(9), dpToPx(16), dpToPx(9))
            elevation = 12f * density
        }

        // 图标 / 状态指示 Emoji
        iconView = TextView(this).apply {
            text = "🎙️"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        // 文字标签
        labelView = TextView(this).apply {
            text = "按住记账"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(6)
            }
            layoutParams = lp
        }

        capsuleView?.addView(iconView)
        capsuleView?.addView(labelView)

        updateCapsuleStyle(CapsuleState.IDLE, "🎙️", "按住记账")
        setupTouchListener()

        try {
            windowManager?.addView(capsuleView, windowParams)
        } catch (e: Exception) {
            e.printStackTrace()
            QianjiApp.instance.addLog("❌ 悬浮窗创建失败: ${e.message}")
        }
    }

    private fun updateCapsuleStyle(state: CapsuleState, iconText: String, message: String) {
        currentState = state
        iconView?.text = iconText
        labelView?.text = message

        val density = resources.displayMetrics.density
        val radius = 24f * density

        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            when (state) {
                CapsuleState.IDLE -> {
                    setColor(Color.parseColor("#E62563EB")) // 经典半透明主蓝 (90% alpha)
                    setStroke((1.5f * density).toInt(), Color.parseColor("#66FFFFFF"))
                }
                CapsuleState.RECORDING -> {
                    setColor(Color.parseColor("#F2DC2626")) // 录音深红
                    setStroke((2f * density).toInt(), Color.parseColor("#FFFFFFFF"))
                }
                CapsuleState.PROCESSING -> {
                    setColor(Color.parseColor("#E67C3AED")) // AI 思考紫
                    setStroke((1.5f * density).toInt(), Color.parseColor("#80FFFFFF"))
                }
                CapsuleState.SUCCESS -> {
                    setColor(Color.parseColor("#E616A34A")) // 成功绿
                    setStroke((2f * density).toInt(), Color.parseColor("#FFFFFFFF"))
                }
                CapsuleState.ERROR -> {
                    setColor(Color.parseColor("#E6EA580C")) // 警告橙红
                    setStroke((1.5f * density).toInt(), Color.parseColor("#80FFFFFF"))
                }
            }
        }

        capsuleView?.background = backgroundDrawable
    }

    private fun setupTouchListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoved = false
        var isRecording = false

        val density = resources.displayMetrics.density
        val touchSlop = (ViewConfiguration.get(this).scaledTouchSlop).coerceAtLeast((10 * density).toInt())

        val longPressRunnable = Runnable {
            if (!isMoved && !isRecording && currentState == CapsuleState.IDLE) {
                isRecording = true
                vibrate(40)
                startRecording()
            }
        }

        capsuleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoved = false
                    isRecording = false

                    // 260ms 内未移动判定为按住录音
                    mainHandler.postDelayed(longPressRunnable, 260)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (!isRecording) {
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            isMoved = true
                            mainHandler.removeCallbacks(longPressRunnable)

                            windowParams.x = initialX + dx
                            windowParams.y = initialY + dy
                            try {
                                windowManager?.updateViewLayout(capsuleView, windowParams)
                            } catch (_: Exception) {}
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)

                    if (isRecording) {
                        isRecording = false
                        vibrate(20)
                        stopAndProcessRecording()
                    } else if (!isMoved) {
                        // 点按操作：直接跳转回应用主页面
                        openMainActivity()
                    } else {
                        // 拖动结束：持久化记录坐标
                        QianjiApp.instance.prefs.floatingX = windowParams.x
                        QianjiApp.instance.prefs.floatingY = windowParams.y
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (isRecording) {
                        isRecording = false
                        cancelRecording()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun openMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            QianjiApp.instance.addLog("❌ 返回主界面失败: ${e.message}")
        }
    }

    private fun startRecording() {
        val app = QianjiApp.instance
        recordStartTime = System.currentTimeMillis()

        try {
            wakeLock?.acquire(60_000) // 最长持有 60 秒唤醒锁，防止锁屏或休眠切断麦克风
        } catch (_: Exception) {}

        val modeDesc = app.prefs.getModeSummary()
        app.addLog("🎙️ [悬浮胶囊] 启动录音 ($modeDesc)，按住说话...")

        val audioFile = File(cacheDir, "float_record_${recordStartTime}.m4a")
        currentRecordFile = audioFile

        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
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
                setOnErrorListener { _, what, extra ->
                    app.addLog("❌ [悬浮胶囊] 录音底层被系统中断 (what=$what, extra=$extra)")
                    mainHandler.post {
                        updateCapsuleStyle(CapsuleState.ERROR, "❌", "录音被中断")
                    }
                }
                prepare()
                start()
            }
            updateCapsuleStyle(CapsuleState.RECORDING, "🔴", "正在录音...")
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Exception) {}
            app.addLog("❌ [悬浮胶囊] 录音启动失败: ${e.message}")
            updateCapsuleStyle(CapsuleState.ERROR, "❌", "录音失败")
            mainHandler.postDelayed({
                updateCapsuleStyle(CapsuleState.IDLE, "🎙️", "按住记账")
            }, 1800)
        }
    }

    private fun stopAndProcessRecording() {
        val duration = System.currentTimeMillis() - recordStartTime
        val app = QianjiApp.instance
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}

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
            app.addLog("⚠️ [悬浮胶囊] 录音时长过短 (${duration}ms)，已自动忽略")
            file?.delete()
            updateCapsuleStyle(CapsuleState.ERROR, "⚠️", "时间太短")
            mainHandler.postDelayed({
                updateCapsuleStyle(CapsuleState.IDLE, "🎙️", "按住记账")
            }, 1500)
            return
        }

        if (file == null || !file.exists() || file.length() == 0L) {
            app.addLog("❌ [悬浮胶囊] 录音文件无效")
            updateCapsuleStyle(CapsuleState.ERROR, "❌", "录音无效")
            mainHandler.postDelayed({
                updateCapsuleStyle(CapsuleState.IDLE, "🎙️", "按住记账")
            }, 1500)
            return
        }

        val useAsr = app.prefs.asrModeEnabled
        updateCapsuleStyle(CapsuleState.PROCESSING, "⚡", if (useAsr) "高速转写中..." else "AI解析中...")
        val asrDesc = if (app.prefs.asrProvider == "baidu") "百度语音识别" else "gemini-3.5-transcribe"
        if (app.prefs.aiProvider == "custom" || app.prefs.asrModeEnabled) {
            app.addLog("🤖 [悬浮胶囊] 录音完成 (${duration}ms)，正在调用 $asrDesc 转写...")
        } else {
            app.addLog("🤖 [悬浮胶囊] 录音完成 (${duration}ms)，正在调用 Gemini 进行多模态解析...")
        }

        serviceScope.launch {
            try {
                val bills = withContext(Dispatchers.IO) {
                    app.aiServiceHub.parseAudio(file) { progressMsg ->
                        app.addLog(progressMsg)
                    }
                }

                if (bills.isEmpty()) {
                    app.addLog("⚠️ [悬浮胶囊] 未从语音中识别出有效账单")
                    withContext(Dispatchers.Main) {
                        updateCapsuleStyle(CapsuleState.ERROR, "⚠️", "未识别出金额")
                    }
                } else {
                    val firstBill = bills.first()
                    val summary = "${firstBill.catename ?: "账单"} ¥${firstBill.money}"
                    app.addLog("✨ [悬浮胶囊] 识别成功 (${bills.size}笔): $summary，加入限流队列 (不抢占焦点)...")

                    // 悬浮窗发起的记账：autoReturn 设为 false，钱迹记账后不强行跳回本 App，保留在用户当前宿主应用
                    val billsToEnqueue = bills.map { it.copy(autoReturn = false) }
                    app.queue.enqueueAll(billsToEnqueue)

                    withContext(Dispatchers.Main) {
                        vibrate(35)
                        val text = if (bills.size == 1) "¥${firstBill.money} ${firstBill.catename ?: ""}".trim() else "已记 ${bills.size} 笔"
                        updateCapsuleStyle(CapsuleState.SUCCESS, "✅", text)
                    }
                }
            } catch (e: Exception) {
                app.addLog("❌ [悬浮胶囊] AI解析异常: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    updateCapsuleStyle(CapsuleState.ERROR, "❌", "识别异常")
                }
            } finally {
                file.delete()
                delay(2000)
                withContext(Dispatchers.Main) {
                    if (currentState != CapsuleState.RECORDING) {
                        updateCapsuleStyle(CapsuleState.IDLE, "🎙️", "按住记账")
                    }
                }
            }
        }
    }

    private fun cancelRecording() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}

        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        currentRecordFile?.delete()
        updateCapsuleStyle(CapsuleState.IDLE, "🎙️", "按住记账")
        QianjiApp.instance.addLog("⚠️ [悬浮胶囊] 录音已取消")
    }

    private fun vibrate(ms: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.value = false
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null

        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null

        capsuleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        capsuleView = null
    }

    companion object {
        const val ACTION_STOP = "com.qianji.litebridge.ACTION_STOP_CAPSULE"
        private const val NOTIFICATION_ID = 8849

        val isRunning = mutableStateOf(false)

        fun start(context: Context) {
            val intent = Intent(context, FloatingCapsuleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingCapsuleService::class.java)
            context.stopService(intent)
        }
    }
}
