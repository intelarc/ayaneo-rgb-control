package com.ambientrgb

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Lights the 8 LEDs one at a time (white) and asks where each one is.
 * Result: cfg.layout[position] = hardware LED index.
 */
class Calibrator(private val act: Activity, private val cfg: AppConfig, private val onDone: () -> Unit) {
    private val ui = Ui(act)
    private val found = IntArray(8) { -1 }      // position -> LED index
    private var step = 0
    private var wasRunning = false
    private var dlg: AlertDialog? = null

    fun start() {
        wasRunning = LightService.running
        if (wasRunning) LightService.stop(act)
        showStep()
    }

    private fun light(i: Int) {
        LedBackend.setEach(act, IntArray(8) { if (it == i) 0xFFFFFF else 0 }, 1f)
    }

    private fun showStep() {
        dlg?.dismiss()
        if (step >= 8) { finish(); return }
        // give the service a moment to switch the lights off before lighting ours
        act.window.decorView.postDelayed({ light(step) }, if (step == 0 && wasRunning) 300 else 0)

        val box = ui.vbox().apply {
            background = ui.rounded(Aya.DIALOG, 8f, 1f, Aya.DIVIDER)
            setPadding(ui.px(22), ui.px(18), ui.px(22), ui.px(18))
        }
        box.addView(ui.text("Where is the light?", 19f, weight = 700))
        box.addView(ui.text("LED ${step + 1} of 8 is lit white. Pick its position.", 14f, Aya.DIM).apply { setPadding(0, ui.px(6), 0, ui.px(12)) })
        val sticks = ui.hbox()
        for (s in 0..1) {
            val col = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(ui.sectionTitle(if (s == 0) "Left stick" else "Right stick").apply { setPadding(0, 0, 0, ui.px(6)) })
            for (r in 0..1) {
                val line = ui.hbox()
                val positions = if (r == 0) intArrayOf(Pos.UL, Pos.UR) else intArrayOf(Pos.LL, Pos.LR)
                for (pos in positions) {
                    val p = s * 4 + pos
                    val taken = found[p] >= 0
                    line.addView(ui.pill(if (taken) "LED ${found[p] + 1}" else Pos.NAMES[pos], selected = false, textSp = 13f) {
                        found.indices.filter { found[it] == step }.forEach { found[it] = -1 }
                        found[p] = step
                        step++
                        showStep()
                    }.apply { isEnabled = !taken; alpha = if (taken) 0.4f else 1f },
                        LinearLayout.LayoutParams(ui.px(122), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(ui.px(4), ui.px(4), ui.px(4), ui.px(4)) })
                }
                col.addView(line)
            }
            sticks.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(sticks)
        val bottom = ui.hbox().apply { gravity = Gravity.END }
        bottom.addView(ui.pill("Can't see it") { step++; showStep() })
        bottom.addView(ui.pill("Cancel") { cancel() }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(12) })
        box.addView(bottom, ui.lp(top = 16))

        val d = AlertDialog.Builder(act).setView(box).setOnCancelListener { cancel() }.create()
        d.window?.setBackgroundDrawable(ColorDrawable(0))
        d.show()
        d.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.95).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg = d
    }

    private fun finish() {
        // fill unanswered positions with the leftover LED indices so every position still maps somewhere
        val used = found.filter { it >= 0 }.toSet()
        val spare = (0 until 8).filter { it !in used }.iterator()
        for (p in 0 until 8) if (found[p] < 0) found[p] = if (spare.hasNext()) spare.next() else cfg.layout[p]
        cfg.layout = found.copyOf()
        cfg.calibrated = true
        restore()
        onDone()
    }

    private fun cancel() {
        dlg?.dismiss()
        restore()
        onDone()
    }

    private fun restore() {
        LedBackend.off(act)
        if (wasRunning || cfg.enabled) {
            if (cfg.enabled) LightService.start(act)
        }
    }
}
