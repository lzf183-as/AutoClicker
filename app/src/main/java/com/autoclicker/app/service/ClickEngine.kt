package com.autoclicker.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.autoclicker.app.data.ConfigStore
import com.autoclicker.app.data.Logs
import com.autoclicker.app.model.ClickConfig
import com.autoclicker.app.model.ClickPoint
import com.autoclicker.app.model.ClickType
import com.autoclicker.app.model.RunMode
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 点击调度器。
 *
 * 关键约束：dispatchGesture 是异步的，必须等 onCompleted 才能发下一次，
 * 所以调度链是「下发 → 回调 → 延时 → 再下发」，绝不能用 while 循环空转。
 */
class ClickEngine(private val svc: AccessibilityService) {

    enum class State { IDLE, COUNTDOWN, RUNNING, PAUSED }

    var state: State = State.IDLE
        private set

    var clickCount: Long = 0
        private set

    var loopDone: Int = 0
        private set

    var countdownLeft: Int = 0
        private set

    var currentIndex: Int = 0
        private set

    /** 运行中但本应用界面还在前台，暂缓下发手势 */
    var waitingOwnUi: Boolean = false
        private set

    private var startedAt: Long = 0
    private var failStreak = 0

    /** 「按时长」总闸的剩余毫秒；真正开始点击后才挂上，暂停时扣减，继续时重挂 */
    private var timeLimitRemaining: Long = 0
    private var timeLimitArmedAt: Long = 0
    private var timeLimitArmed = false
    /** 计时是否已经从「首次真正下手势」开始了 */
    private var elapsedStarted = false
    private var pauseStartedAt = 0L
    private var pausedTotal = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random(System.currentTimeMillis())

    var onUpdate: (() -> Unit)? = null

    private val config: ClickConfig get() = ConfigStore.current(svc)

    fun elapsedMs(): Long {
        if (startedAt == 0L || state == State.IDLE) return 0L
        val now = SystemClock.elapsedRealtime()
        // 暂停的时长不算进运行时间，否则「运行 0:29」会把人看懵
        val inPause = if (pauseStartedAt > 0) now - pauseStartedAt else 0L
        return (now - startedAt - pausedTotal - inPause).coerceAtLeast(0L)
    }

    fun start() {
        val c = config
        if (c.points.isEmpty()) {
            Logs.add("启动失败：没有点位")
            onUpdate?.invoke()
            return
        }
        handler.removeCallbacksAndMessages(null)
        clickCount = 0
        loopDone = 0
        currentIndex = 0
        failStreak = 0
        waitingOwnUi = false
        startedAt = SystemClock.elapsedRealtime()
        timeLimitRemaining = c.runSeconds * 1000L
        timeLimitArmed = false
        elapsedStarted = false
        pauseStartedAt = 0L
        pausedTotal = 0L

        val loopText = when (c.runMode) {
            RunMode.ONCE -> "单轮结束后停"
            RunMode.TIME -> "运行 ${c.runSeconds}s 后停"
            else -> if (c.loopCount <= 0) "无限循环" else "循环 ${c.loopCount} 轮"
        }
        Logs.add("启动：${c.points.size} 个点位，$loopText，间隔基准 ${c.points[0].intervalMs}ms" +
            (if (c.jitterTimePct > 0) "，抖动 ±${c.jitterTimePct}%" else ""))

        if (c.startDelaySec > 0) {
            countdownLeft = c.startDelaySec
            state = State.COUNTDOWN
            onUpdate?.invoke()
            handler.postDelayed(countdownTask, 1000)
        } else {
            state = State.RUNNING
            onUpdate?.invoke()
            step()
        }
    }

    private val countdownTask = object : Runnable {
        override fun run() {
            if (state != State.COUNTDOWN) return
            countdownLeft--
            if (countdownLeft <= 0) {
                state = State.RUNNING
                // 从真正开始点的那一刻计时，别把倒计时算进运行时长
                startedAt = SystemClock.elapsedRealtime()
                onUpdate?.invoke()
                step()
            } else {
                onUpdate?.invoke()
                handler.postDelayed(this, 1000)
            }
        }
    }

    /** 按时长模式：真正开始点击时才挂总闸，且只挂一次 */
    private fun armTimeLimitIfNeeded() {
        val c = config
        if (c.runMode != RunMode.TIME) return
        if (timeLimitArmed) return
        timeLimitArmed = true
        timeLimitArmedAt = SystemClock.elapsedRealtime()
        if (timeLimitRemaining <= 0) timeLimitRemaining = c.runSeconds * 1000L
        handler.postDelayed(timeLimitTask, timeLimitRemaining)
    }

    private val timeLimitTask = Runnable {
        if (state == State.RUNNING) stop("已达到设定时长 ${config.runSeconds}s")
    }

    /** 暂停/停止前结算剩余时长，继续时按剩余时间重挂，别让「按时长」被暂停吞掉 */
    private fun settleTimeLimit() {
        if (!timeLimitArmed) return
        timeLimitRemaining -= (SystemClock.elapsedRealtime() - timeLimitArmedAt)
        if (timeLimitRemaining < 0) timeLimitRemaining = 0
        timeLimitArmed = false
        handler.removeCallbacks(timeLimitTask)
    }

    fun pause(reason: String? = null) {
        if (state != State.RUNNING) return
        state = State.PAUSED
        pauseStartedAt = SystemClock.elapsedRealtime()
        settleTimeLimit()
        handler.removeCallbacksAndMessages(null)
        Logs.add("已暂停" + (reason?.let { "（$it）" } ?: "") + "，已点击 $clickCount 次")
        onUpdate?.invoke()
    }

    fun resume() {
        if (state != State.PAUSED) return
        if (pauseStartedAt > 0) {
            pausedTotal += SystemClock.elapsedRealtime() - pauseStartedAt
            pauseStartedAt = 0L
        }
        state = State.RUNNING
        Logs.add("继续运行")
        onUpdate?.invoke()
        handler.postDelayed({ step() }, 200)
    }

    fun toggle() {
        when (state) {
            State.IDLE -> start()
            State.COUNTDOWN -> stop("倒计时中取消")
            State.RUNNING -> pause()
            State.PAUSED -> resume()
        }
    }

    fun stop(reason: String? = null) {
        val active = state != State.IDLE
        val elapsed = elapsedMs()
        val count = clickCount
        settleTimeLimit()
        handler.removeCallbacksAndMessages(null)
        state = State.IDLE
        countdownLeft = 0
        waitingOwnUi = false
        timeLimitArmed = false
        pauseStartedAt = 0L
        pausedTotal = 0L
        if (active) {
            Logs.add("停止" + (reason?.let { "（$it）" } ?: "") + "：共 $count 次，运行 ${formatDuration(elapsed)}")
        }
        onUpdate?.invoke()
    }

    private fun step() {
        if (state != State.RUNNING) return
        val c = config
        if (c.points.isEmpty()) {
            stop("点位为空")
            return
        }
        // 自己的界面还在前台就先不发手势：否则会点到自己界面，
        // 轻则把参数区拖走，重则点到 ✕ 把点位删了。切走后再自动开始。
        if (ClickAccessibilityService.isUiForeground) {
            if (!waitingOwnUi) {
                waitingOwnUi = true
                onUpdate?.invoke()
            }
            handler.postDelayed({ step() }, 250)
            return
        }
        if (waitingOwnUi) {
            waitingOwnUi = false
            onUpdate?.invoke()
        }
        // 真正要下手势了才开始算「按时长」，否则用户在应用里挑参数的时间会被吃掉
        if (!elapsedStarted) {
            elapsedStarted = true
            startedAt = SystemClock.elapsedRealtime()
        }
        armTimeLimitIfNeeded()
        if (currentIndex >= c.points.size) currentIndex = 0
        val p = c.points[currentIndex]

        val screenW = svc.resources.displayMetrics.widthPixels
        val screenH = svc.resources.displayMetrics.heightPixels
        val x = jitter(p.x, c.jitterPosPx, screenW)
        val y = jitter(p.y, c.jitterPosPx, screenH)

        val gesture = GestureDescription.Builder().apply {
            when (p.type) {
                ClickType.LONG -> addStroke(
                    GestureDescription.StrokeDescription(tapPath(x, y), 0, 650)
                )
                ClickType.DOUBLE -> {
                    // 同一手势里放两个 stroke：第一个 40ms，间隔 130ms 再补一下
                    addStroke(GestureDescription.StrokeDescription(tapPath(x, y), 0, 40))
                    addStroke(GestureDescription.StrokeDescription(tapPath(x, y), 170, 40))
                }
                ClickType.DRAG -> {
                    val ex = (x + p.dragDx).coerceIn(1, screenW - 1).toFloat()
                    val ey = (y + p.dragDy).coerceIn(1, screenH - 1).toFloat()
                    val path = Path().apply {
                        moveTo(x.toFloat(), y.toFloat())
                        lineTo(ex, ey)
                    }
                    addStroke(GestureDescription.StrokeDescription(path, 0, 520))
                }
                else -> addStroke(
                    GestureDescription.StrokeDescription(tapPath(x, y), 0, 45)
                )
            }
        }.build()

        val dispatched = runCatching {
            svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (state != State.RUNNING) return
                    clickCount++
                    failStreak = 0
                    if (config.verboseLog) {
                        Logs.add("✓ #$clickCount 点位${currentIndex + 1} ${ClickType.label(p.type)} ($x, $y)")
                    }
                    onUpdate?.invoke()
                    advance(p.intervalMs)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (state != State.RUNNING) return
                    failStreak++
                    Logs.add("✗ 手势被拒 (${p.x}, ${p.y})，连续 $failStreak 次")
                    if (failStreak >= 10) {
                        stop("连续 10 次手势被系统拒绝")
                        return
                    }
                    advance(p.intervalMs)
                }
            }, null)
        }.getOrDefault(false)

        if (!dispatched) {
            failStreak++
            if (failStreak >= 10) stop("手势下发失败") else advance(p.intervalMs)
        }
    }

    /** 极小位移保证路径长度 > 0，纯 moveTo 在部分 ROM 上会被判为无效手势 */
    private fun tapPath(x: Int, y: Int): Path = Path().apply {
        moveTo(x.toFloat(), y.toFloat())
        lineTo(x + 0.6f, y.toFloat())
    }

    private fun advance(baseInterval: Int) {
        if (state != State.RUNNING) return
        val c = config
        currentIndex++
        if (currentIndex >= c.points.size) {
            currentIndex = 0
            loopDone++
            val limit = c.effectiveLoop()
            if (limit > 0 && loopDone >= limit) {
                stop(if (c.runMode == RunMode.ONCE) "已完成一轮" else "已完成 $limit 轮")
                return
            }
        }
        scheduleNext(baseInterval)
    }

    private fun scheduleNext(base: Int) {
        if (state != State.RUNNING) return
        val c = config
        var ms = base.toLong()
        if (c.jitterTimePct > 0) {
            val amp = base * (c.jitterTimePct / 100.0)
            ms = (base + (rnd.nextDouble() * 2 - 1) * amp).toLong()
        }
        if (ms < ClickPoint.MIN_INTERVAL) ms = ClickPoint.MIN_INTERVAL.toLong()
        handler.postDelayed({ step() }, ms)
    }

    private fun jitter(value: Int, sigma: Int, max: Int): Int {
        if (sigma <= 0) return value.coerceIn(1, max - 1)
        val delta = (rnd.nextDouble() * 2 - 1) * sigma
        return (value + delta).roundToInt().coerceIn(1, max - 1)
    }

    companion object {
        fun formatDuration(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
