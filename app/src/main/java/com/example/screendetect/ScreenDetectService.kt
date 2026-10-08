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
import android.util.DisplayMetrics
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

    // ================== ROI 检测区域（屏幕坐标，左上角为原点） ==================
    // 默认值仅用于首次运行；之后可在 App 内点「设置检测区域」框选并自动保存
    private var roiRect = Rect(100, 200, 800, 600)

    // ================== 检测参数 ==================
    private var motionAreaThreshold = 8000.0   // 运动像素面积阈值，越小越灵敏
    private val requiredContFrame = 2          // 连续 N 帧检测到运动才报警，过滤噪点
    private val cooldownMs = 3000L             // 报警冷却，避免连续狂响
    private var continuousMotionCount = 0
    private var lastAlarmTs = 0L

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
    private var rotation = 0

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

        val prefs = getSharedPreferences(PREFS_ROI, MODE_PRIVATE)
        roiRect = Rect(
            prefs.getInt("x", roiRect.x),
            prefs.getInt("y", roiRect.y),
            prefs.getInt("w", roiRect.width),
            prefs.getInt("h", roiRect.height)
        )

        // MOG2 初始化（native 库加载失败时降级为不检测，避免崩溃）
        mog2 = try {
            Video.createBackgroundSubtractorMOG2(500, 16.0, true)
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
                return START_NOT_STICKY
            }
            // 运行时从 UI 实时更新检测区域（App 内框选保存后触发）
            ACTION_UPDATE_ROI -> {
                roiRect = Rect(
                    i.getIntExtra(EXTRA_ROI_X, roiRect.x),
                    i.getIntExtra(EXTRA_ROI_Y, roiRect.y),
                    i.getIntExtra(EXTRA_ROI_W, roiRect.width),
                    i.getIntExtra(EXTRA_ROI_H, roiRect.height)
                )
                return START_NOT_STICKY
            }
            // 运行时从 UI 实时更新声音/震动开关
            ACTION_UPDATE_SETTINGS -> {
                soundEnabled = i.getBooleanExtra(EXTRA_SOUND, soundEnabled)
                vibrationEnabled = i.getBooleanExtra(EXTRA_VIBRATION, vibrationEnabled)
                return START_NOT_STICKY
            }
        }

        // 正式启动监控（带屏幕捕获授权结果）
        val resultCode = i.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = i.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultData == null) return START_NOT_STICKY

        motionAreaThreshold = i.getDoubleExtra(EXTRA_THRESHOLD, 8000.0)

        // 前台服务：常驻通知栏，后台继续运行（必须先于媒体投影会话建立）
        startForeground(1001, buildNotification())

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(
            object : MediaProjection.Callback() {},
            Handler(Looper.getMainLooper())
        )

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getMetrics(metrics)
        rotation = wm.defaultDisplay.rotation
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            image?.let { img ->
                mHandler?.post {
                    try {
                        processFrame(img)
                    } catch (e: Exception) {
                        // 单帧处理失败不中断监控
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
        return START_STICKY
    }

    private fun processFrame(image: android.media.Image) {
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

        // 按屏幕方向旋转到自然方向（横屏使用不会错位）
        val rotated = Mat()
        when (rotation) {
            Surface.ROTATION_90 -> Core.rotate(mat, rotated, Core.ROTATE_90_CLOCKWISE)
            Surface.ROTATION_180 -> Core.rotate(mat, rotated, Core.ROTATE_180)
            Surface.ROTATION_270 -> Core.rotate(mat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> mat.copyTo(rotated)
        }
        if (rotated.empty()) {
            mat.release()
            rotated.release()
            return
        }

        // ROI 边界保护（防止矩形超出画面导致崩溃）
        val x = roiRect.x.coerceIn(0, rotated.cols() - 1)
        val y = roiRect.y.coerceIn(0, rotated.rows() - 1)
        val rw = roiRect.width.coerceAtMost(rotated.cols() - x)
        val rh = roiRect.height.coerceAtMost(rotated.rows() - y)
        val safeRoi = Rect(x, y, rw, rh)

        val roiMat = rotated.submat(safeRoi)
        val gray = Mat()
        Imgproc.cvtColor(roiMat, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

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
            continuousMotionCount++
            if (continuousMotionCount >= requiredContFrame) {
                val now = System.currentTimeMillis()
                if (now - lastAlarmTs > cooldownMs) {
                    triggerAlarm(motionValue)
                    lastAlarmTs = now
                }
                continuousMotionCount = 0
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
        rotated.release()
        roiMat.release()
        gray.release()
        fgMask.release()
    }

    private fun triggerAlarm(motionValue: Double) {
        alarmCount++
        appendLog(motionValue)

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

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("画面监控运行中")
            .setContentText("MOG2 ROI 运动检测已开启")
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
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        // MOG2 由 OpenCV native 管理，无需显式 release
        mediaPlayer?.release()
        handlerThread?.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
