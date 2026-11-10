package com.autoclicker.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.autoclicker.app.data.ConfigStore
import com.autoclicker.app.data.Logs
import com.autoclicker.app.service.ClickAccessibilityService
import com.autoclicker.app.service.ClickEngine
import com.autoclicker.app.ui.col
import com.autoclicker.app.ui.dp

/**
 * 悬浮控制面板：拖动移动，▶/❚❚ 启停，■ 停止，＋ 取点，✕ 关闭。
 * 使用 TYPE_ACCESSIBILITY_OVERLAY，因此不需要「显示在其他应用上层」权限。
 */
class ControlPanel(private val svc: ClickAccessibilityService) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private var btnPlay: TextView? = null
    private var info: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderOnly()
            val st = svc.engine.state
            if (st == ClickEngine.State.RUNNING || st == ClickEngine.State.COUNTDOWN) {
                handler.postDelayed(this, 500)
            }
        }
    }

    fun show() {
        if (root != null) return
        build()
        runCatching { wm.addView(root, params) }
            .onFailure { Logs.add("悬浮窗添加失败：${it.message}") }
        refresh()
        handler.postDelayed(ticker, 500)
    }

    val isShown: Boolean get() = root != null

    fun hide() {
        handler.removeCallbacks(ticker)
        val r = root ?: return
        // 只有真正移除成功才丢引用。否则窗口会变成没人引用的孤儿，永远留在屏幕上，
        // 之后任何 hidePanel() 都找不到它。
        val removed = runCatching { wm.removeView(r) }.isSuccess ||
            runCatching { wm.removeViewImmediate(r) }.isSuccess
        if (removed) {
            root = null
            params = null
            btnPlay = null
            info = null
        }
    }

    private fun build() {
        val saved = ConfigStore.panelPos(svc)
        val metrics = svc.resources.displayMetrics
        val fresh = saved.first < 0 || saved.second < 0
        val startX = if (fresh) svc.dp(14) else saved.first
        val startY = if (fresh) (metrics.heightPixels * 0.78f).toInt() else saved.second
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(svc.dp(6), svc.dp(6), svc.dp(6), svc.dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = svc.dp(24).toFloat()
                setColor(0xE8161820.toInt())
                setStroke(svc.dp(1), 0x33FFFFFF)
            }
            elevation = svc.dp(6).toFloat()
        }

        btnPlay = chip("▶", 15f, 0xFFFFFFFF.toInt()) {
            svc.engine.toggle()
            refresh()
        }

        info = TextView(svc).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFE8ECF2.toInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = svc.dp(86)
            setPadding(svc.dp(10), svc.dp(6), svc.dp(10), svc.dp(6))
            text = "0 次"
        }

        val btnStop = chip("■", 12f, 0xFFFF7A72.toInt()) {
            svc.engine.stop("手动停止")
            refresh()
        }
        val btnAdd = chip("＋", 16f, 0xFFFFFFFF.toInt()) {
            svc.showPicker { refresh() }
        }
        val btnClose = chip("✕", 12f, 0x99FFFFFF.toInt()) {
            ConfigStore.current(svc).showPanel = false
            ConfigStore.save(svc)
            svc.hidePanel()
            ClickAccessibilityService.notifyHooks()
        }

        row.addView(btnPlay, LinearLayout.LayoutParams(svc.dp(36), svc.dp(36)))
        row.addView(info, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, svc.dp(36)
        ))
        row.addView(btnStop, LinearLayout.LayoutParams(svc.dp(36), svc.dp(36)))
        row.addView(btnAdd, LinearLayout.LayoutParams(svc.dp(36), svc.dp(36)))
        row.addView(btnClose, LinearLayout.LayoutParams(svc.dp(32), svc.dp(32)))

        attachDrag(row)
        attachDrag(info!!)

        root = row

        // 首次使用：宽高测量出来后贴右边，避免压在参数输入框上
        if (fresh) {
            row.post {
                val p = params ?: return@post
                val w = row.width
                if (w > 0 && root?.isAttachedToWindow == true) {
                    p.x = (metrics.widthPixels - w - svc.dp(10)).coerceAtLeast(svc.dp(6))
                    runCatching { wm.updateViewLayout(row, p) }
                }
            }
        }
    }

    private fun chip(label: String, sizeSp: Float, color: Int, onTap: () -> Unit): TextView =
        TextView(svc).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
            this.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x1FFFFFFF)
            }
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun attachDrag(view: View) {
        var downX = 0
        var downY = 0
        var originX = 0
        var originY = 0
        var moved = false
        view.setOnTouchListener { _, e ->
            val lp = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX.toInt()
                    downY = e.rawY.toInt()
                    originX = lp.x
                    originY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX.toInt() - downX
                    val dy = e.rawY.toInt() - downY
                    if (kotlin.math.abs(dx) > svc.dp(4) || kotlin.math.abs(dy) > svc.dp(4)) moved = true
                    if (moved && root?.isAttachedToWindow == true) {
                        lp.x = originX + dx
                        lp.y = originY + dy
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) ConfigStore.savePanelPos(svc, lp.x, lp.y)
                    moved
                }
                else -> false
            }
        }
    }

    fun refresh() {
        renderOnly()
        handler.removeCallbacks(ticker)
        val st = svc.engine.state
        if (st == ClickEngine.State.RUNNING || st == ClickEngine.State.COUNTDOWN) {
            handler.postDelayed(ticker, 500)
        }
    }

    private fun renderOnly() {
        // 窗口被系统回收后继续刷新没有意义，还会触发窗口会话异常
        if (root?.isAttachedToWindow != true) return
        val e = svc.engine
        val st = e.state
        btnPlay?.text = if (st == ClickEngine.State.RUNNING || st == ClickEngine.State.COUNTDOWN) "❚❚" else "▶"
        val text = when (st) {
            ClickEngine.State.IDLE -> "${e.clickCount} 次"
            ClickEngine.State.COUNTDOWN -> "${e.countdownLeft}s 后开始"
            ClickEngine.State.PAUSED -> "暂停 · ${e.clickCount}"
            ClickEngine.State.RUNNING -> "${ClickEngine.formatDuration(e.elapsedMs())} · ${e.clickCount}"
        }
        info?.text = text
    }
}
