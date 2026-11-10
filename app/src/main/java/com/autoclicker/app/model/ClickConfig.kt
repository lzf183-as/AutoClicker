package com.autoclicker.app.model

import org.json.JSONArray
import org.json.JSONObject

object ClickType {
    const val TAP = 0
    const val LONG = 1
    const val DOUBLE = 2
    const val DRAG = 3

    fun label(type: Int): String = when (type) {
        LONG -> "长按"
        DOUBLE -> "双击"
        DRAG -> "拖动"
        else -> "单击"
    }

    fun next(type: Int): Int = when (type) {
        TAP -> LONG
        LONG -> DOUBLE
        DOUBLE -> DRAG
        else -> TAP
    }
}

object RunMode {
    const val LOOP = 0
    const val TIME = 1
    const val ONCE = 2

    fun label(mode: Int): String = when (mode) {
        TIME -> "按时长"
        ONCE -> "单轮"
        else -> "按轮次"
    }
}

/**
 * 一个点击点位。坐标是屏幕绝对像素，与录屏/手势坐标系一致。
 */
data class ClickPoint(
    var x: Int = 0,
    var y: Int = 0,
    var type: Int = ClickType.TAP,
    var intervalMs: Int = 500,
    /** 拖动终点的相对位移，仅在 type == DRAG 时生效 */
    var dragDx: Int = 0,
    var dragDy: Int = -400
) {
    fun copyOf(): ClickPoint = ClickPoint(x, y, type, intervalMs, dragDx, dragDy)

    fun toJson(): JSONObject = JSONObject().apply {
        put("x", x)
        put("y", y)
        put("type", type)
        put("intervalMs", intervalMs)
        put("dragDx", dragDx)
        put("dragDy", dragDy)
    }

    companion object {
        /** 系统手势注入的物理下限，低于这个值只会烧 CPU 且不准 */
        const val MIN_INTERVAL = 30
        const val MAX_INTERVAL = 20000

        fun fromJson(o: JSONObject): ClickPoint = ClickPoint(
            o.optInt("x", 0),
            o.optInt("y", 0),
            o.optInt("type", ClickType.TAP),
            o.optInt("intervalMs", 500).coerceIn(MIN_INTERVAL, MAX_INTERVAL),
            o.optInt("dragDx", 0),
            o.optInt("dragDy", -400)
        )
    }
}

data class ClickConfig(
    var points: MutableList<ClickPoint> = mutableListOf(),
    var runMode: Int = RunMode.LOOP,
    /** 0 表示无限循环（仅 LOOP 模式使用） */
    var loopCount: Int = 0,
    /** 按时长模式下运行多少秒后自动停 */
    var runSeconds: Int = 60,
    var startDelaySec: Int = 3,
    /** 间隔浮动百分比，0 表示不随机 */
    var jitterTimePct: Int = 0,
    /** 坐标抖动半径（px），0 表示不随机 */
    var jitterPosPx: Int = 0,
    /** 只在这个包名的窗口里运行，null 表示不锁定 */
    var lockPackage: String? = null,
    var lockLabel: String? = null,
    var volumeKeyToggle: Boolean = false,
    var pauseOnScreenOff: Boolean = true,
    var logging: Boolean = true,
    /** 记录每一次点击的坐标与结果，排查失效时打开 */
    var verboseLog: Boolean = false,
    var showPanel: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", 2)
        val arr = JSONArray()
        points.forEach { arr.put(it.toJson()) }
        put("points", arr)
        put("runMode", runMode)
        put("loopCount", loopCount)
        put("runSeconds", runSeconds)
        put("startDelaySec", startDelaySec)
        put("jitterTimePct", jitterTimePct)
        put("jitterPosPx", jitterPosPx)
        put("lockPackage", lockPackage ?: JSONObject.NULL)
        put("lockLabel", lockLabel ?: JSONObject.NULL)
        put("volumeKeyToggle", volumeKeyToggle)
        put("pauseOnScreenOff", pauseOnScreenOff)
        put("logging", logging)
        put("verboseLog", verboseLog)
        put("showPanel", showPanel)
    }

    fun prettyJson(): String = toJson().toString(2)

    fun copyOf(): ClickConfig = ClickConfig(
        points = points.map { it.copyOf() }.toMutableList(),
        runMode = runMode,
        loopCount = loopCount,
        runSeconds = runSeconds,
        startDelaySec = startDelaySec,
        jitterTimePct = jitterTimePct,
        jitterPosPx = jitterPosPx,
        lockPackage = lockPackage,
        lockLabel = lockLabel,
        volumeKeyToggle = volumeKeyToggle,
        pauseOnScreenOff = pauseOnScreenOff,
        logging = logging,
        verboseLog = verboseLog,
        showPanel = showPanel
    )

    /** 本次实际要跑的总轮数，0 = 无限 */
    fun effectiveLoop(): Int = when (runMode) {
        RunMode.ONCE -> 1
        RunMode.TIME -> 0
        else -> loopCount
    }

    companion object {
        fun fromJson(o: JSONObject): ClickConfig {
            val c = ClickConfig()
            val arr = o.optJSONArray("points")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { c.points.add(ClickPoint.fromJson(it)) }
                }
            }
            c.runMode = o.optInt("runMode", RunMode.LOOP).coerceIn(0, 2)
            c.loopCount = o.optInt("loopCount", 0).coerceAtLeast(0)
            c.runSeconds = o.optInt("runSeconds", 60).coerceIn(5, 86400)
            c.startDelaySec = o.optInt("startDelaySec", 3).coerceIn(0, 60)
            c.jitterTimePct = o.optInt("jitterTimePct", 0).coerceIn(0, 50)
            c.jitterPosPx = o.optInt("jitterPosPx", 0).coerceIn(0, 40)
            c.lockPackage = optStringOrNull(o, "lockPackage")
            c.lockLabel = optStringOrNull(o, "lockLabel")
            c.volumeKeyToggle = o.optBoolean("volumeKeyToggle", false)
            c.pauseOnScreenOff = o.optBoolean("pauseOnScreenOff", true)
            c.logging = o.optBoolean("logging", true)
            c.verboseLog = o.optBoolean("verboseLog", false)
            c.showPanel = o.optBoolean("showPanel", true)
            return c
        }

        private fun optStringOrNull(o: JSONObject, key: String): String? {
            if (o.isNull(key)) return null
            val v = o.optString(key, "")
            return if (v.isEmpty() || v == "null") null else v
        }

        fun parse(text: String): ClickConfig = fromJson(JSONObject(text))
    }
}
