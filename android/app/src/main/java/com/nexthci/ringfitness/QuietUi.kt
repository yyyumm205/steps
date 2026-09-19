package com.nexthci.ringfitness

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Small shared native-view vocabulary for the collection task. */
internal class QuietUi(private val context: Context) {
    val background = context.getColor(R.color.quiet_background)
    val ink = context.getColor(R.color.quiet_ink)
    val secondary = context.getColor(R.color.quiet_secondary)
    val accent = context.getColor(R.color.quiet_accent)
    val surface = context.getColor(R.color.quiet_surface)
    val statusSurface = context.getColor(R.color.quiet_status)
    val outline = context.getColor(R.color.quiet_outline)
    val error = ink

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
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(secondary, if (primary) surface else accent)))
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(this@QuietUi.background, 18, outline))
            addState(intArrayOf(android.R.attr.state_focused), shape(if (primary) accent else surface, 18, ink))
            addState(intArrayOf(), shape(if (primary) accent else surface, 18, if (primary) null else accent))
        }
        background = RippleDrawable(ColorStateList.valueOf(context.getColor(
            if (primary) R.color.quiet_on_accent_ripple else R.color.quiet_ripple)), states, shape(surface, 18))
        elevation = 0f
        stateListAnimator = null
        gravity = Gravity.CENTER
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    fun input(parent: LinearLayout, hintText: String, inputTag: String, numeric: Boolean = false): EditText = EditText(context).apply {
        tag = inputTag
        hint = hintText
        inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER else
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true)
        styleInput(this, numeric)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun styleInput(input: EditText, numeric: Boolean = false) = input.apply {
        setTextColor(ink)
        setHintTextColor(secondary)
        textSize = if (numeric) 32f else 18f
        minimumHeight = dp(56)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(surface, 16, accent).apply { setStroke(dp(2), accent) })
            addState(intArrayOf(), shape(surface, 16, outline))
        }
        backgroundTintList = null
    }

    fun linkBackground() = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.quiet_ripple)),
        shape(Color.TRANSPARENT, 12), shape(surface, 12))

    fun placementAdapter(labels: List<String>) = object : ArrayAdapter<String>(context,
        android.R.layout.simple_spinner_dropdown_item, labels) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            styleRow(super.getView(position, convertView, parent), false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            styleRow(super.getDropDownView(position, convertView, parent), true)
        private fun styleRow(view: View, dropdown: Boolean): View = (view as TextView).apply {
            layoutParams = layoutParams?.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            setTextColor(ink)
            textSize = 16f
            setSingleLine(false)
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = if (dropdown) StateListDrawable().apply {
                for (state in listOf(android.R.attr.state_checked, android.R.attr.state_selected,
                    android.R.attr.state_focused, android.R.attr.state_pressed, android.R.attr.state_activated)) {
                    addState(intArrayOf(state), shape(statusSurface, 8))
                }
                addState(intArrayOf(), shape(surface, 8))
            } else shape(surface, 8)
        }
    }

    fun progress() = ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(accent) }
}
