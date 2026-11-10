package com.autoclicker.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.autoclicker.app.R

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.col(id: Int): Int = if (Build.VERSION.SDK_INT >= 23) getColor(id) else resources.getColor(id)

fun Context.roundBg(
    fill: Int,
    radiusDp: Number = 12,
    strokeColor: Int = 0,
    strokeDp: Number = 1
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(radiusDp).toFloat()
    setColor(fill)
    if (strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
}

fun Context.tv(
    text: CharSequence,
    sizeSp: Float = 14f,
    colorId: Int = R.color.text_primary,
    bold: Boolean = false
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(col(colorId))
    includeFontPadding = false
    if (bold) setTypeface(Typeface.create(typeface, Typeface.BOLD))
}

fun Context.btn(
    text: CharSequence,
    primary: Boolean = false,
    onTap: () -> Unit
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    gravity = Gravity.CENTER
    includeFontPadding = false
    setPadding(dp(16), dp(9), dp(16), dp(9))
    isClickable = true
    isFocusable = true
    if (primary) {
        setTextColor(col(R.color.accent_on))
        background = roundBg(col(R.color.accent), 10)
    } else {
        setTextColor(col(R.color.text_primary))
        background = roundBg(col(R.color.surface_alt), 10, col(R.color.stroke))
    }
    setOnClickListener { onTap() }
}

fun Context.card(radiusDp: Number = 16): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = roundBg(col(R.color.surface), radiusDp, col(R.color.stroke))
    setPadding(dp(16), dp(14), dp(16), dp(14))
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
}

fun Context.hdivider(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    setBackgroundColor(col(R.color.divider))
}

fun View.lp(
    width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    left: Int = 0,
    top: Int = 0,
    right: Int = 0,
    bottom: Int = 0
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height).apply {
    setMargins(left, top, right, bottom)
}

/** 给已经 addView（或即将 addView）的 View 设置外边距，保留原有宽高 */
fun <T : View> T.m(
    top: Int = 0,
    bottom: Int = 0,
    left: Int = 0,
    right: Int = 0
): T {
    val current = layoutParams
    val w = current?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
    val h = current?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
    layoutParams = LinearLayout.LayoutParams(w, h).apply { setMargins(left, top, right, bottom) }
    return this
}
