package com.nexthci.ringfitness

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Small shared native-view vocabulary for the collection task. */
internal class QuietUi(private val context: Context) {
    val background = Color.rgb(247, 249, 248)
    val ink = Color.rgb(30, 49, 45)
    val secondary = Color.rgb(92, 111, 105)
    val accent = Color.rgb(23, 107, 85)
    val surface = Color.WHITE
    val softGreen = Color.rgb(228, 240, 234)
    val softPurple = Color.rgb(239, 235, 249)
    val purpleInk = Color.rgb(92, 76, 129)
    val error = Color.rgb(151, 57, 49)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 24, border: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }

    fun column(parent: LinearLayout? = null, padding: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        parent?.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun card(parent: LinearLayout, color: Int = surface): LinearLayout = column(padding = 22).apply {
        background = shape(color)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }

    fun text(parent: LinearLayout, value: String, size: Float = 16f, muted: Boolean = false, bold: Boolean = false): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (muted) secondary else ink)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
        includeFontPadding = false
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun gap(parent: LinearLayout, height: Int) = parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height)))

    fun button(parent: LinearLayout, title: String, primary: Boolean = false, tag: String? = null, action: () -> Unit): Button = Button(context).apply {
        text = title
        textSize = 16f
        isAllCaps = false
        this.tag = tag
        minHeight = dp(56)
        minimumHeight = dp(56)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setTextColor(if (primary) Color.WHITE else accent)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(35, 23, 107, 85)),
            shape(if (primary) accent else softGreen, 18), null)
        elevation = 0f
        stateListAnimator = null
        gravity = Gravity.CENTER
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    fun input(parent: LinearLayout, hintText: String, inputTag: String, numeric: Boolean = false): EditText = EditText(context).apply {
        tag = inputTag
        hint = hintText
        setTextColor(ink)
        setHintTextColor(secondary)
        textSize = if (numeric) 44f else 18f
        inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER else
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = shape(this@QuietUi.background, 16, Color.rgb(213, 224, 219))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
}
