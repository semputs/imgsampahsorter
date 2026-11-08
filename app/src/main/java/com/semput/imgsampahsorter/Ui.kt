package com.semput.imgsampahsorter

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

object Ui {
    val BG = Color.parseColor("#121212")
    val SURFACE = Color.parseColor("#1E1E1E")
    val RED = Color.parseColor("#E53935")
    val GREEN = Color.parseColor("#43A047")
    val BLUE = Color.parseColor("#3F51B5")
    val GREY = Color.parseColor("#424242")
    val TEXT = Color.parseColor("#EEEEEE")
    val TEXT_DIM = Color.parseColor("#9E9E9E")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int = 24): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun button(ctx: Context, label: String, color: Int): TextView = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        val p = dp(ctx, 12)
        setPadding(p, p, p, p)
        background = rounded(ctx, color)
        isClickable = true
        isFocusable = true
    }

    fun text(ctx: Context, s: String, sizeSp: Float = 16f, color: Int = TEXT): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
        }
}
