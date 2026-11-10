package com.autoclicker.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 极简运行日志：内存环形缓冲给界面看，同时追加到 filesDir/clicks.log。
 */
object Logs {

    private const val MAX_BUFFER = 600
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val buffer = ArrayDeque<String>()
    // 带毫秒：调间隔抖动 / 排查掉帧时要看清两次点击到底隔了多久
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    @Volatile
    private var enabled = true

    @Volatile
    private var lastMsg: String? = null
    private var lastAt: Long = 0

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "clicks.log")
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun add(msg: String) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val line = fmt.format(Date(now)) + "  " + msg
        synchronized(buffer) {
            // 相同内容 300ms 内合并，避免 30ms 间隔把日志刷爆
            if (msg == lastMsg && now - lastAt < 300) return
            lastMsg = msg
            lastAt = now
            buffer.addLast(line)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
        }
        runCatching {
            val f = file ?: return
            if (f.exists() && f.length() > MAX_FILE_BYTES) f.writeText("")
            f.appendText(line + "\n")
        }
    }

    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        runCatching { file?.writeText("") }
    }

    fun fileText(): String = runCatching { file?.takeIf { it.exists() }?.readText() ?: "" }.getOrDefault("")
}
