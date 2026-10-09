package com.ambientrgb

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * A hue/saturation colour wheel. Tap or drag on it, or (when it has focus) push the left stick
 * towards the colour you want - how far you push sets how strong the colour is.
 */
class ColorWheel(ctx: Context) : View(ctx) {
    var hue = 0f
    var sat = 1f
    var onChange: (() -> Unit)? = null
    private val d = ctx.resources.displayMetrics.density
    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE }
    private var cx = 0f; private var cy = 0f; private var r = 0f

    init { isFocusable = true; isClickable = true }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f; r = min(w, h) / 2f - 8 * d
        val hues = IntArray(13) { Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f)) }
        wheel.shader = SweepGradient(cx, cy, hues, null)
        white.shader = RadialGradient(cx, cy, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawCircle(cx, cy, r, wheel)
        c.drawCircle(cx, cy, r, white)
        if (isFocused) c.drawCircle(cx, cy, r + 5 * d, ring)
        val a = Math.toRadians(hue.toDouble())
        val mx = cx + (r * sat * cos(a)).toFloat()
        val my = cy + (r * sat * sin(a)).toFloat()
        markerFill.color = Color.HSVToColor(floatArrayOf(hue, sat, 1f))
        c.drawCircle(mx, my, 11 * d, markerFill)
        c.drawCircle(mx, my, 11 * d, marker)
    }

    private fun setFrom(dx: Float, dy: Float, radius: Float) {
        hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360).toFloat()
        sat = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        onChange?.invoke()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            if (!isFocused) requestFocus()
            setFrom(e.x - cx, e.y - cy, r)
            return true
        }
        return super.onTouchEvent(e)
    }

    /** Left stick points at a colour; the D-pad (HAT) is left alone so it still moves focus. */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_JOYSTICK) && e.action == MotionEvent.ACTION_MOVE) {
            val x = e.getAxisValue(MotionEvent.AXIS_X)
            val y = e.getAxisValue(MotionEvent.AXIS_Y)
            val m = hypot(x, y)
            if (m > 0.25f) {
                hue = ((Math.toDegrees(atan2(y, x).toDouble()) + 360) % 360).toFloat()
                sat = ((m - 0.15f) / 0.8f).coerceIn(0f, 1f)
                onChange?.invoke()
                invalidate()
                return true
            }
            if (m > 0.05f) return true      // swallow small stick movements so they don't move focus
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }
}

/** AYASpace-styled colour picker dialog: wheel + brightness, quick colours, recent colours. */
object ColorPicker {

    fun show(act: Activity, title: String, initial: Int, onPick: (Int) -> Unit) {
        val ui = Ui(act)
        val store = Store(act)
        val hsv = FloatArray(3)
        Color.colorToHSV(0xFF000000.toInt() or initial, hsv)
        if (hsv[2] < 0.05f) hsv[2] = 1f

        val root = ui.hbox().apply {
            gravity = Gravity.TOP
            background = ui.rounded(Aya.DIALOG, 8f, 1f, Aya.DIVIDER)
            setPadding(ui.px(18), ui.px(14), ui.px(18), ui.px(14))
        }

        // left: the wheel
        val leftCol = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val wheel = ColorWheel(act).apply { hue = hsv[0]; sat = hsv[1] }
        leftCol.addView(wheel, LinearLayout.LayoutParams(ui.px(212), ui.px(212)))
        leftCol.addView(ui.text("Tap, or push the left stick toward a colour", 12f, Aya.DIMMER).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.px(8), 0, 0)
        }, LinearLayout.LayoutParams(ui.px(212), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(leftCol)

        // right: preview, brightness, quick + recent colours, buttons
        val rightCol = ui.vbox().apply { setPadding(ui.px(22), 0, 0, 0) }
        root.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rightCol.addView(ui.text(title, 18f, weight = 700))
        val head = ui.hbox()
        val preview = View(act)
        val hex = ui.text("", 14f, Aya.DIM, weight = 500).apply { typeface = android.graphics.Typeface.MONOSPACE }
        head.addView(preview, LinearLayout.LayoutParams(ui.px(90), ui.px(38)))
        head.addView(hex, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(14) })
        rightCol.addView(head, ui.lp(top = 10))

        fun current() = Color.HSVToColor(hsv) and 0xFFFFFF
        fun refresh() {
            val c = current()
            preview.background = ui.rounded(0xFF000000.toInt() or c, 12f, 1f, 0x33FFFFFF)
            hex.text = String.format("#%06X", c)
        }

        rightCol.addView(ui.sectionTitle("Brightness").apply { setPadding(0, ui.px(12), 0, ui.px(2)) })
        val valBar = SeekBar(act).apply {
            max = 100
            progress = (hsv[2] * 100).toInt()
            fun gradient() = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))).apply { cornerRadius = ui.pxf(8f) }
            progressDrawable = gradient()
            minHeight = ui.px(12); maxHeight = ui.px(12)
            thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Aya.TEXT); setStroke(ui.px(1), 0x33000000); setSize(ui.px(24), ui.px(24)) }
            splitTrack = false
            keyProgressIncrement = 5
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ui.rounded(0x14FFFFFF, 16f, 2f, Aya.TEXT))
                addState(intArrayOf(), ui.rounded(0x00000000, 16f))
            }
            setPadding(ui.px(14), ui.px(10), ui.px(14), ui.px(10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { hsv[2] = p / 100f; refresh() } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        rightCol.addView(valBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(44)))
        fun refreshBar() {
            valBar.progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))).apply { cornerRadius = ui.pxf(8f) }
        }
        wheel.onChange = { hsv[0] = wheel.hue; hsv[1] = wheel.sat; refresh(); refreshBar() }

        fun pickChip(c: Int) {
            Color.colorToHSV(0xFF000000.toInt() or c, hsv)
            wheel.hue = hsv[0]; wheel.sat = hsv[1]; wheel.invalidate()
            valBar.progress = (hsv[2] * 100).toInt()
            refresh(); refreshBar()
        }
        rightCol.addView(ui.sectionTitle("Colours").apply { setPadding(0, ui.px(10), 0, ui.px(2)) })
        rightCol.addView(ui.rows(Ui.QUICK.map { c -> ui.chip(c, 28, { pickChip(c) }) }, 8))
        val recent = store.recentColours()
        if (recent.isNotEmpty()) {
            rightCol.addView(ui.sectionTitle("Recent").apply { setPadding(0, ui.px(6), 0, ui.px(2)) })
            rightCol.addView(ui.rows(recent.map { c -> ui.chip(c, 28, { pickChip(c) }) }, 8))
        }

        val buttons = ui.hbox().apply { gravity = Gravity.END }
        rightCol.addView(buttons, ui.lp(top = 14))
        refresh()

        val dlg = AlertDialog.Builder(act).setView(root).create()
        buttons.addView(ui.pill("Cancel") { dlg.dismiss() })
        buttons.addView(ui.pill("Use colour", primary = true) {
            val c = current()
            store.addRecent(c)
            onPick(c)
            dlg.dismiss()
        }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(12) })
        dlg.window?.setBackgroundDrawable(ColorDrawable(0))
        dlg.show()
        val dm = act.resources.displayMetrics
        dlg.window?.setLayout((dm.widthPixels * 0.95).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        wheel.requestFocus()
    }
}
