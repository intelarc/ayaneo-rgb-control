package com.ambientrgb

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.random.Random

/**
 * Steam-style layout: a top bar with page tabs (L1 / R1), a fixed preview pane on the left with the
 * device photo, the current look, power and brightness, and the page's controls scrolling on the right.
 * Both sticks always share one look.
 */
class MainActivity : Activity() {

    private enum class Page(val title: String) { HOME("Lights"), MODES("Modes"), THEMES("Themes"), SETTINGS("Settings") }

    /** A live mini ring on a tile, animated with its own configuration. */
    private class Tile(val ring: MiniRing, val config: AppConfig)

    private lateinit var ui: Ui
    private lateinit var store: Store
    private lateinit var cfg: AppConfig
    private val handler = Handler(Looper.getMainLooper())
    private var page = Page.HOME
    private var category: Category? = null
    private var themeFilter = 0
    private val tabs = LinkedHashMap<Page, TopTab>()
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pane: View
    private lateinit var hero: DeviceView
    private lateinit var lookName: TextView
    private lateinit var lookSub: TextView
    private lateinit var powerToggle: Toggle
    private lateinit var powerSub: TextView
    private lateinit var focusPark: View
    private val tiles = ArrayList<Tile>()
    private val frame = IntArray(8)
    private val tileFrame = IntArray(8)
    private val visRect = Rect()
    private val t0 = SystemClock.uptimeMillis()
    private var refreshOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = Store(this)
        cfg = store.load()
        Aya.setAccent(cfg.accent)
        window.statusBarColor = Aya.BAR
        LedBackend.init(this)
        setContentView(buildShell())
        showPage(Page.HOME)
        focusFirst()
        if (cfg.enabled && !LightService.running) LightService.start(this)
        handleTest(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleTest(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(animator)
        if (refreshOnResume) { refreshOnResume = false; refresh() }   // back from Accessibility settings
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(animator)       // nothing animates while the app isn't on screen
    }

    /** One UI clock (20 fps): the preview photo and the mini rings that are on screen. */
    private val animator = object : Runnable {
        override fun run() {
            val t = SystemClock.uptimeMillis() - t0
            if (pane.visibility == View.VISIBLE) {
                Engine.render(cfg, t, frame)
                if (!cfg.enabled) for (i in frame.indices) frame[i] = Engine.scale(frame[i], 0.3)
                hero.setColors(frame)
            }
            for (tl in tiles) {
                if (!tl.ring.getGlobalVisibleRect(visRect)) continue      // skip tiles scrolled off screen
                Engine.render(tl.config, t, tileFrame)
                tl.ring.setColors(tileFrame, 0)
            }
            handler.postDelayed(this, 50)
        }
    }

    /** L1 / R1 switch tabs, like the Steam Deck. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val pages = Page.values()
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L1 -> { goTo(pages[(page.ordinal + pages.size - 1) % pages.size]); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { goTo(pages[(page.ordinal + 1) % pages.size]); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun goTo(p: Page) {
        showPage(p)
        focusFirst()
    }

    /** Controller focus onto the first control of the page. */
    private fun focusFirst() {
        scroll.post { if (!scroll.isInTouchMode) content.focusSearchNear(0, 0)?.requestFocus() }
    }

    private fun save() = store.save(cfg)

    /** Both sticks share cfg.left. A theme can style them differently; any edit makes them one look again. */
    private fun commit() { cfg.sync = true; save() }

    private fun setEnabled(on: Boolean) {
        cfg.enabled = on
        save()
        if (on) LightService.start(this)    // the service stops itself when it sees enabled = false
        else handler.postDelayed({ if (!cfg.enabled) LedBackend.off(this) }, 200)
    }

    private fun setEffect(e: Effect) {
        cfg.sync = true
        cfg.left.effect = e
        if (e == Effect.MUSIC) ensureMic()
        if (!cfg.enabled) setEnabled(true) else save()
        refresh()
    }

    /** One-tap colour: keep the current mode if it can use a colour, otherwise switch to Static. */
    private fun quickColour(s: StickConfig, c: Int) {
        when {
            s.effect == Effect.SOLID -> s.ledColors.fill(c)
            s.effect.colours == 0 || s.effect == Effect.OFF -> { s.effect = Effect.STATIC; s.palette = mutableListOf(c) }
            else -> s.palette = mutableListOf(c)
        }
    }

    private fun applyColour(c: Int) {
        cfg.sync = true
        quickColour(cfg.left, c)
        if (!cfg.enabled) setEnabled(true) else save()
        refresh()
    }

    // ================================================================ permissions / services

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureMic() {
        if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && cfg.enabled) LightService.start(this)
        refresh()
    }

    private fun keyServiceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { it.startsWith("$packageName/") }
    }

    private fun openAccessibility() {
        refreshOnResume = true
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Turn on \"RGB Control – reactive lights\"", Toast.LENGTH_LONG).show()
    }

    // ================================================================ shell

    private fun buildShell(): View {
        val root = ui.vbox().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Aya.BG_TOP, Aya.BG))
        }

        // ---- top bar: logo, L1 [tabs] R1, device badge
        val bar = ui.hbox().apply { setBackgroundColor(Aya.BAR); setPadding(ui.px(16), 0, ui.px(16), 0) }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ayaneo_emblem); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(ui.px(31), ui.px(12)))
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ayaneo_wordmark); scaleType = ImageView.ScaleType.FIT_START; contentDescription = "AYANEO" },
            LinearLayout.LayoutParams(ui.px(62), ui.px(11)).apply { leftMargin = ui.px(9) })
        bar.addView(View(this).apply { setBackgroundColor(Aya.FILL_2) }, LinearLayout.LayoutParams(maxOf(1, ui.density.toInt()), ui.px(18)).apply { leftMargin = ui.px(12); rightMargin = ui.px(12) })
        bar.addView(ui.text("RGB", 17f, Aya.WHITE, weight = 800))
        bar.addView(ui.text("Control", 17f, Aya.ACCENT, weight = 600), ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(5) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(glyph("L1"), ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.px(4) })
        for (p in Page.values()) {
            val t = TopTab(this, ui, p.title).apply { setOnClickListener { goTo(p) } }
            tabs[p] = t
            bar.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        bar.addView(glyph("R1"), ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(4) })
        // parks the controller focus while a page re-renders, so removing the focused view never scrolls the page
        focusPark = View(this).apply { isFocusable = false }
        bar.addView(focusPark, LinearLayout.LayoutParams(1, 1))
        root.addView(bar, ui.lp(h = ui.px(48)))
        root.addView(View(this).apply { setBackgroundColor(Aya.DIVIDER) }, ui.lp(h = maxOf(1, ui.density.toInt())))

        // ---- body: fixed preview pane + scrolling controls
        val body = ui.hbox().apply { gravity = Gravity.TOP }
        pane = buildPane()
        body.addView(pane, LinearLayout.LayoutParams(ui.px(PANE_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        content = ui.vbox().apply { setPadding(ui.px(PAD_DP), ui.px(2), ui.px(PAD_DP), ui.px(18)) }
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, ui.lp(h = 0, weight = 1f))

        // ---- footer: controller hints
        root.addView(View(this).apply { setBackgroundColor(Aya.DIVIDER) }, ui.lp(h = maxOf(1, ui.density.toInt())))
        val footer = ui.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setBackgroundColor(Aya.BAR)
            setPadding(ui.px(16), 0, ui.px(4), 0)
        }
        fun hint(key: String, label: String) {
            footer.addView(glyph(key))
            footer.addView(ui.text(label, 12.5f, Aya.DIM, weight = 500).apply { setPadding(ui.px(6), 0, ui.px(16), 0) })
        }
        hint("A", "Select"); hint("B", "Back")
        root.addView(footer, ui.lp(h = ui.px(32)))
        return root
    }

    /** Steam Deck style button glyph. */
    private fun glyph(key: String) = ui.text(key, 10.5f, Aya.BAR, weight = 800).apply {
        background = ui.rounded(Aya.TEXT, 999f); gravity = Gravity.CENTER
        setPadding(ui.px(6), ui.px(2), ui.px(6), ui.px(2)); minWidth = ui.px(18)
    }

    /** Left pane: live device preview, what's on, power and master brightness. Never scrolls away. */
    private fun buildPane(): View {
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; setBackgroundColor(Aya.PANE) }
        val p = ui.vbox().apply { setPadding(ui.px(14), ui.px(12), ui.px(14), ui.px(12)) }
        hero = DeviceView(this)
        p.addView(hero, ui.lp(h = ui.px(112)))
        p.addView(ImageView(this).apply { setImageResource(R.drawable.pocket_air_logo); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Pocket Air" },
            ui.lp(h = ui.px(13), top = 6))
        lookName = ui.text("", 19f, Aya.WHITE, weight = 700).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        p.addView(lookName, ui.lp(top = 10))
        lookSub = ui.text("", 12.5f, Aya.DIM, weight = 500).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, ui.px(4), 0, 0) }
        p.addView(lookSub)
        val power = ui.switchRow("Stick lights", "Off", cfg.enabled) { on -> setEnabled(on); refresh() }
        // switchRow = [texts(label, sub), toggle]
        powerToggle = (power as ViewGroup).getChildAt(1) as Toggle
        powerSub = (power.getChildAt(0) as ViewGroup).getChildAt(1) as TextView
        p.addView(ui.group(power), ui.lp(top = 10))
        p.addView(ui.group(ui.sliderRow("Brightness", 5, 100, cfg.brightness, "%") { cfg.brightness = it; save() }), ui.lp(top = 6))
        sv.addView(p)
        return sv
    }

    private fun refreshPane() {
        val split = !cfg.sync
        lookName.text = when {
            !cfg.enabled -> "Lights off"
            split -> "${cfg.left.effect.label} + ${cfg.right.effect.label}"
            else -> cfg.left.effect.label
        }
        lookSub.text = if (!cfg.enabled) "Turn on to light the sticks" else "${cfg.left.effect.cat.label} · both sticks"
        if (powerToggle.on != cfg.enabled) powerToggle.setOn(cfg.enabled, animate = false)
        powerSub.text = if (cfg.enabled) "On" else "Off"
    }

    /** Top bar tab: white + accent underline for the current page, light fill when focused. */
    private class TopTab(ctx: Context, private val ui: Ui, label: String) : TextView(ctx) {
        private val underline = Paint().apply { color = Aya.ACCENT }
        var current = false
            set(v) { field = v; typeface = ui.font(if (v) 700 else 500); colours(); invalidate() }

        init {
            text = label; textSize = 15f; gravity = Gravity.CENTER; includeFontPadding = false
            isFocusable = true; isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), InsetDrawable(ui.rounded(Aya.FOCUS_FILL, 4f), 0, ui.px(9), 0, ui.px(9)))
                addState(intArrayOf(android.R.attr.state_pressed), InsetDrawable(ui.rounded(Aya.CARD_FOCUS, 4f), 0, ui.px(9), 0, ui.px(9)))
                addState(intArrayOf(), ColorDrawable(0))
            }
            setPadding(ui.px(13), 0, ui.px(13), 0)       // after the background, which would reset it
            current = false
        }

        private fun colours() = setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Aya.ON_FOCUS, if (current) Aya.WHITE else Aya.DIM)))

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (current && !isFocused) c.drawRect(ui.pxf(13f), height - ui.pxf(3f), width - ui.pxf(13f), height.toFloat(), underline)
        }
    }

    // ================================================================ pages

    private fun showPage(p: Page) {
        if (p != page) { category = null; themeFilter = 0 }
        page = p
        for ((k, t) in tabs) t.current = k == p
        pane.visibility = if (p == Page.SETTINGS) View.GONE else View.VISIBLE
        refreshPane()
        buildContent()
        scroll.scrollTo(0, 0)
    }

    private fun buildContent() {
        content.removeAllViews()
        tiles.clear()
        when (page) {
            Page.HOME -> buildHome()
            Page.MODES -> buildModes()
            Page.THEMES -> buildThemes()
            Page.SETTINGS -> buildSettings()
        }
    }

    /**
     * Re-render the current page in place after a change: the scroll position stays put and the controller
     * focus lands back on the nearest control, all before the next frame is drawn (no flash, no jump).
     */
    private fun refresh() {
        refreshPane()
        val y = scroll.scrollY
        val focused = currentFocus
        val inContent = focused != null && isInside(focused, content)
        val loc = IntArray(2)
        if (inContent) {
            focused!!.getLocationOnScreen(loc)
            focusPark.isFocusable = true
            focusPark.requestFocus()
        }
        buildContent()
        scroll.scrollTo(0, y)
        content.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                content.viewTreeObserver.removeOnGlobalLayoutListener(this)
                scroll.scrollTo(0, y)
                if (inContent) (content.focusSearchNear(loc[0], loc[1]) ?: tabs[page])?.requestFocus()
                focusPark.isFocusable = false
            }
        })
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === parent) return true; p = p.parent as? View }
        return false
    }

    // ================================================================ home

    private val QUICK_MODES = listOf(Effect.STATIC, Effect.BREATHE, Effect.RAINBOW, Effect.SPIN, Effect.CHASE, Effect.FIRE, Effect.STARRY, Effect.MUSIC)

    private fun buildHome() {
        content.addView(ui.sectionTitle("Colour"))
        val current = cfg.left.effect.let { e -> if (e.colours == 0 && e != Effect.SOLID) -1 else if (e == Effect.SOLID) cfg.left.ledColors[0] else cfg.left.palette.first() }
        content.addView(ui.rows(Ui.QUICK.map { c -> ui.chip(c, chipDp(8, 0), { applyColour(c) }, selected = cfg.sync && c == current) }, 8))
        content.addView(ui.pill("Custom colour", textSp = 13.5f) {
            ColorPicker.show(this, "Colour for both sticks", cfg.left.palette.first()) { c -> applyColour(c) }
        }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 8).apply { leftMargin = ui.px(3) })

        content.addView(ui.sectionTitle("Mode"))
        content.addView(ui.rows(QUICK_MODES.map { e -> modeTile(e, vertical = true) { setEffect(e) } }, 4, equalWidth = true, gapDp = 3))
        content.addView(ui.pill("All ${Effect.values().size - 1} modes", textSp = 13.5f) { goTo(Page.MODES) },
            ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 8).apply { leftMargin = ui.px(3) })
    }

    /** A mode tile with a live mini ring. */
    private fun modeTile(e: Effect, vertical: Boolean, onClick: () -> Unit): View {
        val base = cfg.left
        val selected = cfg.sync && base.effect == e
        val tile = (if (vertical) ui.vbox() else ui.hbox()).apply {
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            background = ui.focusable(Aya.PANEL, 4f, Aya.CARD_FOCUS, if (selected) Aya.ACCENT else 0)
            setPadding(ui.px(8), ui.px(if (vertical) 10 else 7), ui.px(8), ui.px(if (vertical) 9 else 7))
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
        }
        ui.lift(tile)
        val ring = MiniRing(this)
        val size = if (vertical) 38 else 32
        tile.addView(ring, LinearLayout.LayoutParams(ui.px(size), ui.px(size)))
        tile.addView(ui.text(e.label, if (vertical) 12.5f else 13f, if (selected) Aya.WHITE else Aya.TEXT, weight = if (selected) 600 else 500).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            gravity = if (vertical) Gravity.CENTER else Gravity.START
            setPadding(if (vertical) 0 else ui.px(8), if (vertical) ui.px(6) else 0, 0, 0)
        }, if (vertical) ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT) else ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
        val demo = AppConfig().apply {
            sync = true; mirror = false
            left = base.copy().also { it.effect = e; it.ledOn.fill(true); it.brightness = 100 }
        }
        tiles += Tile(ring, demo)
        return tile
    }

    // ================================================================ modes

    private fun buildModes() {
        val s = cfg.left
        if (!cfg.sync) {
            val c = ui.card().apply { background = ui.rounded(Aya.PANEL, 4f, 1f, Aya.accentAlpha(0x80)) }
            c.addView(ui.text("This theme gives each stick its own look. Changing anything here puts both sticks on one look.", 13.5f, Aya.TEXT))
            content.addView(c, ui.lp(top = 12))
        }

        // ---- mode
        content.addView(ui.sectionTitle("Mode"))
        val cats = Category.values()
        val cat = category ?: s.effect.cat
        content.addView(ui.segmented(cats.map { it.label }, cats.indexOf(cat), fill = false) { i -> category = cats[i]; refresh() })
        val modes = Effect.values().filter { it.cat == cat && it != Effect.OFF } + if (cat == Category.BASIC) listOf(Effect.OFF) else emptyList()
        content.addView(ui.rows(modes.map { e -> modeTile(e, vertical = false) { setEffect(e) } }, 2, equalWidth = true, gapDp = 3), ui.lp(top = 8))
        val note = if (s.effect.global) " Spans both sticks." else ""
        val desc = if (s.effect.cat == cat) s.effect.desc + note else "Current mode: ${s.effect.label} (${s.effect.cat.label})"
        content.addView(ui.text(desc, 13.5f, Aya.DIM).apply { setPadding(ui.px(4), ui.px(10), ui.px(4), 0) })
        if (s.effect.cat == cat) smartNotice(s.effect)

        // ---- adjust
        content.addView(ui.sectionTitle("Adjust"))
        val adj = ArrayList<View>()
        adj += ui.sliderRow("Speed", 10, 300, s.speed, "%") { s.speed = it; commit() }
        adj += ui.sliderRow("Brightness", 0, 100, s.brightness, "%") { s.brightness = it; commit() }
        s.effect.amount?.let { label -> adj += ui.sliderRow(label, 0, 100, s.amount, "%") { s.amount = it; commit() } }
        adj += ui.switchRow("Reverse direction", null, s.reverse) { s.reverse = it; commit() }
        adj += ui.switchRow("Mirror the sticks", "Spins and comets turn opposite ways on each side", cfg.mirror) { cfg.mirror = it; commit() }
        adj += ui.switchRow("One ring across both sticks", "Spins, comets and waves travel from stick to stick", cfg.linked) { cfg.linked = it; commit() }
        content.addView(ui.group(*adj.toTypedArray()))

        // ---- colours
        content.addView(ui.sectionTitle("Colours"))
        val card = ui.card()
        val first = if (s.effect == Effect.SOLID) s.ledColors[0] else s.palette.first()
        card.addView(ui.text("Tap to use · hold to add", 13f, Aya.DIMMER).apply { setPadding(ui.px(3), 0, 0, ui.px(4)) })
        card.addView(ui.rows(Ui.QUICK.map { c ->
            ui.chip(c, chipDp(8, 32), { quickColour(s, c); commit(); refresh() }, {
                if (s.palette.size < 6) { s.palette.add(c); commit(); refresh() }
            }, selected = c == first)
        }, 8))
        card.addView(View(this).apply { setBackgroundColor(Aya.LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, ui.density.toInt())).apply { setMargins(0, ui.px(10), 0, ui.px(10)) })
        val palHead = ui.hbox()
        palHead.addView(ui.text("Palette", 15f, Aya.WHITE, weight = 600), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
        palHead.addView(ui.text(if (s.effect.colours == 0) "Not used by this mode" else "Select to edit · hold to remove", 12.5f, Aya.DIMMER))
        card.addView(palHead)
        val palRow = ui.hbox().apply { setPadding(0, ui.px(6), 0, 0) }
        val palDp = chipDp(7, 32)
        s.palette.forEachIndexed { idx, c ->
            palRow.addView(ui.chip(c, palDp, {
                ColorPicker.show(this, "Colour ${idx + 1}", c) { picked -> s.palette[idx] = picked; commit(); refresh() }
            }, { if (s.palette.size > 1) { s.palette.removeAt(idx); commit(); refresh() } }))
        }
        if (s.palette.size < 6) palRow.addView(addChip(palDp) {
            ColorPicker.show(this, "Add a colour", Random.nextInt(0xFFFFFF)) { picked -> s.palette.add(picked); commit(); refresh() }
        })
        card.addView(palRow)
        val palButtons = ui.hbox().apply { setPadding(0, ui.px(8), 0, 0) }
        palButtons.addView(ui.pill("Shuffle", textSp = 13f) {
            val n = s.palette.size.coerceAtLeast(2)
            val baseHue = Random.nextFloat() * 360f
            s.palette = MutableList(n) { k -> Color.HSVToColor(floatArrayOf((baseHue + k * 360f / n) % 360f, 1f, 1f)) and 0xFFFFFF }
            commit(); refresh()
        })
        palButtons.addView(ui.pill("Rainbow", textSp = 13f) {
            s.palette = mutableListOf(0xFF0000, 0xFF8000, 0xFFFF00, 0x00FF40, 0x0080FF, 0xA000FF)
            commit(); refresh()
        }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(8) })
        card.addView(palButtons)
        content.addView(card)

        // ---- LEDs
        content.addView(ui.sectionTitle("Individual LEDs"))
        val ledRows = intArrayOf(Pos.UL, Pos.UR, Pos.LR, Pos.LL).map { ledRow(s, it) }
        content.addView(ui.group(*ledRows.toTypedArray()))
        content.addView(ui.text("Each row sets that LED on both sticks" + (if (cfg.mirror) " (mirrored on the right)." else ".") +
            (if (s.effect == Effect.SOLID) " Select a swatch to change its colour." else " Swatch colours are used by the Per-LED mode; choosing one switches to it."),
            12.5f, Aya.DIMMER).apply { setPadding(ui.px(4), ui.px(8), ui.px(4), 0) })
    }

    /** One LED as a list row: colour swatch, position, on/off toggle. */
    private fun ledRow(s: StickConfig, pos: Int): View {
        val row = ui.hbox().apply { setPadding(ui.px(10), ui.px(6), ui.px(6), ui.px(6)) }
        row.addView(ui.chip(s.ledColors[pos], 36, {
            ColorPicker.show(this, "${Pos.NAMES[pos]} LED", s.ledColors[pos]) { picked ->
                s.ledColors[pos] = picked
                s.effect = Effect.SOLID
                commit(); refresh()
            }
        }))
        row.addView(ui.switchRow(Pos.NAMES[pos], null, s.ledOn[pos]) { on -> s.ledOn[pos] = on; commit() },
            ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f).apply { leftMargin = ui.px(4) })
        return row
    }

    private fun addChip(sizeDp: Int, onClick: () -> Unit) = TextView(this).apply {
        text = "+"; textSize = 22f; gravity = Gravity.CENTER; typeface = ui.font(300)
        setTextColor(Aya.DIM)
        background = ui.focusable(0x00000000, 6f, Aya.CARD_FOCUS, Aya.FILL_2)
        isFocusable = true; isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ui.px(sizeDp), ui.px(sizeDp)).apply { setMargins(ui.px(3), ui.px(3), ui.px(3), ui.px(3)) }
    }

    private fun smartNotice(e: Effect) {
        val (text, action, run) = when {
            e == Effect.MUSIC && !hasMic() -> Triple("Music mode measures how loud the sound playing on this device is. Nothing is recorded or stored.", "Allow sound access") { ensureMic() }
            e == Effect.MUSIC -> Triple("Play something with sound. The left stick follows the bass, the right stick the treble.", null, null)
            e == Effect.REACTIVE && !keyServiceEnabled() -> Triple("Reactive mode needs button detection. It only notices presses - it never blocks or changes them.", "Turn on button detection") { openAccessibility() }
            e == Effect.REACTIVE -> Triple("Button detection is on. The D-pad isn't detected on this unit; the other buttons are.", null, null)
            e == Effect.BATTERY -> Triple(if (Live.batteryPct >= 0) "Battery ${Live.batteryPct}%${if (Live.charging) ", charging" else ""}." else "Battery level appears once the lights are on.", null, null)
            e == Effect.TEMPERATURE -> Triple(if (Live.tempC > 0) "Battery ${Live.tempC} °C. Blue is about 30 °C, red about 50 °C." else "Temperature appears once the lights are on.", null, null)
            else -> return
        }
        val c = ui.card().apply { background = ui.rounded(Aya.PANEL, 4f, 1f, Aya.accentAlpha(0x80)) }
        c.addView(ui.text(text, 14f, Aya.TEXT))
        if (action != null && run != null) c.addView(ui.pill(action, primary = true, textSp = 13.5f) { run() }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 10))
        content.addView(c, ui.lp(top = 10))
    }

    // ================================================================ themes

    private fun buildThemes() {
        val filters = listOf("All") + Presets.CATEGORIES + "Saved"
        content.addView(ui.segmented(filters, themeFilter, fill = false) { i -> themeFilter = i; refresh() }, ui.lp(top = 10))
        val sel = filters[themeFilter]
        if (sel == "Saved") {
            val mine = store.userPresets()
            if (mine.isEmpty()) content.addView(ui.text("Nothing saved yet. Style the sticks, then save the look here.", 14f, Aya.DIM).apply { setPadding(ui.px(4), ui.px(14), 0, ui.px(4)) })
            else themeGrid(mine.map { Preset(it.first, "Saved", it.second) }, deletable = true)
            content.addView(ui.pill("Save current look", primary = true, textSp = 13.5f) {
                val list = store.userPresets().toMutableList()
                list.add("Look ${list.size + 1}" to cfg.copy())
                store.saveUserPresets(list)
                refresh()
            }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        } else {
            themeGrid(Presets.BUILT_IN.filter { sel == "All" || it.cat == sel }, deletable = false)
        }
    }

    private fun themeGrid(list: List<Preset>, deletable: Boolean) {
        val views = list.mapIndexed { i, preset ->
            val p = preset.config
            ui.hbox().apply {
                background = ui.focusable(Aya.PANEL, 4f, Aya.CARD_FOCUS)
                setPadding(ui.px(8), ui.px(8), ui.px(10), ui.px(8))
                isFocusable = true; isClickable = true
                ui.lift(this)
                setOnClickListener {
                    cfg.applyLook(p)
                    if (cfg.left.effect == Effect.MUSIC) ensureMic()
                    if (!cfg.enabled) setEnabled(true) else save()
                    Toast.makeText(this@MainActivity, "${preset.name} applied", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                if (deletable) setOnLongClickListener {
                    confirm("Delete \"${preset.name}\"?") {
                        store.saveUserPresets(store.userPresets().filterIndexed { k, _ -> k != i })
                        refresh()
                    }
                    true
                }
                val ring = MiniRing(this@MainActivity)
                addView(ring, LinearLayout.LayoutParams(ui.px(36), ui.px(36)))
                tiles += Tile(ring, p.copy().also { it.mirror = false })
                val texts = ui.vbox().apply { setPadding(ui.px(8), 0, 0, 0) }
                texts.addView(ui.text(preset.name, 14f, Aya.WHITE, weight = 600).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val sub = buildString {
                    append(p.left.effect.label)
                    if (!p.sync) append(" + ").append(p.right.effect.label)
                }.let { if (it.equals(preset.name, ignoreCase = true)) preset.cat else it }
                texts.addView(ui.text(sub, 12f, Aya.DIM).apply { setPadding(0, ui.px(3), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(texts, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
            }
        }
        content.addView(ui.rows(views, 2, equalWidth = true, gapDp = 3), ui.lp(top = 8))
    }

    // ================================================================ settings

    private fun buildSettings() {
        content.addView(ui.sectionTitle("Accent colour"))
        val acc = ui.card()
        val accRow = ui.hbox()
        Aya.ACCENTS.forEachIndexed { i, (_, col) ->
            accRow.addView(ui.chip(col and 0xFFFFFF, 42, {
                cfg.accent = i; save(); Aya.setAccent(i); recreate()
            }, selected = i == cfg.accent))
        }
        acc.addView(accRow)
        acc.addView(ui.text(Aya.ACCENTS[cfg.accent].first, 13f, Aya.DIM).apply { setPadding(ui.px(4), ui.px(4), 0, 0) })
        content.addView(acc)

        content.addView(ui.sectionTitle("Animation"))
        val fpsOptions = listOf(12, 24, 30)
        content.addView(ui.segmented(listOf("Battery saver", "Balanced", "Smooth"), fpsOptions.indexOf(cfg.fps).coerceAtLeast(0)) { i ->
            cfg.fps = fpsOptions[i]; save(); refresh()
        })
        content.addView(ui.text("${cfg.fps} updates per second. Static colours use no CPU at all.", 13f, Aya.DIMMER).apply { setPadding(ui.px(4), ui.px(8), 0, 0) })

        content.addView(ui.sectionTitle("General"))
        content.addView(ui.group(ui.switchRow("Start on boot", "Restore the lights after a restart", cfg.startOnBoot) { cfg.startOnBoot = it; save() }))

        content.addView(ui.sectionTitle("Smart modes"))
        content.addView(ui.group(
            statusRow("Sound access", "Used by Music mode", hasMic(), if (hasMic()) null else "Allow") { ensureMic() },
            statusRow("Button detection", "Used by Reactive mode", keyServiceEnabled(), if (keyServiceEnabled()) "Settings" else "Turn on") { openAccessibility() },
        ))

        content.addView(ui.sectionTitle("LED layout"))
        val cal = ui.card()
        cal.addView(ui.text(if (cfg.calibrated) "Calibrated" else "Not calibrated", 16f, if (cfg.calibrated) Aya.WHITE else Aya.LINK, weight = 600))
        cal.addView(ui.text("Lights each LED in turn and asks where it is, so spins, comets and per-LED colours land in the right place.", 13.5f, Aya.DIM).apply { setPadding(0, ui.px(6), 0, ui.px(12)) })
        val b = ui.hbox()
        b.addView(ui.pill("Calibrate", primary = !cfg.calibrated, textSp = 13.5f) { Calibrator(this, cfg) { save(); refresh() }.start() })
        b.addView(ui.pill("Reset", textSp = 13.5f) {
            cfg.layout = AppConfig.DEFAULT_LAYOUT.copyOf(); cfg.calibrated = false; save(); refresh()
        }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(8) })
        cal.addView(b)
        content.addView(cal)

        content.addView(ui.sectionTitle("About"))
        val about = ui.card()
        about.addView(ui.text("AYANEO RGB Control ${appVersion()}", 16f, Aya.WHITE, weight = 600))
        about.addView(ui.text("Unofficial. Not made by, endorsed by or affiliated with AYANEO.", 13f, Aya.DIM).apply { setPadding(0, ui.px(4), 0, ui.px(8)) })
        about.addView(ui.text("Uses the firmware's own LED service, the same one AYASpace uses. 100% brightness matches the brightest setting AYASpace uses.\n" +
            "${Effect.values().size - 1} modes · ${Presets.BUILT_IN.size} themes · LED access: ${LedBackend.via}" +
            (LedBackend.lastError?.let { "\nLast error: $it" } ?: ""), 13f, Aya.DIMMER))
        content.addView(about)
    }

    private fun statusRow(title: String, sub: String, ok: Boolean, action: String?, run: () -> Unit): View {
        val row = ui.hbox().apply { setPadding(ui.px(16), ui.px(12), ui.px(14), ui.px(12)) }
        val texts = ui.vbox()
        texts.addView(ui.text(title, 15f, weight = 500))
        texts.addView(ui.text(if (ok) "$sub · on" else "$sub · off", 12.5f, if (ok) Aya.DIM else Aya.DIMMER).apply { setPadding(0, ui.px(3), 0, 0) })
        row.addView(texts, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
        if (action != null) row.addView(ui.pill(action, primary = !ok, textSp = 13f) { run() })
        return row
    }

    // ================================================================ helpers

    private fun ViewGroup.focusSearchNear(x: Int, y: Int): View? {
        var best: View? = null
        var bestD = Long.MAX_VALUE
        val loc = IntArray(2)
        fun walk(v: View) {
            if (v.isFocusable && v.visibility == View.VISIBLE) {
                v.getLocationOnScreen(loc)
                val dx = (loc[0] - x).toLong(); val dy = (loc[1] - y).toLong()
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = v }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return best
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        val box = ui.vbox().apply {
            background = ui.rounded(Aya.DIALOG, 6f, 1f, Aya.DIVIDER)
            setPadding(ui.px(22), ui.px(20), ui.px(22), ui.px(16))
        }
        box.addView(ui.text(msg, 17f, Aya.WHITE, weight = 600))
        val row = ui.hbox().apply { gravity = Gravity.END }
        box.addView(row, ui.lp(top = 18))
        val dlg = AlertDialog.Builder(this).setView(box).create()
        row.addView(ui.pill("Cancel") { dlg.dismiss() })
        row.addView(ui.pill("Delete", selected = true) { onYes(); dlg.dismiss() }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(10) })
        dlg.window?.setBackgroundDrawable(ColorDrawable(0))
        dlg.show()
    }

    /**
     * Chip size (dp) so [perRow] chips fill the controls column. The Pocket Air Mini is 1280x960 @ 320 dpi
     * = 640x480 dp; with the preview pane the column is 640 - 248 - 32 (padding) = 360 dp wide.
     */
    private fun chipDp(perRow: Int, insetDp: Int): Int {
        val paneDp = if (page == Page.SETTINGS) 0 else PANE_DP
        val columnPx = resources.displayMetrics.widthPixels - ui.px(paneDp) - 2 * ui.px(PAD_DP) - ui.px(insetDp)
        return (columnPx / perRow / ui.density - 6).toInt().coerceIn(28, 44)
    }

    private fun appVersion(): String = runCatching { "v" + packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")

    /** adb hooks for development and screenshots. */
    private fun handleTest(i: Intent?) {
        when (i?.getStringExtra("test") ?: return) {
            "set" -> {
                val cols = (i.getStringExtra("c") ?: "").split(',').map { it.trim().toIntOrNull(16) ?: 0 }
                LedBackend.setEach(this, IntArray(LedBackend.LED_COUNT) { cols.getOrElse(it) { 0 } }, 1f)
            }
            "off" -> LedBackend.off(this)
            "page" -> i.getStringExtra("p")?.let { name -> Page.values().firstOrNull { it.name.equals(name, true) }?.let { goTo(it) } }
            "picker" -> ColorPicker.show(this, "Colour", cfg.left.palette.first()) {}
            "calibrate" -> Calibrator(this, cfg) { save() }.start()
            "category" -> i.getStringExtra("c")?.let { n -> category = Category.values().firstOrNull { it.name.equals(n, true) }; refresh() }
            "theme" -> { if (page != Page.THEMES) showPage(Page.THEMES); themeFilter = i.getIntExtra("i", 0); refresh() }
            "scroll" -> scroll.post { scroll.scrollTo(0, (i.getIntExtra("y", 0) * ui.density).toInt()) }
            "colour" -> applyColour(i.getStringExtra("c")?.toIntOrNull(16) ?: 0xFF0000)
        }
    }

    companion object {
        private const val REQ_MIC = 7
        private const val PANE_DP = 248
        private const val PAD_DP = 16
    }
}
