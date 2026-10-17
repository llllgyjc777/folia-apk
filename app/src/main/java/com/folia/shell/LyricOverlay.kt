package com.folia.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * 锁屏歌词悬浮窗
 *
 * 为什么必须这么做：
 *   锁屏那张播放卡片是 SystemUI 画的，我们只能往里塞 metadata（歌名/歌手），
 *   塞不进自定义 View，所以「卡片内部」做不出歌词动画。
 *   唯一能做真·歌词动画的位置，是在锁屏之上叠一层系统悬浮窗。
 *
 * 动画实现：
 *   逐字高亮（Karaoke 效果）——按播放进度把整行歌词从左到右染色的边界算出来，
 *   用 clipRect 把「已唱部分」画成高亮色，其余画成暗色，形成逐字推进效果。
 *   每帧只重绘，不做逐字动画对象，性能稳。
 */
class LyricOverlay(private val context: Context) {

    companion object {
        /** 悬浮窗宽高（dp） */
        private const val OVERLAY_HEIGHT_DP = 120
        private const val LINE_SPACING_DP = 46
        private const val TEXT_SIZE_SP = 17f
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View? = null
    private var lyricView: LyricView? = null
    private var added = false

    /** 当前这一句歌词 */
    @Volatile
    private var currentLine: String = ""

    /** 上一句（用于上方淡出） */
    @Volatile
    private var prevLine: String = ""

    /** 下一句（用于下方预显） */
    @Volatile
    private var nextLine: String = ""

    /** 当前行已播进度 0f..1f，用于逐字染色 */
    @Volatile
    private var lineProgress: Float = 0f

    @Volatile
    private var visible = false

    private val main = Handler(Looper.getMainLooper())

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    // ---------- 绘制 ----------

    private inner class LyricView(ctx: Context) : View(ctx) {

        private val sungPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = TEXT_SIZE_SP * resources.displayMetrics.scaledDensity
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private val unsungPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = TEXT_SIZE_SP * resources.displayMetrics.scaledDensity
            color = 0x80FFFFFF.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private val ghostPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = TEXT_SIZE_SP * resources.displayMetrics.scaledDensity
            color = 0x4DFFFFFF
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f

            // 上一句：上方，更淡更小
            if (prevLine.isNotEmpty()) {
                canvas.drawText(
                    prevLine, cx, cy - dp(LINE_SPACING_DP),
                    ghostPaint
                )
            }

            // 下一句：下方，淡
            if (nextLine.isNotEmpty()) {
                canvas.drawText(
                    nextLine, cx, cy + dp(LINE_SPACING_DP),
                    ghostPaint
                )
            }

            // 当前句：逐字高亮
            if (currentLine.isNotEmpty()) {
                val textWidth = unsungPaint.measureText(currentLine)
                val left = cx - textWidth / 2f
                val baseline = cy + abs(unsungPaint.ascent()) / 2.6f

                // 先画整句暗色（未唱）
                canvas.drawText(currentLine, cx, baseline, unsungPaint)

                // 再按进度裁剪，画出「已唱」的亮色部分
                val sungW = textWidth * lineProgress.coerceIn(0f, 1f)
                if (sungW > 0f) {
                    canvas.save()
                    canvas.clipRect(left, 0f, left + sungW, height.toFloat())
                    canvas.drawText(currentLine, cx, baseline, sungPaint)
                    canvas.restore()
                }
            }
        }
    }

    // ---------- 窗口管理 ----------

    private fun buildParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(OVERLAY_HEIGHT_DP),
            type,
            // 关键 flag：锁屏也能显示、不抢焦点、点击穿透
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // 压在锁屏播放卡片下方约 1/3 屏处
            y = dp(300)
        }
    }

    fun show() {
        main.post {
            if (added) return@post
            try {
                val v = LyricView(context)
                lyricView = v
                wm.addView(v, buildParams())
                rootView = v
                added = true
                visible = true
                startRenderLoop()
            } catch (t: Throwable) {
                android.util.Log.d("FoliaBridge", "歌词悬浮窗添加失败: ${t.message}")
                added = false
            }
        }
    }

    fun hide() {
        main.post {
            if (!added) return@post
            try {
                rootView?.let { wm.removeView(it) }
            } catch (_: Throwable) {
            }
            rootView = null
            lyricView = null
            added = false
            visible = false
        }
    }

    fun isShowing(): Boolean = added

    /** 更新歌词文本与行内进度，由 PlaybackService 每次状态上报时调用 */
    fun updateLyric(line: String, progress: Float) {
        if (line != currentLine) {
            prevLine = currentLine
            currentLine = line
            lineProgress = progress
            invalidateNow()
        } else if (abs(progress - lineProgress) > 0.02f) {
            lineProgress = progress
            invalidateNow()
        }
    }

    fun updateNext(next: String) {
        if (next != nextLine) {
            nextLine = next
            invalidateNow()
        }
    }

    private fun invalidateNow() {
        main.post { lyricView?.invalidate() }
    }

    /** 60fps 重绘循环，保证进度推进顺滑（没有它只在数据变化时才重绘，会卡顿） */
    private fun startRenderLoop() {
        val tick = object : Runnable {
            override fun run() {
                if (!added) return
                lyricView?.invalidate()
                main.postDelayed(this, 33)
            }
        }
        main.postDelayed(tick, 33)
    }
}