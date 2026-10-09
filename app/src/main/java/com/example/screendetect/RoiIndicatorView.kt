package com.example.screendetect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 监控运行时悬浮显示的检测区域指示窗（不可触摸、不遮挡操作）。
 * 窗口尺寸/位置由 Service 按检测区域设置；报警时闪烁并显示本次数值与阈值，
 * 闪烁 2 秒后自动恢复常态（内部定时重绘，不会卡在红色）。
 */
class RoiIndicatorView(context: Context) : View(context) {

    var threshold = 8000.0
    var lastMotionValue = 0.0
    private var alarmFlashUntil = 0L

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * scaledDensity
        textAlign = Paint.Align.CENTER
    }

    /** 闪烁循环：持续重绘直到闪烁结束，最后一次重绘恢复常态颜色 */
    private val flashRunnable = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() < alarmFlashUntil) {
                invalidate()
                postDelayed(this, 150)
            } else {
                invalidate()
            }
        }
    }

    /** 报警时调用：记录本次数值，闪烁 2 秒展示阈值信息后自动恢复 */
    fun onAlarm(motionValue: Double, threshold: Double) {
        lastMotionValue = motionValue
        this.threshold = threshold
        alarmFlashUntil = System.currentTimeMillis() + 2000
        removeCallbacks(flashRunnable)
        post(flashRunnable)
    }

    /** 阈值变化时刷新显示 */
    fun refresh(threshold: Double) {
        this.threshold = threshold
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(flashRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        val flashing = System.currentTimeMillis() < alarmFlashUntil

        borderPaint.color = if (flashing) Color.parseColor("#FF2D2D") else Color.parseColor("#FFB300")
        fillPaint.color =
            if (flashing) Color.argb(70, 255, 45, 45) else Color.argb(35, 255, 179, 0)

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        canvas.drawRect(0f, 0f, w, h, borderPaint)

        val cx = w / 2f
        val cy = h / 2f
        if (flashing) {
            canvas.drawText("报警！检测到画面变化", cx, cy - 6f, textPaint)
            canvas.drawText(
                "motion=${lastMotionValue.toInt()}  阈值=${threshold.toInt()}",
                cx, cy + 26f, subTextPaint
            )
        } else {
            canvas.drawText("检测区域", cx, cy - 6f, textPaint)
            canvas.drawText("阈值=${threshold.toInt()}", cx, cy + 26f, subTextPaint)
        }
    }
}
