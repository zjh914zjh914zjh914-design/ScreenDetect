package com.example.screendetect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 全屏悬浮框选层：框外变暗 + 白色 ROI 边框 + 四角手柄。
 * - 拖动框体内部：移动检测区域
 * - 拖右下角：缩放大小
 * - 点"保存"：把区域坐标回调给调用方（自动同步到监控服务并持久化）
 * 坐标系为屏幕像素（悬浮窗全屏，与屏幕物理像素 1:1 对应）。
 */
class RoiOverlayView(
    context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    initialRoi: Rect,
    private val onSave: (Rect) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val roi = Rect(initialRoi)

    private val maskPaint = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val handlePaint = Paint().apply {
        color = Color.rgb(0x1E, 0x88, 0xE5)
        style = Paint.Style.FILL
    }
    private val savePaint = Paint().apply {
        color = Color.rgb(0x1E, 0x88, 0xE5)
        style = Paint.Style.FILL
    }
    private val cancelPaint = Paint().apply {
        color = Color.rgb(0x90, 0x90, 0x90)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }

    private val saveBtn = RectF()
    private val cancelBtn = RectF()

    private enum class Mode { NONE, MOVE, RESIZE, SAVE, CANCEL }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var downRect = Rect()
    private val handleSize = 72f
    private val minSize = 80

    init {
        clampRoi()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = roi.left.toFloat()
        val top = roi.top.toFloat()
        val right = roi.right.toFloat()
        val bottom = roi.bottom.toFloat()

        // 框外暗色遮罩（突出显示框内监控画面）
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), top, maskPaint)
        canvas.drawRect(0f, top, left, bottom, maskPaint)
        canvas.drawRect(right, top, screenWidth.toFloat(), bottom, maskPaint)
        canvas.drawRect(0f, bottom, screenWidth.toFloat(), screenHeight.toFloat(), maskPaint)

        // 白色边框
        canvas.drawRect(left, top, right, bottom, borderPaint)

        // 四角手柄
        val h = handleSize / 2
        canvas.drawRect(left - h, top - h, left + h, top + h, handlePaint)
        canvas.drawRect(right - h, top - h, right + h, top + h, handlePaint)
        canvas.drawRect(left - h, bottom - h, left + h, bottom + h, handlePaint)
        canvas.drawRect(right - h, bottom - h, right + h, bottom + h, handlePaint)

        // 操作提示
        canvas.drawText("拖动移动 · 拖右下角缩放 · 框住监控画面", screenWidth / 2f, 90f, hintPaint)

        // 保存 / 取消按钮（框下方，超出底部时上移）
        val btnW = 240f
        val btnH = 110f
        val gap = 40f
        val cx = screenWidth / 2f
        val btnTop = (bottom + 100f).coerceAtMost(screenHeight - btnH - 30f)
        saveBtn.set(cx - btnW - gap / 2, btnTop, cx - gap / 2, btnTop + btnH)
        cancelBtn.set(cx + gap / 2, btnTop, cx + btnW + gap / 2, btnTop + btnH)

        canvas.drawRoundRect(saveBtn, 20f, 20f, savePaint)
        canvas.drawText("保存", saveBtn.centerX(), saveBtn.centerY() + 14f, textPaint)
        canvas.drawRoundRect(cancelBtn, 20f, 20f, cancelPaint)
        canvas.drawText("取消", cancelBtn.centerX(), cancelBtn.centerY() + 14f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downRect.set(roi)
                mode = when {
                    saveBtn.contains(event.x, event.y) -> Mode.SAVE
                    cancelBtn.contains(event.x, event.y) -> Mode.CANCEL
                    event.x > roi.right - handleSize && event.y > roi.bottom - handleSize -> Mode.RESIZE
                    roi.contains(event.x.toInt(), event.y.toInt()) -> Mode.MOVE
                    else -> Mode.NONE
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                when (mode) {
                    Mode.MOVE -> {
                        val w = downRect.width()
                        val h = downRect.height()
                        val newLeft = (downRect.left + dx).toInt().coerceIn(0, screenWidth - w)
                        val newTop = (downRect.top + dy).toInt().coerceIn(0, screenHeight - h)
                        roi.set(newLeft, newTop, newLeft + w, newTop + h)
                    }
                    Mode.RESIZE -> {
                        roi.right = (downRect.right + dx).toInt().coerceIn(roi.left + minSize, screenWidth)
                        roi.bottom = (downRect.bottom + dy).toInt().coerceIn(roi.top + minSize, screenHeight)
                    }
                    else -> {}
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.SAVE -> onSave(Rect(roi))
                    Mode.CANCEL -> onCancel()
                    else -> {}
                }
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun clampRoi() {
        roi.left = roi.left.coerceIn(0, screenWidth - minSize)
        roi.top = roi.top.coerceIn(0, screenHeight - minSize)
        roi.right = roi.right.coerceIn(minSize, screenWidth)
        roi.bottom = roi.bottom.coerceIn(minSize, screenHeight)
    }
}
