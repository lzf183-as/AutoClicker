package com.autoclicker.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.autoclicker.app.data.ConfigStore
import com.autoclicker.app.data.Logs
import com.autoclicker.app.overlay.ControlPanel
import com.autoclicker.app.overlay.PointPicker
import java.util.concurrent.CopyOnWriteArrayList

class ClickAccessibilityService : AccessibilityService() {

    lateinit var engine: ClickEngine
        private set

    private var panel: ControlPanel? = null
    private var picker: PointPicker? = null

    @Volatile
    private var panelSuppressed = false

    var currentPackage: String? = null
        private set
    var lastOtherPackage: String? = null
        private set

    private var lastLoggedPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logs.init(this)
        Logs.setEnabled(ConfigStore.current(this).logging)
        engine = ClickEngine(this)
        engine.onUpdate = {
            panel?.refresh()
            notifyHooks()
        }
        if (ConfigStore.current(this).showPanel) showPanel()
        runCatching {
            registerReceiver(screenReceiver, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        }.onFailure { Logs.add("锁屏监听注册失败：${it.message}") }
        Logs.add("无障碍服务已连接（flags=${serviceInfo?.flags ?: -1}, cap=${serviceInfo?.capabilities ?: -1}, pid=${android.os.Process.myPid()}）")
        notifyHooks()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // 本应用自己的窗口（主界面、悬浮条、取点层）也会不断抛 TYPE_WINDOW_STATE_CHANGED。
        // 照单全收的话 lastOtherPackage 会被自己覆盖，日志也会被刷屏，直接忽略。
        if (pkg == packageName) return
        currentPackage = pkg
        lastOtherPackage = pkg
        if (pkg != lastLoggedPkg) {
            lastLoggedPkg = pkg
            Logs.add("前台应用：$pkg")
        }

        val lock = ConfigStore.current(this).lockPackage
        if (!lock.isNullOrEmpty() &&
            engine.state == ClickEngine.State.RUNNING &&
            pkg != lock
        ) {
            engine.pause("已切出锁定应用（$pkg）")
        }

        // 悬浮条的显隐由 Activity 生命周期控制（见 setPanelSuppressed），
        // 不能用窗口事件判断：面板自己弹出时会上报本应用包名，形成自触发死循环
    }

    override fun onInterrupt() {
        if (this::engine.isInitialized) engine.stop("服务被系统中断")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (!this::engine.isInitialized) return false
        val cfg = ConfigStore.current(this)
        if (!cfg.volumeKeyToggle) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        return when (event.keyCode) {
            // 音量+：开始 / 继续
            KeyEvent.KEYCODE_VOLUME_UP -> {
                when (engine.state) {
                    ClickEngine.State.IDLE -> engine.start()
                    ClickEngine.State.PAUSED -> engine.resume()
                    else -> Unit
                }
                refreshPanel()
                true
            }
            // 音量-：停止
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (engine.state != ClickEngine.State.IDLE) {
                    engine.stop("音量键停止")
                    refreshPanel()
                }
                true
            }
            else -> false
        }
    }

    private fun refreshPanel() {
        panel?.refresh()
        notifyHooks()
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (!ConfigStore.current(this@ClickAccessibilityService).pauseOnScreenOff) return
                    if (!this@ClickAccessibilityService::engine.isInitialized) return
                    if (engine.state == ClickEngine.State.RUNNING) {
                        engine.pause("锁屏")
                        refreshPanel()
                    }
                }
            }
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Logs.add("服务 onUnbind pid=${android.os.Process.myPid()} intent=$intent")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (this::engine.isInitialized) engine.stop("服务已断开")
        runCatching { unregisterReceiver(screenReceiver) }
        hidePanel()
        picker?.dismiss()
        picker = null
        instance = null
        Logs.add("无障碍服务已断开 pid=${android.os.Process.myPid()}")
        super.onDestroy()
    }

    fun showPanel() {
        // uiDepth > 0 表示本应用界面在前台。这里必须再判一次：
        // 服务是在界面 onResume 之后才连上的场景下，setPanelSuppressed 当时拿不到 instance，
        // 只有靠 uiDepth 这个静态标记兜住，否则悬浮条会出现在 App 内部挡住输入框。
        if (panel != null || panelSuppressed || uiDepth > 0 || externalUi) return
        panel = ControlPanel(this).also { it.show() }
    }

    fun hidePanel() {
        val p = panel ?: return
        p.hide()
        // 移除失败就保留引用，下次还能再试，否则窗口会变成孤儿
        if (!p.isShown) panel = null
    }

    /**
     * 本应用界面在前台时抑制悬浮条，否则它会挡住参数输入框。
     * 由 MainActivity / LogActivity 的 onResume / onPause 调用。
     */
    fun setPanelSuppressed(suppressed: Boolean) {
        panelSuppressed = suppressed
        if (suppressed) {
            hidePanel()
        } else if (picker == null && ConfigStore.current(this).showPanel) {
            showPanel()
        }
    }

    fun isPanelShown(): Boolean = panel != null

    fun showPicker(onDone: () -> Unit) {
        picker?.dismiss()
        // 取点需要全屏，先把悬浮条收起来，否则会挡住要点的位置
        hidePanel()
        val p = PointPicker(this) {
            picker = null
            notifyHooks()
            if (ConfigStore.current(this).showPanel) showPanel()
            onDone()
        }
        picker = p
        p.show()
    }

    fun dismissPicker() {
        picker?.dismiss()
        picker = null
        if (ConfigStore.current(this).showPanel) showPanel()
    }

    companion object {
        @Volatile
        var instance: ClickAccessibilityService? = null

        private val hooks = CopyOnWriteArrayList<() -> Unit>()

        private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var uiDepth = 0

        /** 正在显示我们自己拉起的系统界面（文件选择器等）。此时不该弹悬浮条。 */
        private var externalUi = false

        /** 本应用界面是否在前台。点击引擎据此避免把点击打到自己界面上。 */
        val isUiForeground: Boolean get() = uiDepth > 0

        /** 拉起系统文件选择器之前调用：收起悬浮条并保持收起，直到 endExternalUi() */
        fun beginExternalUi() {
            externalUi = true
            instance?.setPanelSuppressed(true)
        }

        fun endExternalUi() {
            externalUi = false
        }

        /** 本应用界面进入前台：抑制悬浮条，避免遮挡输入框 */
        fun onUiResume() {
            uiDepth++
            instance?.setPanelSuppressed(true)
        }

        /**
         * 本应用界面离开前台。Activity 切换时 onPause/onResume 的先后顺序不固定，
         * 所以延迟一拍再决定，避免返回主界面时悬浮条闪出来。
         */
        fun onUiPause() {
            uiDepth = (uiDepth - 1).coerceAtLeast(0)
            if (uiDepth > 0) return
            uiHandler.postDelayed({
                if (uiDepth == 0 && !externalUi) instance?.setPanelSuppressed(false)
            }, 400)
        }

        fun addHook(hook: () -> Unit) {
            hooks.add(hook)
        }

        fun removeHook(hook: () -> Unit) {
            hooks.remove(hook)
        }

        fun notifyHooks() {
            hooks.forEach { runCatching { it() } }
        }

        /** 用 ENABLED_ACCESSIBILITY_SERVICES 判断服务是否真的被系统打开 */
        fun isEnabled(ctx: Context): Boolean {
            val component = ComponentName(ctx.packageName, ClickAccessibilityService::class.java.name)
            val full = component.flattenToString()
            val short = component.flattenToShortString()
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
        }

        fun isRunning(): Boolean = instance != null

        fun openSettings(ctx: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
        }
    }
}
