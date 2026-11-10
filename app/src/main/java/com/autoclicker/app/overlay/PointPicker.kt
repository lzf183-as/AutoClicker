package com.autoclicker.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.autoclicker.app.data.ConfigStore
import com.autoclicker.app.data.Logs
import com.autoclicker.app.model.ClickPoint
import com.autoclicker.app.model.ClickType
import com.autoclicker.app.service.ClickAccessibilityService
import com.autoclicker.app.ui.col
import com.autoclicker.app.ui.dp
import com.autoclicker.app.ui.roundBg

/**
 * 全屏坐标拾取器。点击屏幕添加点位，完成后覆盖原点位列表。
 */
class PointPicker(
    private val svc: ClickAccessibilityService,
    private val onDone: () -> Unit
) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val points = ArrayList<ClickPoint>()
    private var root: FrameLayout? = null
    private var pickerView: PickerView? = null
    private var hint: TextView? = null
    private var nudgeLabel: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var selected = -1

    init {
        ConfigStore.current(svc).points.forEach { points.add(it.copyOf()) }
        if (points.isNotEmpty()) selected = points.size - 1
    }

    fun show() {
        if (root != null) return

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val container = FrameLayout(svc)
        val pv = PickerView(svc)
        container.addView(pv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val bar = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(svc.dp(12), svc.dp(8), svc.dp(12), svc.dp(8))
            background = svc.roundBg(0xF2171A20.toInt(), 14, 0x33FFFFFF)
        }
        bar.addView(action("撤销", false) {
            if (points.isNotEmpty()) {
                points.removeAt(points.size - 1)
                selected = points.size - 1
                updateHint()
                pv.invalidate()
            }
        })
        bar.addView(action("清空", false) {
            points.clear()
            selected = -1
            updateHint()
            pv.invalidate()
        })
        bar.addView(View(svc), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(action("取消", false) { dismiss() })
        val doneBtn = action("完成", true) { finish() }
        bar.addView(doneBtn)

        val barLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        barLp.gravity = Gravity.TOP
        barLp.topMargin = statusBarHeight() + svc.dp(8)
        barLp.leftMargin = svc.dp(12)
        barLp.rightMargin = svc.dp(12)
        container.addView(bar, barLp)

        hint = TextView(svc).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(svc.dp(14), svc.dp(8), svc.dp(14), svc.dp(8))
            background = svc.roundBg(0xCC000000.toInt(), 20)
        }
        val hintLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        hintLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        hintLp.bottomMargin = svc.dp(92)
        container.addView(hint, hintLp)

        // 微调条：选中点位后可用方向键按 ±1px 修正
        val nudge = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(svc.dp(6), svc.dp(6), svc.dp(6), svc.dp(6))
            background = svc.roundBg(0xF2171A20.toInt(), 22, 0x33FFFFFF)
        }
        nudgeLabel = TextView(svc).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = svc.dp(92)
            setPadding(svc.dp(8), 0, svc.dp(8), 0)
        }
        nudge.addView(stepKey("◀") { nudge(-1, 0) })
        nudge.addView(stepKey("▲") { nudge(0, -1) })
        nudge.addView(stepKey("▼") { nudge(0, 1) })
        nudge.addView(stepKey("▶") { nudge(1, 0) })
        nudge.addView(nudgeLabel)

        val nudgeLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        nudgeLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        nudgeLp.bottomMargin = svc.dp(32)
        container.addView(nudge, nudgeLp)

        root = container
        pickerView = pv
        runCatching { wm.addView(container, params) }
            .onFailure {
                Logs.add("取点层添加失败：${it.message}")
                root = null
            }
        nudge.post {
            val l = IntArray(2)
            nudge.getLocationOnScreen(l)
            Logs.add("微调条 screen=${l[0]},${l[1]} size=${nudge.width}x${nudge.height}")
            pv.getLocationOnScreen(l)
            Logs.add("取点层 screen=${l[0]},${l[1]} size=${pv.width}x${pv.height}")
        }
        updateHint()
        Logs.add("进入取点模式")
    }

    fun dismiss() {
        val r = root ?: return
        val removed = runCatching { wm.removeView(r) }.isSuccess ||
            runCatching { wm.removeViewImmediate(r) }.isSuccess
        if (removed) {
            root = null
            pickerView = null
            hint = null
        }
        ClickAccessibilityService.notifyHooks()
    }

    private fun finish() {
        val cfg = ConfigStore.current(svc)
        cfg.points.clear()
        points.forEach { cfg.points.add(it.copyOf()) }
        ConfigStore.save(svc, cfg)
        Logs.add("取点完成，共 ${cfg.points.size} 个点位")
        Toast.makeText(svc, "已保存 ${cfg.points.size} 个点位", Toast.LENGTH_SHORT).show()
        dismiss()
        onDone()
    }

    private fun updateHint() {
        val p = points.getOrNull(selected)
        nudgeLabel?.text = if (p == null) "未选中" else "${p.x}, ${p.y}"
        hint?.text = when {
            points.isEmpty() -> "点击屏幕任意位置添加点位"
            p != null -> "第 ${selected + 1}/${points.size} 个 · 方向键 ±1px 微调，空白处点击可加点位"
            else -> "共 ${points.size} 个点位 · 点标记可选中"
        }
    }

    private fun nudge(dx: Int, dy: Int) {
        val p = points.getOrNull(selected) ?: return
        val w = svc.resources.displayMetrics.widthPixels
        val h = svc.resources.displayMetrics.heightPixels
        p.x = (p.x + dx).coerceIn(1, w - 1)
        p.y = (p.y + dy).coerceIn(1, h - 1)
        updateHint()
        pickerView?.invalidate()
    }

    private fun stepKey(label: String, onTap: () -> Unit): TextView {
        val v = TextView(svc).apply {
            text = label
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = svc.roundBg(0x33FFFFFF, 8)
            isClickable = true
            setOnClickListener { onTap() }
        }
        val lp = LinearLayout.LayoutParams(svc.dp(30), svc.dp(26))
        if (label != "▶") lp.rightMargin = svc.dp(4)
        v.layoutParams = lp
        v.setOnClickListener {
            onTap()
            Logs.add("微调 $label → ${nudgeLabel?.text}")
        }
        return v
    }

    private fun action(label: String, primary: Boolean, onTap: () -> Unit): TextView =
        TextView(svc).apply {
            text = label
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(svc.dp(14), svc.dp(8), svc.dp(14), svc.dp(8))
            setTextColor(if (primary) svc.col(com.autoclicker.app.R.color.accent_on) else Color.WHITE)
            background = if (primary) {
                svc.roundBg(svc.col(com.autoclicker.app.R.color.accent), 10)
            } else {
                svc.roundBg(0x33FFFFFF, 10)
            }
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun statusBarHeight(): Int {
        val id = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) svc.resources.getDimensionPixelSize(id) else svc.dp(28)
    }

    @SuppressLint("ViewConstructor")
    inner class PickerView(context: Context) : View(context) {

        private val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = svc.col(com.autoclicker.app.R.color.accent)
        }
        private val circleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = svc.dp(2).toFloat()
            color = Color.WHITE
        }
        private val indexPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = svc.dp(11).toFloat()
            isFakeBoldText = true
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt()
            strokeWidth = svc.dp(1).toFloat()
        }
        private val coordPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = svc.dp(12).toFloat()
            textAlign = Paint.Align.CENTER
        }
        private val coordBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
        private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = svc.dp(2).toFloat()
            color = 0xFFFFD54F.toInt()
        }

        private var touchX = -1f
        private var touchY = -1f
        private var downX = 0f
        private var downY = 0f

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0x66000000)

            points.forEachIndexed { index, p ->
                val cx = p.x.toFloat()
                val cy = p.y.toFloat()
                canvas.drawCircle(cx, cy, svc.dp(13).toFloat(), circleFill)
                canvas.drawCircle(cx, cy, svc.dp(13).toFloat(), circleStroke)
                if (index == selected) {
                    canvas.drawCircle(cx, cy, svc.dp(20).toFloat(), selectPaint)
                }
                val baseline = cy - (indexPaint.ascent() + indexPaint.descent()) / 2
                canvas.drawText((index + 1).toString(), cx, baseline, indexPaint)
            }

            if (touchX >= 0) {
                canvas.drawLine(0f, touchY, width.toFloat(), touchY, crossPaint)
                canvas.drawLine(touchX, 0f, touchX, height.toFloat(), crossPaint)
                val label = "${touchX.toInt()}, ${touchY.toInt()}"
                val w = coordPaint.measureText(label)
                val bx = touchX + svc.dp(16)
                val by = touchY - svc.dp(34)
                canvas.drawRoundRect(
                    bx, by, bx + w + svc.dp(16), by + svc.dp(24),
                    svc.dp(6).toFloat(), svc.dp(6).toFloat(), coordBg
                )
                canvas.drawText(label, bx + (w + svc.dp(16)) / 2, by + svc.dp(16), coordPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            touchX = event.rawX
            touchY = event.rawY
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = Math.hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble())
                    if (moved < svc.dp(12)) {
                        val hit = points.indexOfFirst {
                            Math.hypot(
                                (it.x - event.rawX).toDouble(),
                                (it.y - event.rawY).toDouble()
                            ) < svc.dp(26)
                        }
                        if (hit >= 0) {
                            selected = hit
                            updateHint()
                        } else {
                            addPoint(event.rawX, event.rawY)
                        }
                    }
                    touchX = -1f
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun addPoint(x: Float, y: Float) {
            points.add(ClickPoint(x.toInt().coerceAtLeast(1), y.toInt().coerceAtLeast(1), ClickType.TAP, 500))
            selected = points.size - 1
            Logs.add("加点 ${x.toInt()},${y.toInt()}")
            updateHint()
            invalidate()
        }
    }
}
