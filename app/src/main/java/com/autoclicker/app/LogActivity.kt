package com.autoclicker.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.autoclicker.app.data.Logs
import com.autoclicker.app.service.ClickAccessibilityService
import com.autoclicker.app.ui.btn
import com.autoclicker.app.ui.col
import com.autoclicker.app.ui.dp
import com.autoclicker.app.ui.m
import com.autoclicker.app.ui.roundBg
import com.autoclicker.app.ui.tv

class LogActivity : Activity() {

    private lateinit var box: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logs.init(this)
        setContentView(build())
        render()
    }

    override fun onResume() {
        super.onResume()
        ClickAccessibilityService.onUiResume()
    }

    override fun onPause() {
        super.onPause()
        ClickAccessibilityService.onUiPause()
    }

    private fun build(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(col(R.color.bg))
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            tv("运行日志", 18f, R.color.text_primary, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(btn("复制") {
            val text = Logs.snapshot().joinToString("\n")
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        })
        head.addView(btn("导出") { export() }.also { it.m(left = dp(8)) })
        head.addView(btn("清空") {
            Logs.clear()
            render()
        }.also { it.m(left = dp(8)) })
        root.addView(head)

        val scroll = ScrollView(this)
        box = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextColor(col(R.color.text_primary))
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundBg(col(R.color.surface), 12, col(R.color.stroke))
        }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(12) })
        return root
    }

    private fun render() {
        val lines = Logs.snapshot()
        box.text = if (lines.isEmpty()) "暂无日志。开启服务并运行一次点击后，这里会记录启停、计数与异常。" else
            lines.joinToString("\n")
    }

    private fun export() {
        // 文件选择器是全屏 Activity，先把悬浮条收起来
        ClickAccessibilityService.beginExternalUi()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "autoclicker-log-${System.currentTimeMillis()}.txt")
        }
        runCatching { startActivityForResult(intent, RC_EXPORT) }
            .onFailure { Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ClickAccessibilityService.endExternalUi()
        if (requestCode != RC_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            val text = Logs.snapshot().joinToString("\n")
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("无法写入")
            Toast.makeText(this, "已导出日志", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, "导出失败：${it.message}", Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val RC_EXPORT = 2001
    }
}
