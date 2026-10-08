package com.example.screendetect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * 监控运行时悬浮显示的检测区域指示窗（不可触摸、不遮挡操作）。
 * 平时显示区域边框与当前阈值；报警时闪烁并显示本次变化数值与阈值。
 */
class RoiIndicatorView(context: Context) : View(context) {

    var roiRect = Rect(100, 200, 800, 600)
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

    /** 报警时调用：记录本次数值，闪烁 2 秒展示阈值信息 */
    fun onAlarm(motionValue: Double, threshold: Double) {
        lastMotionValue = motionValue
        this.threshold = threshold
        alarmFlashUntil = System.currentTimeMillis() + 2000
        invalidate()
    }

    /** ROI / 阈值变化时刷新显示 */
    fun refresh(roi: Rect, threshold: Double) {
        roiRect = roi
        this.threshold = threshold
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val flashing = System.currentTimeMillis() < alarmFlashUntil

        borderPaint.color = if (flashing) Color.parseColor("#FF2D2D") else Color.parseColor("#FFB300")
        fillPaint.color =
            if (flashing) Color.argb(70, 255, 45, 45) else Color.argb(35, 255, 179, 0)

        val r = roiRect
        canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), fillPaint)
        canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), borderPaint)

        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
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
