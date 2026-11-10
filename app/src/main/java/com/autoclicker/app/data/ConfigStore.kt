package com.autoclicker.app.data

import android.content.Context
import com.autoclicker.app.model.ClickConfig
import java.io.File

/**
 * 配置与方案的本地存储。整个 App 只有一个进程，因此用一个内存缓存 + SharedPreferences 兜住读写。
 */
object ConfigStore {

    private const val PREFS = "autoclicker_prefs"
    private const val KEY_CONFIG = "config_json"
    private const val KEY_PANEL_X = "panel_x"
    private const val KEY_PANEL_Y = "panel_y"
    private const val KEY_SCHEME = "scheme_name"

    @Volatile
    private var cached: ClickConfig? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(ctx: Context): ClickConfig {
        cached?.let { return it }
        val raw = prefs(ctx).getString(KEY_CONFIG, null)
        val loaded = if (raw.isNullOrEmpty()) {
            ClickConfig()
        } else {
            runCatching { ClickConfig.parse(raw) }.getOrElse { ClickConfig() }
        }
        cached = loaded
        return loaded
    }

    fun save(ctx: Context, config: ClickConfig = current(ctx)) {
        cached = config
        prefs(ctx).edit().putString(KEY_CONFIG, config.toJson().toString()).apply()
    }

    fun panelPos(ctx: Context): Pair<Int, Int> =
        prefs(ctx).getInt(KEY_PANEL_X, -1) to prefs(ctx).getInt(KEY_PANEL_Y, -1)

    fun savePanelPos(ctx: Context, x: Int, y: Int) {
        prefs(ctx).edit().putInt(KEY_PANEL_X, x).putInt(KEY_PANEL_Y, y).apply()
    }

    /** 当前正在编辑的方案名，null 表示未命名（未保存） */
    fun schemeName(ctx: Context): String? = prefs(ctx).getString(KEY_SCHEME, null)

    fun setSchemeName(ctx: Context, name: String?) {
        prefs(ctx).edit().apply {
            if (name == null) remove(KEY_SCHEME) else putString(KEY_SCHEME, name)
        }.apply()
    }

    // ---------------- 方案（脚本） ----------------

    fun scriptDir(ctx: Context): File = File(ctx.filesDir, "scripts").apply { if (!exists()) mkdirs() }

    fun listScripts(ctx: Context): List<String> =
        scriptDir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sorted()
            ?: emptyList()

    fun saveScript(ctx: Context, name: String, json: String): File =
        File(scriptDir(ctx), sanitize(name) + ".json").apply { writeText(json) }

    fun readScript(ctx: Context, name: String): String? =
        File(scriptDir(ctx), name + ".json").takeIf { it.exists() }?.readText()

    fun deleteScript(ctx: Context, name: String) {
        File(scriptDir(ctx), name + ".json").delete()
    }

    /** 方案展示名（可能带空格）和落盘文件名（下划线）不是一回事，比较前先归一 */
    fun sameAsFile(displayName: String?, fileName: String): Boolean =
        displayName != null && sanitize(displayName) == fileName

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").ifBlank { "script" }
}
