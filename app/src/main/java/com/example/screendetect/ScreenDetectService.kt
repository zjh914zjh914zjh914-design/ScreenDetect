package com.example.screendetect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.media.ImageReader
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 屏幕画面变化检测服务：
 * MediaProjection 持续采集屏幕帧 -> MOG2 背景建模运动检测（限定 ROI 区域） ->
 * 运动面积超过阈值且连续多帧确认 -> 按开关播放报警音 / 震动，
 * 并每秒向 UI 广播实时变化数值（调试用）。
 */
class ScreenDetectService : Service() {

    // ================== ROI 检测区域 ==================
    // 竖屏标准坐标：用户在竖屏（或任意方向）框选保存的权威值，统一换算成竖屏坐标系存储
    private var naturalRoiRect = Rect(100, 200, 800, 600)
    // 当前方向运行坐标：检测与指示窗使用，随屏幕方向自动换算
    private var roiRect = Rect(100, 200, 800, 600)
    private var curScreenW = 0   // 当前方向屏幕宽（采集帧尺寸）
    private var curScreenH = 0
    private var lastRotation = -1

    // ================== 检测参数 ==================
    private var motionAreaThreshold = 8000.0   // 运动像素面积阈值，越小越灵敏
    private val requiredContFrame = 2          // 连续 N 帧检测到运动才报警，过滤噪点
    private val cooldownMs = 3000L             // 报警冷却，避免连续狂响
    private var continuousMotionCount = 0
    private var lastAlarmTs = 0L
    // ================== 检测节奏（App 内可调） ==================
    private var detectIntervalMs = 1000L       // 检测间隔：多久对比一次画面
    private var alarmPauseMs = 10_000L         // 报警后暂停：期间不检测也不报警
    private var lastDetectTs = 0L
    private var monitorPauseUntil = 0L
    private var relearnUntil = 0L   // 报警刚结束后的短暂学习期（期间只更新背景不报警）

    // ================== 提醒设置（App 内可勾选开关） ==================
    private var soundEnabled = true
    private var vibrationEnabled = true

    // ================== 调试数据 ==================
    private var maxMotionValue = 0.0
    private var alarmCount = 0
    private var lastBroadcastTs = 0L

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mog2: BackgroundSubtractorMOG2? = null
    private var handlerThread: HandlerThread? = null
    private var mHandler: Handler? = null
    private var mediaPlayer: MediaPlayer? = null
    // ================== 启动延迟与 ROI 指示窗 ==================
    private var showRoiOverlay = true      // 监控时显示检测区域指示窗（App 内可勾选）
    private var startPending = false       // 是否在 10 秒延迟等待中
    private var roiIndicator: RoiIndicatorView? = null
    private var overlayWindowManager: WindowManager? = null

    companion object {
        const val CHANNEL_ID = "SCREEN_DETECT_CHANNEL"
        const val ACTION_UPDATE_THRESHOLD = "ACTION_UPDATE_THRESHOLD"
        const val ACTION_UPDATE_ROI = "ACTION_UPDATE_ROI"
        const val ACTION_UPDATE_SETTINGS = "ACTION_UPDATE_SETTINGS"
        const val ACTION_MOTION_UPDATE = "ACTION_MOTION_UPDATE"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_ROI_X = "roi_x"
        const val EXTRA_ROI_Y = "roi_y"
        const val EXTRA_ROI_W = "roi_w"
        const val EXTRA_ROI_H = "roi_h"
        const val EXTRA_SOUND = "sound_enabled"
        const val EXTRA_VIBRATION = "vibration_enabled"
        const val EXTRA_SHOW_ROI = "show_roi_overlay"
        const val EXTRA_DETECT_INTERVAL = "detect_interval_ms"
        const val EXTRA_ALARM_PAUSE = "alarm_pause_ms"
        const val START_DELAY_MS = 10_000L   // 启动后延迟 10 秒再开始检测/报警
        const val EXTRA_MOTION_VALUE = "motion_value"
        const val EXTRA_PEAK_VALUE = "peak_value"
        const val EXTRA_ALARM_COUNT = "alarm_count"
        const val PREFS_ROI = "roi_prefs"
        const val PREFS_SETTINGS = "settings_prefs"
        const val PREFS_LOG = "log_prefs"
        const val KEY_LOG = "alarm_log"
        const val KEY_COUNT = "alarm_count"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 读取持久化的提醒设置与检测区域
        val settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        soundEnabled = settings.getBoolean(EXTRA_SOUND, true)
        vibrationEnabled = settings.getBoolean(EXTRA_VIBRATION, true)
        showRoiOverlay = settings.getBoolean(EXTRA_SHOW_ROI, true)
        detectIntervalMs = settings.getInt(EXTRA_DETECT_INTERVAL, 1000).toLong()
        alarmPauseMs = settings.getInt(EXTRA_ALARM_PAUSE, 10000).toLong()

        val prefs = getSharedPreferences(PREFS_ROI, MODE_PRIVATE)
        naturalRoiRect = Rect(
            prefs.getInt("x", naturalRoiRect.x),
            prefs.getInt("y", naturalRoiRect.y),
            prefs.getInt("w", naturalRoiRect.width),
            prefs.getInt("h", naturalRoiRect.height)
        )
        roiRect = Rect(naturalRoiRect.x, naturalRoiRect.y, naturalRoiRect.width, naturalRoiRect.height)

        // MOG2 初始化：必须先显式加载 OpenCV native 库（不加载会闪退），失败则降级为不检测
        mog2 = try {
            if (OpenCVLoader.initLocal()) {
                Video.createBackgroundSubtractorMOG2(500, 16.0, true)
            } else {
                null
            }
        } catch (e: Throwable) {
            null
        }

        handlerThread = HandlerThread("DetectThread")
        handlerThread?.start()
        mHandler = Handler(handlerThread!!.looper)

        mediaPlayer = MediaPlayer.create(this, R.raw.alert)
        mediaPlayer?.isLooping = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        when (i.action) {
            // 运行时从 UI 实时更新灵敏度阈值
            ACTION_UPDATE_THRESHOLD -> {
                motionAreaThreshold = i.getDoubleExtra(EXTRA_THRESHOLD, motionAreaThreshold)
                roiIndicator?.refresh(motionAreaThreshold)
                return START_NOT_STICKY
            }
            // 运行时从 UI 实时更新检测区域（App 内框选保存后触发，坐标为竖屏标准坐标）
            ACTION_UPDATE_ROI -> {
                naturalRoiRect = Rect(
                    i.getIntExtra(EXTRA_ROI_X, naturalRoiRect.x),
                    i.getIntExtra(EXTRA_ROI_Y, naturalRoiRect.y),
                    i.getIntExtra(EXTRA_ROI_W, naturalRoiRect.width),
                    i.getIntExtra(EXTRA_ROI_H, naturalRoiRect.height)
                )
                refreshCurrentRoi()
                roiIndicator?.refresh(motionAreaThreshold)
                return START_NOT_STICKY
            }
            // 运行时从 UI 实时更新声音/震动/区域显示/检测节奏开关
            ACTION_UPDATE_SETTINGS -> {
                soundEnabled = i.getBooleanExtra(EXTRA_SOUND, soundEnabled)
                vibrationEnabled = i.getBooleanExtra(EXTRA_VIBRATION, vibrationEnabled)
                showRoiOverlay = i.getBooleanExtra(EXTRA_SHOW_ROI, showRoiOverlay)
                detectIntervalMs = i.getIntExtra(EXTRA_DETECT_INTERVAL, detectIntervalMs.toInt()).toLong()
                alarmPauseMs = i.getIntExtra(EXTRA_ALARM_PAUSE, alarmPauseMs.toInt()).toLong()
                if (showRoiOverlay) showRoiWindow() else hideRoiWindow()
                return START_NOT_STICKY
            }
        }

        // 正式启动监控（带屏幕捕获授权结果）
        val resultCode = i.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = i.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultData == null) return START_NOT_STICKY

        motionAreaThreshold = i.getDoubleExtra(EXTRA_THRESHOLD, 8000.0)

        // 前台服务：常驻通知栏，后台继续运行（必须先于媒体投影会话建立）
        startForeground(1001, buildNotification("准备中：10 秒后开始检测"))

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(
            object : MediaProjection.Callback() {},
            Handler(Looper.getMainLooper())
        )

        // 延迟 10 秒再开始采集与检测，给用户时间切到目标画面（期间不报警）
        startPending = true
        mHandler?.postDelayed({
            startPending = false
            beginCapture()
        }, START_DELAY_MS)
        return START_STICKY
    }

    /** 延迟结束后正式建立屏幕采集与检测链路 */
    private fun beginCapture() {
        if (mediaProjection == null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        @Suppress("DEPRECATION")
        lastRotation = wm.defaultDisplay.rotation
        curScreenW = metrics.widthPixels
        curScreenH = metrics.heightPixels
        refreshCurrentRoi()
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            image?.let { img ->
                mHandler?.post {
                    try {
                        processFrame(img)
                    } catch (e: Throwable) {
                        // 单帧处理失败不中断监控（Throwable 兜底，含 native 库未加载等 Error）
                    } finally {
                        img.close()
                    }
                }
            }
        }, mHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenDetectDisplay",
            screenW, screenH, metrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        showRoiWindow()
        // 通知切换为检测中状态
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1001, buildNotification())
    }

    /** 显示检测区域指示窗（局部窗口：只覆盖检测区域，不挡屏幕操作；需悬浮窗权限，失败静默跳过） */
    private fun showRoiWindow() {
        if (!showRoiOverlay || !Settings.canDrawOverlays(this)) return
        try {
            if (roiIndicator == null) {
                overlayWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val view = RoiIndicatorView(this)
                overlayWindowManager?.addView(view, buildRoiParams())
                roiIndicator = view
            }
            roiIndicator?.refresh(motionAreaThreshold)
        } catch (e: Throwable) {
            // 悬浮窗权限异常时静默跳过，不影响监控
        }
    }

    /** 按当前检测区域生成指示窗布局参数（小窗口 + 不可触摸 + 不抢焦点，触摸完全穿透） */
    private fun buildRoiParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            roiRect.width,
            roiRect.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            x = roiRect.x
            y = roiRect.y
            gravity = Gravity.TOP or Gravity.START
        }

    /** 检测区域变化时让指示窗跟随移动/缩放 */
    private fun updateRoiWindow() {
        try {
            roiIndicator?.let { view ->
                overlayWindowManager?.updateViewLayout(view, buildRoiParams())
            }
        } catch (e: Throwable) {
            // 更新失败不影响监控
        }
    }

    /** 移除检测区域指示窗 */
    private fun hideRoiWindow() {
        try {
            roiIndicator?.let { overlayWindowManager?.removeView(it) }
        } catch (e: Throwable) {
            // 已移除时忽略
        }
        roiIndicator = null
    }

    /** 按当前屏幕方向把竖屏标准区域换算成运行坐标，并让指示窗跟随移动 */
    private fun refreshCurrentRoi() {
        if (curScreenW <= 0 || curScreenH <= 0) {
            roiRect = Rect(naturalRoiRect.x, naturalRoiRect.y, naturalRoiRect.width, naturalRoiRect.height)
            return
        }
        val rot = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        lastRotation = rot
        roiRect = mapNaturalToCurrent(naturalRoiRect, rot, curScreenW, curScreenH)
        updateRoiWindow()
    }

    /** 竖屏标准坐标 -> 当前方向坐标（OpenCV Rect 参数为 x/y/宽/高；curW 为当前方向宽，横屏时等于竖屏高） */
    private fun mapNaturalToCurrent(roi: Rect, rotation: Int, curW: Int, curH: Int): Rect = when (rotation) {
        Surface.ROTATION_90 -> Rect(roi.y, curW - roi.x - roi.width, roi.height, roi.width)
        Surface.ROTATION_180 -> Rect(curW - roi.x - roi.width, curH - roi.y - roi.height, roi.width, roi.height)
        Surface.ROTATION_270 -> Rect(curW - roi.y - roi.height, roi.x, roi.height, roi.width)
        else -> Rect(roi.x, roi.y, roi.width, roi.height)
    }

    private fun processFrame(image: android.media.Image) {
        // 检测节奏控制：未到检测间隔且不在暂停期则完全跳过；
        // 处于报警后暂停期时仍需处理本帧（用于持续更新背景模型，见下方分支）
        val frameNow = System.currentTimeMillis()
        if (frameNow >= monitorPauseUntil && frameNow - lastDetectTs < detectIntervalMs) return

        // 屏幕方向变化时自动换算检测区域并移动指示窗（跟随同一物理位置）
        val rot = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        if (rot != lastRotation) refreshCurrentRoi()

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val w = image.width
        val h = image.height

        // 逐行拷贝（处理 rowStride padding），按"行起始偏移是否有足够数据"判断，
        // 避免最后一行 padding 导致越界/整帧跳过
        val data = ByteArray(w * h * pixelStride)
        var offset = 0
        var copiedRows = 0
        for (row in 0 until h) {
            val rowStart = row * rowStride
            if (buffer.limit() - rowStart < w * pixelStride) break
            buffer.position(rowStart)
            buffer.get(data, offset, w * pixelStride)
            offset += w * pixelStride
            copiedRows++
        }
        if (copiedRows < h) return // 帧数据不完整，跳过本帧

        val mat = Mat(h, w, CvType.CV_8UC4)
        mat.put(0, 0, data)

        // 直接使用采集帧方向检测（与框选/指示窗同坐标系，横竖屏均一致）
        // ROI 边界保护（防止矩形超出画面导致崩溃）
        val x = roiRect.x.coerceIn(0, mat.cols() - 1)
        val y = roiRect.y.coerceIn(0, mat.rows() - 1)
        val rw = roiRect.width.coerceAtMost(mat.cols() - x)
        val rh = roiRect.height.coerceAtMost(mat.rows() - y)
        val safeRoi = Rect(x, y, rw, rh)

        val roiMat = mat.submat(safeRoi)
        val gray = Mat()
        Imgproc.cvtColor(roiMat, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        // 报警后暂停期内：不检测不报警，但持续把最新帧喂给 MOG2 更新背景模型。
        // 若不更新，暂停期间背景被"冻结"，恢复后静止画面与旧背景对比会一直被误判为变化而连环报警
        if (frameNow < monitorPauseUntil) {
            val fg = Mat()
            mog2?.apply(gray, fg)
            fg.release()
            mat.release()
            roiMat.release()
            gray.release()
            return
        }
        lastDetectTs = frameNow

        // MOG2 前景掩码
        val fgMask = Mat()
        mog2?.apply(gray, fgMask)

        // 形态学去噪：开运算去孤立噪点，闭运算填充运动区域空洞
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(fgMask, fgMask, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(fgMask, fgMask, Imgproc.MORPH_CLOSE, kernel)

        // Scalar.val 是 Kotlin 关键字，需用反引号访问
        val motionValue = Core.sumElems(fgMask).`val`[0]

        // 统计本轮峰值（调试用）
        if (motionValue > maxMotionValue) maxMotionValue = motionValue

        if (motionValue > motionAreaThreshold) {
            // 报警刚结束后的短暂学习期内只更新背景不报警，彻底消化残余前景，避免连环误报
            if (frameNow < relearnUntil) {
                continuousMotionCount = 0
            } else {
                continuousMotionCount++
                if (continuousMotionCount >= requiredContFrame) {
                    val now = System.currentTimeMillis()
                    if (now - lastAlarmTs > cooldownMs) {
                        triggerAlarm(motionValue)
                        lastAlarmTs = now
                    }
                    continuousMotionCount = 0
                }
            }
        } else {
            continuousMotionCount = 0
        }

        // 每秒向 UI 广播一次实时数值（调阈值/调试用）
        val now = System.currentTimeMillis()
        if (now - lastBroadcastTs >= 1000) {
            lastBroadcastTs = now
            sendBroadcast(
                Intent(ACTION_MOTION_UPDATE)
                    .setPackage(packageName)
                    .putExtra(EXTRA_MOTION_VALUE, motionValue)
                    .putExtra(EXTRA_THRESHOLD, motionAreaThreshold)
                    .putExtra(EXTRA_PEAK_VALUE, maxMotionValue)
                    .putExtra(EXTRA_ALARM_COUNT, alarmCount)
            )
        }

        mat.release()
        roiMat.release()
        gray.release()
        fgMask.release()
    }

    private fun triggerAlarm(motionValue: Double) {
        alarmCount++
        appendLog(motionValue)
        // 检测区域指示窗报警闪烁，显示本次数值与阈值
        roiIndicator?.onAlarm(motionValue, motionAreaThreshold)
        // 报警后暂停监测一段时间（期间不检测也不报警，持续更新背景），秒数可在 App 内调节
        monitorPauseUntil = System.currentTimeMillis() + alarmPauseMs
        // 暂停结束后 3 秒内只学习背景不报警，防止恢复瞬间残余前景导致连环误报
        relearnUntil = System.currentTimeMillis() + 3000L

        if (soundEnabled) {
            mHandler?.post {
                mediaPlayer?.seekTo(0)
                mediaPlayer?.start()
            }
        }
        if (vibrationEnabled) {
            vibrate()
        }
    }

    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(800)
                }
            }
        } catch (e: Throwable) {
            // 个别机型无震动器或权限异常，忽略
        }
    }

    /** 记录报警时间与变化数值（调试用），保留最近 20 条 */
    private fun appendLog(motionValue: Double) {
        try {
            val prefs = getSharedPreferences(PREFS_LOG, MODE_PRIVATE)
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "$time  motion=$motionValue"
            val old = prefs.getString(KEY_LOG, "") ?: ""
            val list = (old.split("\n") + entry).filter { it.isNotBlank() }.takeLast(20)
            prefs.edit()
                .putString(KEY_LOG, list.joinToString("\n"))
                .putInt(KEY_COUNT, prefs.getInt(KEY_COUNT, 0) + 1)
                .apply()
        } catch (e: Throwable) {
            // 日志写入失败不影响监控
        }
    }

    private fun buildNotification(text: String = "画面监控运行中"): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("画面监控运行中")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "画面检测服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消未执行的延迟启动任务
        if (startPending) {
            mHandler?.removeCallbacksAndMessages(null)
        }
        hideRoiWindow()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        // MOG2 由 OpenCV native 管理，无需显式 release
        mediaPlayer?.release()
        handlerThread?.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
