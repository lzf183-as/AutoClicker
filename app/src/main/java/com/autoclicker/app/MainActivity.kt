package com.autoclicker.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.autoclicker.app.data.ConfigStore
import com.autoclicker.app.data.Logs
import com.autoclicker.app.model.ClickConfig
import com.autoclicker.app.model.ClickPoint
import com.autoclicker.app.model.ClickType
import com.autoclicker.app.model.RunMode
import com.autoclicker.app.service.ClickAccessibilityService
import com.autoclicker.app.service.ClickEngine
import com.autoclicker.app.ui.btn
import com.autoclicker.app.ui.card
import com.autoclicker.app.ui.col
import com.autoclicker.app.ui.dp
import com.autoclicker.app.ui.hdivider
import com.autoclicker.app.ui.m
import com.autoclicker.app.ui.roundBg
import com.autoclicker.app.ui.tv

class MainActivity : Activity() {

    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var serviceBtn: TextView
    private lateinit var runBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var countdownBox: LinearLayout
    private lateinit var countdownNum: TextView
    private lateinit var pointsHeader: TextView
    private lateinit var pointsContainer: LinearLayout
    private lateinit var lockText: TextView
    private lateinit var unlockBtn: TextView
    private lateinit var schemeLabel: TextView

    private var lastCountdown = -1
    private var dragging = false

    private val config: ClickConfig get() = ConfigStore.current(this)

    private val hook: () -> Unit = { runOnUiThread { refreshAll(rebuildList = false) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logs.init(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        ClickAccessibilityService.addHook(hook)
        ClickAccessibilityService.onUiResume()
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        ClickAccessibilityService.removeHook(hook)
        ClickAccessibilityService.onUiPause()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(col(R.color.bg)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(36))
        }
        scroll.addView(root, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(tv("连点器", 26f, R.color.text_primary, true))
        root.addView(
            tv("免 root · 无障碍手势注入 · 不联网", 12f, R.color.text_secondary)
                .also { it.m(top = dp(4)) }
        )

        // ---- 状态卡
        val statusCard = card().also { it.m(top = dp(16)) }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = tv("●", 14f, R.color.danger)
        val statusCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        statusText = tv("检查中…", 14f, R.color.text_primary, true)
        statusSub = tv("", 11f, R.color.text_secondary).also { it.m(top = dp(2)) }
        statusCol.addView(statusText)
        statusCol.addView(statusSub)
        statusRow.addView(statusDot)
        statusRow.addView(statusCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        serviceBtn = btn("去开启", primary = true) { ClickAccessibilityService.openSettings(this) }
        statusRow.addView(serviceBtn)
        statusCard.addView(statusRow)

        countdownBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(10), 0, dp(2))
        }
        countdownNum = tv("3", 40f, R.color.accent, true)
        countdownBox.addView(countdownNum)
        countdownBox.addView(tv("秒后开始点击", 13f, R.color.text_secondary).also { it.m(left = dp(8)) })
        statusCard.addView(countdownBox)

        val runRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        runBtn = btn("开始点击", primary = true) { onRunPressed() }
        stopBtn = btn("停止") {
            ClickAccessibilityService.instance?.engine?.stop("主界面停止")
            refreshAll()
        }
        stopBtn.visibility = View.GONE
        runRow.addView(runBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        runRow.addView(stopBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            marginStart = dp(8)
        })
        statusCard.addView(runRow.also { it.m(top = dp(12)) })
        root.addView(statusCard)

        // ---- 点位卡
        val pointsCard = card().also { it.m(top = dp(12)) }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pointsHeader = tv("点位 (0)", 15f, R.color.text_primary, true)
        headerRow.addView(pointsHeader, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(btn("编辑点位", primary = false) { openPicker() })
        pointsCard.addView(headerRow)
        pointsCard.addView(
            tv("长按 ≡ 拖动可调整执行顺序；点类型可循环切换 单击/长按/双击/拖动", 11f, R.color.text_hint)
                .also { it.m(top = dp(6), bottom = dp(8)) }
        )
        pointsCard.addView(hdivider())

        pointsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pointsCard.addView(pointsContainer)
        root.addView(pointsCard)

        // ---- 运行参数
        val paramCard = card().also { it.m(top = dp(12)) }
        paramCard.addView(tv("运行参数", 15f, R.color.text_primary, true))

        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presetRow.addView(chip("快速连点", false) { applyPreset(0) })
        presetRow.addView(chip("慢速循环", false) { applyPreset(1) }.also { it.m(left = dp(6)) })
        presetRow.addView(chip("模拟人工", false) { applyPreset(2) }.also { it.m(left = dp(6)) })
        paramCard.addView(presetRow.also { it.m(top = dp(10), bottom = dp(2)) })

        paramCard.addView(tv("停止方式", 12f, R.color.text_secondary).also { it.m(top = dp(10)) })
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (mode in intArrayOf(RunMode.LOOP, RunMode.TIME, RunMode.ONCE)) {
            modeRow.addView(chip(RunMode.label(mode), config.runMode == mode) {
                config.runMode = mode
                persist()
                reloadUi()
            }.also { if (mode != RunMode.LOOP) it.m(left = dp(6)) })
        }
        paramCard.addView(modeRow.also { it.m(top = dp(6)) })

        when (config.runMode) {
            RunMode.TIME -> paramCard.addView(
                numberRow("运行时长", config.runSeconds, "到时间自动停止", "秒", 5, 5, 86400) { v ->
                    config.runSeconds = v
                    persist()
                }
            )
            RunMode.ONCE -> paramCard.addView(
                tv("每个点位依次点一遍后自动停止", 11f, R.color.text_hint).also { it.m(top = dp(6)) }
            )
            else -> paramCard.addView(
                numberRow("循环次数", config.loopCount, "0 = 无限循环", "轮", 1, 0, 999999) { v ->
                    config.loopCount = v
                    persist()
                }
            )
        }
        paramCard.addView(numberRow("启动延迟", config.startDelaySec, "留出切换页面的时间", "秒", 1, 0, 60) { v ->
            config.startDelaySec = v
            persist()
        })
        paramCard.addView(numberRow("间隔抖动", config.jitterTimePct, "每次间隔上下浮动", "%", 5, 0, 50) { v ->
            config.jitterTimePct = v
            persist()
        })
        paramCard.addView(numberRow("坐标抖动", config.jitterPosPx, "点击位置随机偏移半径", "px", 1, 0, 40) { v ->
            config.jitterPosPx = v
            persist()
        })
        root.addView(paramCard)

        // ---- 窗口锁定
        val lockCard = card().also { it.m(top = dp(12)) }
        lockCard.addView(tv("窗口锁定", 15f, R.color.text_primary, true))
        lockText = tv("未开启", 12f, R.color.text_secondary).also { it.m(top = dp(6)) }
        lockCard.addView(lockText)
        val lockRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lockRow.addView(btn("锁定上一个应用") {
            val pkg = ClickAccessibilityService.instance?.lastOtherPackage
            if (pkg.isNullOrEmpty()) {
                toast("先打开目标应用，再回到这里锁定")
                return@btn
            }
            config.lockPackage = pkg
            config.lockLabel = appLabel(pkg)
            persist()
            refreshAll()
            toast("已锁定：${config.lockLabel ?: pkg}")
        })
        unlockBtn = btn("解除") {
            config.lockPackage = null
            config.lockLabel = null
            persist()
            refreshAll()
        }
        lockRow.addView(unlockBtn.also { it.m(left = dp(8)) })
        lockCard.addView(lockRow.also { it.m(top = dp(10)) })
        root.addView(lockCard)

        // ---- 选项
        val optionCard = card().also { it.m(top = dp(12)) }
        optionCard.addView(tv("开关", 15f, R.color.text_primary, true))
        optionCard.addView(check("音量键控制（＋开始/继续，− 停止）", config.volumeKeyToggle) {
            config.volumeKeyToggle = it
            persist()
        })
        optionCard.addView(check("显示悬浮控制面板", config.showPanel) {
            config.showPanel = it
            persist()
            val svc = ClickAccessibilityService.instance
            if (it) svc?.showPanel() else svc?.hidePanel()
            refreshAll()
        })
        optionCard.addView(check("锁屏时自动暂停", config.pauseOnScreenOff) {
            config.pauseOnScreenOff = it
            persist()
        })
        optionCard.addView(check("记录运行日志", config.logging) {
            config.logging = it
            Logs.setEnabled(it)
            persist()
        })
        optionCard.addView(check("记录每次点击坐标（日志量大）", config.verboseLog) {
            config.verboseLog = it
            persist()
        })
        root.addView(optionCard)

        // ---- 方案
        val scriptCard = card().also { it.m(top = dp(12)) }
        val scriptHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scriptHead.addView(
            tv("方案", 15f, R.color.text_primary, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        schemeLabel = tv(ConfigStore.schemeName(this) ?: "未命名", 12f, R.color.accent)
        scriptHead.addView(schemeLabel)
        scriptCard.addView(scriptHead)
        scriptCard.addView(
            tv("把当前点位和参数存成方案，一键切换", 12f, R.color.text_secondary)
                .also { it.m(top = dp(6), bottom = dp(10)) }
        )
        val sRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sRow1.addView(btn("保存方案") { saveDialog() })
        sRow1.addView(btn("载入方案") { loadDialog() }.also { it.m(left = dp(8)) })
        scriptCard.addView(sRow1)
        val sRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sRow2.addView(btn("导出 JSON") { exportJson() })
        sRow2.addView(btn("导入 JSON") { importJson() }.also { it.m(left = dp(8)) })
        sRow2.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        scriptCard.addView(sRow2)
        root.addView(scriptCard)

        // ---- 日志
        val logCard = card().also { it.m(top = dp(12)) }
        val logRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logRow.addView(
            tv("运行日志", 14f, R.color.text_primary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        logRow.addView(btn("查看 / 导出") { startActivity(Intent(this, LogActivity::class.java)) })
        logCard.addView(logRow)
        root.addView(logCard)

        root.addView(
            tv(
                "仅用于自动化测试与个人效率场景。禁止用于刷量、抢购、绕过风控等违反第三方服务条款的行为，后果自负。",
                11f, R.color.text_hint
            ).also { it.m(top = dp(18)) }
        )
        return scroll
    }

    // ---------------------------------------------------------------- 刷新

    private fun refreshAll(rebuildList: Boolean = true) {
        val enabled = ClickAccessibilityService.isEnabled(this)
        val svc = ClickAccessibilityService.instance
        val engine = svc?.engine
        val st = engine?.state ?: ClickEngine.State.IDLE

        statusDot.setTextColor(col(if (svc != null) R.color.ok else if (enabled) R.color.warn else R.color.danger))
        statusText.text = when {
            svc == null && !enabled -> "无障碍服务未开启"
            svc == null -> "服务已授权，等待系统连接…"
            st == ClickEngine.State.RUNNING -> "运行中"
            st == ClickEngine.State.PAUSED -> "暂停中"
            st == ClickEngine.State.COUNTDOWN -> "倒计时中"
            else -> "空闲中"
        }
        statusSub.text = when {
            svc == null -> "点右侧按钮，在系统设置里启用「连点器 · 手势服务」"
            st == ClickEngine.State.RUNNING && engine?.waitingOwnUi == true ->
                "切换到你要点操作的应用，切走后自动开始"
            st == ClickEngine.State.RUNNING ->
                "已点击 ${engine?.clickCount ?: 0} 次 · ${ClickEngine.formatDuration(engine?.elapsedMs() ?: 0L)}"
            st == ClickEngine.State.PAUSED -> "已点击 ${engine?.clickCount ?: 0} 次，点「继续」恢复"
            st == ClickEngine.State.COUNTDOWN -> "即将开始，请切换到目标界面"
            else -> "无障碍正常，随时可以开始"
        }
        serviceBtn.text = if (enabled) "设置" else "去开启"

        // 倒计时动画
        if (st == ClickEngine.State.COUNTDOWN && engine != null) {
            countdownBox.visibility = View.VISIBLE
            val n = engine.countdownLeft
            countdownNum.text = n.toString()
            if (n != lastCountdown) {
                lastCountdown = n
                countdownNum.scaleX = 1.45f
                countdownNum.scaleY = 1.45f
                countdownNum.alpha = 0.4f
                countdownNum.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320).start()
            }
        } else {
            countdownBox.visibility = View.GONE
            lastCountdown = -1
        }

        runBtn.text = when (st) {
            ClickEngine.State.IDLE -> "开始点击"
            ClickEngine.State.COUNTDOWN -> "取消"
            ClickEngine.State.RUNNING -> "暂停"
            ClickEngine.State.PAUSED -> "继续"
        }
        runBtn.setTextColor(col(if (st == ClickEngine.State.IDLE) R.color.accent_on else R.color.text_primary))
        stopBtn.visibility =
            if (st == ClickEngine.State.RUNNING || st == ClickEngine.State.PAUSED) View.VISIBLE
            else View.GONE

        if (rebuildList) rebuildPoints()

        val lp = config.lockPackage
        lockText.text = if (lp.isNullOrEmpty()) {
            "未开启，在所有应用内都会点击"
        } else {
            "仅限「${config.lockLabel ?: lp}」内运行，切到其他应用自动暂停"
        }
        unlockBtn.isEnabled = !lp.isNullOrEmpty()
        unlockBtn.alpha = if (lp.isNullOrEmpty()) 0.4f else 1f
        schemeLabel.text = ConfigStore.schemeName(this) ?: "未命名"
    }

    private fun onRunPressed() {
        val svc = ClickAccessibilityService.instance
        if (svc == null) {
            toast("请先开启无障碍服务")
            ClickAccessibilityService.openSettings(this)
            return
        }
        val e = svc.engine
        when (e.state) {
            ClickEngine.State.IDLE -> {
                if (config.points.isEmpty()) {
                    toast("先点「编辑点位」添加至少一个点击位置")
                } else {
                    e.start()
                    toast("已启动，切到目标界面")
                }
            }
            ClickEngine.State.COUNTDOWN -> e.stop("倒计时中取消")
            ClickEngine.State.RUNNING -> e.pause()
            ClickEngine.State.PAUSED -> e.resume()
        }
        // 只刷新状态区，不重建点位列表：重建会把滚动位置弹回去，还会打断正在输入的框
        refreshAll(rebuildList = false)
    }

    private fun rebuildPoints() {
        pointsContainer.removeAllViews()
        val pts = config.points
        pointsHeader.text = "点位 (${pts.size})"
        if (pts.isEmpty()) {
            pointsContainer.addView(
                tv("还没有点位。点「编辑点位」，在屏幕上直接点选位置。", 12f, R.color.text_secondary)
                    .also { it.m(top = dp(12), bottom = dp(8)) }
            )
            return
        }
        pts.forEachIndexed { index, point ->
            pointsContainer.addView(pointRow(index, point))
        }
    }

    private fun pointRow(index: Int, point: ClickPoint): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            background = roundBg(col(R.color.surface), 10)
        }

        val handle = tv("≡", 17f, R.color.text_hint).apply {
            gravity = Gravity.CENTER
        }
        row.addView(handle, LinearLayout.LayoutParams(dp(26), dp(34)))

        val badge = tv("${index + 1}", 11f, R.color.accent_on, true).apply {
            gravity = Gravity.CENTER
            background = roundBg(col(R.color.accent), 20)
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(22), dp(22)))

        val coord = tv("${point.x}, ${point.y}", 13f, R.color.text_primary).also { it.m(left = dp(8)) }
        row.addView(coord, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val typeBtn = tv(ClickType.label(point.type), 12f, R.color.accent).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(5))
            minWidth = dp(48)
            background = roundBg(col(R.color.surface_alt), 8, col(R.color.stroke))
            isClickable = true
            setOnClickListener {
                point.type = ClickType.next(point.type)
                persist()
                rebuildLater()
                if (point.type == ClickType.DRAG) dragDialog(point)
            }
            setOnLongClickListener {
                if (point.type == ClickType.DRAG) {
                    dragDialog(point)
                    true
                } else false
            }
        }
        row.addView(typeBtn)

        val minus = stepBtn("−") { changeInterval(point, -50) }.also { it.m(left = dp(6)) }
        row.addView(minus, LinearLayout.LayoutParams(dp(28), dp(32)))

        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(point.intervalMs.toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(col(R.color.text_primary))
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(2), dp(4), dp(2), dp(4))
            background = roundBg(col(R.color.surface_alt), 8, col(R.color.stroke))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.toIntOrNull() ?: return
                    point.intervalMs = v.coerceIn(ClickPoint.MIN_INTERVAL, ClickPoint.MAX_INTERVAL)
                    persist()
                }
            })
        }
        row.addView(et, LinearLayout.LayoutParams(dp(56), dp(32)).apply { marginStart = dp(4) })

        row.addView(stepBtn("＋") { changeInterval(point, 50) }.also { it.m(left = dp(4)) },
            LinearLayout.LayoutParams(dp(28), dp(32)))

        val del = tv("✕", 13f, R.color.text_secondary).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                config.points.remove(point)
                persist()
                rebuildLater()
            }
        }
        row.addView(del, LinearLayout.LayoutParams(dp(30), dp(34)).apply { marginStart = dp(2) })

        attachDrag(handle, row, index, badge)
        return row
    }

    private fun changeInterval(point: ClickPoint, delta: Int) {
        point.intervalMs = (point.intervalMs + delta)
            .coerceIn(ClickPoint.MIN_INTERVAL, ClickPoint.MAX_INTERVAL)
        persist()
        rebuildLater()
    }

    /** 触摸回调里重建列表会崩溃，统一推迟到下一帧 */
    private fun rebuildLater() {
        window.decorView.post { runCatching { rebuildPoints() } }
    }

    /** 长按 ≡ 拖动排序：拖动过程中行跟随手指，松手后写入新顺序 */
    private fun attachDrag(handle: View, row: View, index: Int, badge: TextView) {
        var startY = 0f
        var target = index
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (dragging) return@setOnTouchListener true
                    dragging = true
                    startY = e.rawY
                    target = index
                    row.alpha = 0.85f
                    row.elevation = dp(8).toFloat()
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    row.translationY = e.rawY - startY
                    val t = indexAt(e.rawY)
                    if (t != target) {
                        target = t
                        badge.text = "${t + 1}"
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    row.translationY = 0f
                    row.alpha = 1f
                    row.elevation = 0f
                    dragging = false
                    if (target != index && target in config.points.indices) {
                        val p = config.points.removeAt(index)
                        config.points.add(target, p)
                        persist()
                    }
                    // 不能在触摸回调里直接重建：会把正在派发事件的 View 从父容器摘掉，
                    // 导致 ViewGroup.dispatchCollectViewAttributes 空指针崩溃
                    rebuildLater()
                    true
                }
                else -> false
            }
        }
    }

    private fun indexAt(rawY: Float): Int {
        val n = pointsContainer.childCount
        if (n == 0) return 0
        val loc = IntArray(2)
        for (i in 0 until n) {
            val child = pointsContainer.getChildAt(i)
            child.getLocationOnScreen(loc)
            if (rawY < loc[1] + child.height / 2) return i
        }
        return n - 1
    }

    // ---------------------------------------------------------------- 控件工厂

    private fun stepBtn(label: String, onTap: () -> Unit): TextView =
        tv(label, 15f, R.color.text_primary).apply {
            gravity = Gravity.CENTER
            background = roundBg(col(R.color.surface_alt), 8, col(R.color.stroke))
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun chip(label: String, active: Boolean, onTap: () -> Unit): TextView =
        tv(label, 12f, if (active) R.color.accent_on else R.color.text_secondary).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = if (active) roundBg(col(R.color.accent), 9)
            else roundBg(col(R.color.surface_alt), 9, col(R.color.stroke))
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun numberRow(
        label: String,
        value: Int,
        hint: String,
        suffix: String,
        step: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit
    ): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            tv(label, 14f, R.color.text_primary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(col(R.color.text_primary))
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(2), dp(4), dp(2), dp(4))
            background = roundBg(col(R.color.surface_alt), 8, col(R.color.stroke))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.toIntOrNull() ?: return
                    if (v < min || v > max) return
                    onChange(v)
                }
            })
        }
        fun bump(delta: Int) {
            val cur = et.text.toString().toIntOrNull() ?: value
            val nv = (cur + delta).coerceIn(min, max)
            et.setText(nv.toString())
            et.setSelection(et.text.length)
        }
        row.addView(stepBtn("−") { bump(-step) }, LinearLayout.LayoutParams(dp(34), dp(36)))
        row.addView(et, LinearLayout.LayoutParams(dp(64), dp(36)).apply { marginStart = dp(6) })
        row.addView(stepBtn("＋") { bump(step) }, LinearLayout.LayoutParams(dp(34), dp(36)).apply {
            marginStart = dp(6)
        })
        row.addView(tv(suffix, 12f, R.color.text_secondary).also { it.m(left = dp(6)) })
        wrap.addView(row)
        wrap.addView(tv(hint, 11f, R.color.text_hint).also { it.m(top = dp(4)) })
        return wrap
    }

    private fun check(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            text = label
            setTextColor(col(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isChecked = checked
            buttonTintList = ColorStateList.valueOf(col(R.color.accent))
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    // ---------------------------------------------------------------- 动作

    private fun reloadUi() {
        setContentView(buildUi())
        refreshAll()
    }

    private fun applyPreset(kind: Int) {
        val c = config
        when (kind) {
            0 -> {
                c.runMode = RunMode.LOOP
                c.loopCount = 0
                c.startDelaySec = 0
                c.jitterTimePct = 0
                c.jitterPosPx = 0
                c.points.forEach { it.intervalMs = 30 }
            }
            1 -> {
                c.runMode = RunMode.LOOP
                c.loopCount = 10
                c.startDelaySec = 3
                c.jitterTimePct = 0
                c.jitterPosPx = 0
                c.points.forEach { it.intervalMs = 2000 }
            }
            else -> {
                c.runMode = RunMode.LOOP
                c.loopCount = 0
                c.startDelaySec = 2
                c.jitterTimePct = 25
                c.jitterPosPx = 8
                c.points.forEach { it.intervalMs = 800 }
            }
        }
        persist()
        reloadUi()
        toast("已套用预设")
    }

    private fun openPicker() {
        val svc = ClickAccessibilityService.instance
        if (svc == null) {
            toast("请先开启无障碍服务，取点需要手势服务")
            ClickAccessibilityService.openSettings(this)
            return
        }
        svc.showPicker { refreshAll() }
    }

    private fun dragDialog(point: ClickPoint) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(tv("拖动终点相对起点的位移（负值向上）", 12f, R.color.text_secondary))
        val dxInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(point.dragDx.toString())
            hint = "dx"
        }
        val dyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(point.dragDy.toString())
            hint = "dy"
        }
        box.addView(dxInput.also { it.m(top = dp(10)) })
        box.addView(dyInput)
        AlertDialog.Builder(this)
            .setTitle("拖动设置")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                point.dragDx = dxInput.text.toString().toIntOrNull() ?: point.dragDx
                point.dragDy = dyInput.text.toString().toIntOrNull() ?: point.dragDy
                persist()
                rebuildPoints()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun persist() {
        ConfigStore.save(this, config)
    }

    private fun saveDialog() {
        val input = EditText(this).apply {
            hint = "方案名称"
            setText(ConfigStore.schemeName(this@MainActivity)
                ?: "方案 ${ConfigStore.listScripts(this@MainActivity).size + 1}")
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("保存当前方案")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "未命名" }
                runCatching { ConfigStore.saveScript(this, name, config.prettyJson()) }
                    .onSuccess {
                        ConfigStore.setSchemeName(this, name)
                        toast("已保存：$name")
                        refreshAll()
                    }
                    .onFailure { toast("保存失败：${it.message}") }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadDialog() {
        val names = ConfigStore.listScripts(this)
        if (names.isEmpty()) {
            toast("还没有保存过方案")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("载入方案")
            .setItems(displayNames(names).toTypedArray()) { _, which ->
                val raw = ConfigStore.readScript(this, names[which])
                val loaded = raw?.let { runCatching { ClickConfig.parse(it) }.getOrNull() }
                if (loaded == null) {
                    toast("方案文件无法解析")
                    return@setItems
                }
                ConfigStore.save(this, loaded)
                ConfigStore.setSchemeName(this, displayNames(names)[which])
                Logs.setEnabled(loaded.logging)
                reloadUi()
                toast("已载入：${displayNames(names)[which]}")
            }
            .setNeutralButton("删除方案") { _, _ -> deleteDialog(names) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 文件名里的空格被 sanitize 成了下划线，展示时还原，避免「方案 1」和「方案_1」两套名字 */
    private fun displayNames(names: List<String>): List<String> = names.map { it.replace('_', ' ') }

    private fun deleteDialog(names: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("删除方案")
            .setItems(displayNames(names).toTypedArray()) { _, which ->
                val raw = names[which]
                ConfigStore.deleteScript(this, raw)
                // 删的就是当前方案：名字必须一起清掉，否则标题还挂着「方案 1」但文件已经没了
                if (ConfigStore.sameAsFile(ConfigStore.schemeName(this), raw)) {
                    ConfigStore.setSchemeName(this, null)
                }
                refreshAll()
                toast("已删除：${displayNames(names)[which]}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportJson() {
        // 系统文件选择器是全屏 Activity，悬浮条留在上面既挡事又容易让窗口会话出问题，先收起
        ClickAccessibilityService.beginExternalUi()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "autoclicker-${System.currentTimeMillis()}.json")
        }
        runCatching { startActivityForResult(intent, RC_EXPORT) }
            .onFailure { toast("无法打开文件选择器") }
    }

    private fun importJson() {
        ClickAccessibilityService.beginExternalUi()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, RC_IMPORT) }
            .onFailure { toast("无法打开文件选择器") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ClickAccessibilityService.endExternalUi()
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            RC_EXPORT -> {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(config.prettyJson().toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入")
                    toast("已导出 JSON")
                }.onFailure { toast("导出失败：${it.message}") }
            }
            RC_IMPORT -> {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("读不到内容")
                    val loaded = ClickConfig.parse(text)
                    ConfigStore.save(this, loaded)
                    ConfigStore.setSchemeName(this, null)
                    Logs.setEnabled(loaded.logging)
                    reloadUi()
                    toast("已导入 ${loaded.points.size} 个点位")
                }.onFailure { toast("导入失败：${it.message}") }
            }
        }
    }

    private fun appLabel(pkg: String): String? = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrNull()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val RC_EXPORT = 1001
        private const val RC_IMPORT = 1002
    }
}
