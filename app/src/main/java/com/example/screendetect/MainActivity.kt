package com.example.screendetect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val requestScreenCapture = 1001
    private var currentThreshold = 8000.0
    private var roiOverlay: View? = null

    private lateinit var tvMotionValue: TextView
    private lateinit var tvLog: TextView
    private lateinit var cbSound: CheckBox
    private lateinit var cbVibration: CheckBox
    private lateinit var cbShowRoi: CheckBox

    /** 接收 Service 每秒广播的实时变化数值，刷新调试面板 */
    private val motionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenDetectService.ACTION_MOTION_UPDATE) return
            val value = intent.getDoubleExtra(ScreenDetectService.EXTRA_MOTION_VALUE, 0.0)
            val threshold = intent.getDoubleExtra(ScreenDetectService.EXTRA_THRESHOLD, 0.0)
            val peak = intent.getDoubleExtra(ScreenDetectService.EXTRA_PEAK_VALUE, 0.0)
            val count = intent.getIntExtra(ScreenDetectService.EXTRA_ALARM_COUNT, 0)
            tvMotionValue.text =
                "实时变化值：%.0f\n当前阈值：%.0f（峰值：%.0f）\n报警次数：%d"
                    .format(value, threshold, peak, count)
            refreshLog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        tvMotionValue = findViewById(R.id.tvMotionValue)
        tvLog = findViewById(R.id.tvLog)
        cbSound = findViewById(R.id.cbSound)
        cbVibration = findViewById(R.id.cbVibration)
        cbShowRoi = findViewById(R.id.cbShowRoi)
        val tvSensitivity = findViewById<TextView>(R.id.tvSensitivity)
        val seekSensitivity = findViewById<SeekBar>(R.id.seekSensitivity)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnSetRoi = findViewById<Button>(R.id.btnSetRoi)

        tvSensitivity.text = "灵敏度阈值：${seekSensitivity.progress}"

        // 读取持久化的声音/震动开关
        val settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        cbSound.isChecked = settings.getBoolean(ScreenDetectService.EXTRA_SOUND, true)
        cbVibration.isChecked = settings.getBoolean(ScreenDetectService.EXTRA_VIBRATION, true)
        cbShowRoi.isChecked = settings.getBoolean(ScreenDetectService.EXTRA_SHOW_ROI, true)
        refreshLog()

        cbSound.setOnCheckedChangeListener { _, checked ->
            settings.edit().putBoolean(ScreenDetectService.EXTRA_SOUND, checked).apply()
            sendSettings()
        }
        cbVibration.setOnCheckedChangeListener { _, checked ->
            settings.edit().putBoolean(ScreenDetectService.EXTRA_VIBRATION, checked).apply()
            sendSettings()
        }
        cbShowRoi.setOnCheckedChangeListener { _, checked ->
            settings.edit().putBoolean(ScreenDetectService.EXTRA_SHOW_ROI, checked).apply()
            sendSettings()
        }

        // 灵敏度滑块：监控运行中拖动也会实时生效
        seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentThreshold = progress.toDouble()
                tvSensitivity.text = "灵敏度阈值：$progress"
                val intent = Intent(this@MainActivity, ScreenDetectService::class.java).apply {
                    action = ScreenDetectService.ACTION_UPDATE_THRESHOLD
                    putExtra(ScreenDetectService.EXTRA_THRESHOLD, currentThreshold)
                }
                startService(intent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnStart.setOnClickListener {
            // 弹出系统录屏授权弹窗，每次启动监控会话都需要手动允许
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            startActivityForResult(captureIntent, requestScreenCapture)
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ScreenDetectService::class.java))
        }

        btnSetRoi.setOnClickListener {
            // Android 6+ 悬浮窗需要单独授权
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先允许「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                showRoiOverlay()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ScreenDetectService.ACTION_MOTION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(motionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(motionReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(motionReceiver)
        } catch (e: IllegalArgumentException) {
            // 未注册时忽略
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 防止 Activity 销毁时悬浮窗泄漏
        roiOverlay?.let { overlay ->
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(overlay)
            } catch (e: Exception) {
                // 已移除时忽略
            }
            roiOverlay = null
        }
    }

    /** 把声音/震动/区域显示开关实时同步给监控服务 */
    private fun sendSettings() {
        val intent = Intent(this, ScreenDetectService::class.java).apply {
            action = ScreenDetectService.ACTION_UPDATE_SETTINGS
            putExtra(ScreenDetectService.EXTRA_SOUND, cbSound.isChecked)
            putExtra(ScreenDetectService.EXTRA_VIBRATION, cbVibration.isChecked)
            putExtra(ScreenDetectService.EXTRA_SHOW_ROI, cbShowRoi.isChecked)
        }
        startService(intent)
    }

    /** 显示最近报警记录 */
    private fun refreshLog() {
        val prefs = getSharedPreferences(PREFS_LOG, MODE_PRIVATE)
        val log = prefs.getString(ScreenDetectService.KEY_LOG, "") ?: ""
        val count = prefs.getInt(ScreenDetectService.KEY_COUNT, 0)
        tvLog.text = if (log.isBlank()) "暂无报警记录" else "报警记录（共 $count 次）：\n$log"
    }

    /** 弹出全屏悬浮框选层，让用户拖动选择检测区域 */
    private fun showRoiOverlay() {
        if (roiOverlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics

        // 读取上次保存的区域作为初始位置
        val prefs = getSharedPreferences(PREFS_ROI, MODE_PRIVATE)
        val initial = Rect(
            prefs.getInt("x", metrics.widthPixels / 4),
            prefs.getInt("y", metrics.heightPixels / 4),
            prefs.getInt("x", metrics.widthPixels / 4) + prefs.getInt("w", metrics.widthPixels / 2),
            prefs.getInt("y", metrics.heightPixels / 4) + prefs.getInt("h", metrics.heightPixels / 2)
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        val overlay = RoiOverlayView(
            this,
            metrics.widthPixels,
            metrics.heightPixels,
            initial,
            onSave = { rect ->
                saveRoiToPrefs(rect)
                sendRoiToService(rect)
                roiOverlay?.let { wm.removeView(it) }
                roiOverlay = null
                Toast.makeText(this, "检测区域已更新", Toast.LENGTH_SHORT).show()
            },
            onCancel = {
                roiOverlay?.let { wm.removeView(it) }
                roiOverlay = null
            }
        )
        wm.addView(overlay, params)
        roiOverlay = overlay
    }

    private fun saveRoiToPrefs(rect: Rect) {
        getSharedPreferences(PREFS_ROI, MODE_PRIVATE).edit()
            .putInt("x", rect.left)
            .putInt("y", rect.top)
            .putInt("w", rect.width())
            .putInt("h", rect.height())
            .apply()
    }

    /** 实时把新区域同步给正在运行的监控服务 */
    private fun sendRoiToService(rect: Rect) {
        val intent = Intent(this, ScreenDetectService::class.java).apply {
            action = ScreenDetectService.ACTION_UPDATE_ROI
            putExtra(ScreenDetectService.EXTRA_ROI_X, rect.left)
            putExtra(ScreenDetectService.EXTRA_ROI_Y, rect.top)
            putExtra(ScreenDetectService.EXTRA_ROI_W, rect.width())
            putExtra(ScreenDetectService.EXTRA_ROI_H, rect.height())
        }
        startService(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestScreenCapture) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, ScreenDetectService::class.java).apply {
                    putExtra(ScreenDetectService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenDetectService.EXTRA_RESULT_DATA, data)
                    putExtra(ScreenDetectService.EXTRA_THRESHOLD, currentThreshold)
                }
                startForegroundService(serviceIntent)
            }
        }
    }

    companion object {
        const val PREFS_ROI = "roi_prefs"
        const val PREFS_SETTINGS = "settings_prefs"
        const val PREFS_LOG = "log_prefs"
    }
}
