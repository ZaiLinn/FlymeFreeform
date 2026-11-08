package io.github.zailinn.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 「更多」面板右侧的字母索引条，铺满面板卡片，只在右侧竖条内接收触摸，其余触摸交给下层列表。
 *
 * 按住滑动时每换一个字母回调 [onLetter] 并轻震一次，面板中央浮出当前字母。
 */
@SuppressLint("ViewConstructor")
internal class PanelIndexBar(
    context: Context,
    private val textColor: Int,
    private val onLetter: (Char) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val stripWidth = 22f * density
    private val touchWidth = 32f * density
    private val endPadding = 2f * density
    private val bubbleRadius = 30f * density
    private val letterPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 10f * resources.displayMetrics.scaledDensity
            typeface = Typeface.DEFAULT_BOLD
        }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BUBBLE_COLOR }
    private val bubbleTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 28f * resources.displayMetrics.scaledDensity
            typeface = Typeface.DEFAULT_BOLD
            color = 0xFFFFFFFF.toInt()
        }

    private var available: Set<Char> = emptySet()
    private var stripTop = 0f
    private var stripBottom = 0f
    private var active = false
    private var current: Char? = null

    init {
        visibility = GONE
    }

    /** 显示索引条；[top]/[bottom] 是列表在本视图坐标里的上下边界，字母均分其间。 */
    fun show(letters: Set<Char>, top: Int, bottom: Int) {
        available = letters
        stripTop = top.toFloat()
        stripBottom = bottom.toFloat()
        visibility = if (letters.isEmpty() || bottom - top < PanelAlphabet.LETTERS.size) GONE else VISIBLE
        invalidate()
    }

    fun hide() {
        active = false
        current = null
        visibility = GONE
    }

    private fun stripLeft(): Float = width - endPadding - stripWidth

    private fun slotHeight(): Float = (stripBottom - stripTop) / PanelAlphabet.LETTERS.size

    override fun onDraw(canvas: Canvas) {
        val slot = slotHeight()
        if (slot <= 0f) return
        val centerX = stripLeft() + stripWidth / 2f
        val baseline = (slot - letterPaint.descent() - letterPaint.ascent()) / 2f
        PanelAlphabet.LETTERS.forEachIndexed { index, letter ->
            val alpha = if (letter in available) 0xFF else 0x4D
            letterPaint.color = (textColor and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawText(letter.toString(), centerX, stripTop + slot * index + baseline, letterPaint)
        }
        val shown = current
        if (active && shown != null) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, bubbleRadius, bubblePaint)
            val textY = cy - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f
            canvas.drawText(shown.toString(), cx, textY, bubbleTextPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x < width - touchWidth || event.y < stripTop || event.y > stripBottom) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                active = true
                select(event.y)
            }
            MotionEvent.ACTION_MOVE -> if (active) select(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                current = null
                invalidate()
            }
        }
        return active || event.actionMasked == MotionEvent.ACTION_UP
    }

    private fun select(y: Float) {
        val slot = slotHeight()
        if (slot <= 0f) return
        val index = ((y - stripTop) / slot).toInt().coerceIn(0, PanelAlphabet.LETTERS.lastIndex)
        val letter = PanelAlphabet.LETTERS[index]
        if (letter == current) return
        current = letter
        performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        onLetter(letter)
        invalidate()
    }

    private companion object {
        const val BUBBLE_COLOR = 0xCC3C3C3C.toInt()
    }
}
