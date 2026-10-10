package com.ambientrgb

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Small line icons drawn in code (crisp at any size, no image assets). */
class IconDrawable(private val type: Int, private val color: Int, private val sizePx: Int) : Drawable() {
    companion object { const val LIGHTS = 0; const val LEFT = 1; const val RIGHT = 2; const val THEMES = 3; const val SETTINGS = 4; const val MODES = 5 }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
    }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        p.strokeWidth = s * 0.085f
        when (type) {
            LIGHTS -> {             // a glowing dot with rays
                c.drawCircle(cx, cy, s * 0.17f, f)
                for (k in 0 until 8) {
                    val a = Math.PI / 4 * k
                    val r1 = s * 0.29f; val r2 = s * 0.42f
                    c.drawLine(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(), cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), p)
                }
            }
            LEFT, RIGHT -> {        // stick ring with the active half highlighted
                c.drawCircle(cx, cy, s * 0.36f, p.apply { alpha = 90 })
                p.alpha = 255
                val r = RectF(cx - s * 0.36f, cy - s * 0.36f, cx + s * 0.36f, cy + s * 0.36f)
                c.drawArc(r, if (type == LEFT) 100f else -80f, 160f, false, p)
                c.drawCircle(cx, cy, s * 0.15f, f)
            }
            MODES -> {              // a stick ring split into its four LEDs
                val r = RectF(cx - s * 0.34f, cy - s * 0.34f, cx + s * 0.34f, cy + s * 0.34f)
                for (k in 0..3) c.drawArc(r, 90f * k + 14f, 62f, false, p)
                c.drawCircle(cx, cy, s * 0.12f, f)
            }
            THEMES -> {             // three overlapping circles
                val r = s * 0.19f
                c.drawCircle(cx, cy - s * 0.12f, r, p)
                c.drawCircle(cx - s * 0.13f, cy + s * 0.1f, r, p)
                c.drawCircle(cx + s * 0.13f, cy + s * 0.1f, r, p)
            }
            SETTINGS -> {           // three slider lines with knobs
                for (k in 0..2) {
                    val y = cy + (k - 1) * s * 0.25f
                    c.drawLine(cx - s * 0.36f, y, cx + s * 0.36f, y, p.apply { alpha = 110 })
                    p.alpha = 255
                    val kx = cx + floatArrayOf(-0.16f, 0.18f, -0.02f)[k] * s
                    c.drawCircle(kx, y, s * 0.085f, f)
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha; f.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf; f.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The real Pocket Air Mini photo (stick rings edited to unlit) with the live LED colours painted
 * onto both rings. A sweep gradient blends neighbouring LEDs the way the light pipe diffuses them.
 * Ring geometry measured on the 1600x1280 product photo, then cropped and scaled to 720x363.
 */
class DeviceView(ctx: Context) : View(ctx) {
    // Held by the view, not a process-wide static cache: the ~1 MB photo is freed with the UI, so the always-on
    // light service never keeps it. It is only decoded when the settings screen is actually open.
    private val photo = android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.device_photo)
    private val colors = IntArray(8)
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dst = RectF()
    private var scale = 1f
    private val centres = floatArrayOf(105.5f, 214.3f, 614.6f, 213.7f)   // in the 720x363 photo
    private val ringR = 29.05f
    private val shaders = arrayOfNulls<android.graphics.SweepGradient>(2)
    private val sweep = IntArray(6)
    private val stops = floatArrayOf(0f, 0.125f, 0.375f, 0.625f, 0.875f, 1f)   // 0 deg = 3 o'clock: LR at 45, LL 135, UL 225, UR 315

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // BlurMaskFilter needs a software layer

    fun setColors(c: IntArray) {
        if (c.contentEquals(colors)) return
        c.copyInto(colors)
        shaders[0] = null; shaders[1] = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val iw = photo.width.toFloat(); val ih = photo.height.toFloat()
        scale = minOf(w / iw, h / ih)
        val dw = iw * scale; val dh = ih * scale
        dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        val r = ringR * scale                       // light pipe proportions, relative to the ring radius
        glow.strokeWidth = 0.43f * r
        glow.maskFilter = BlurMaskFilter(maxOf(1f, 0.26f * r), BlurMaskFilter.Blur.NORMAL)
        core.strokeWidth = 0.15f * r
        shaders[0] = null; shaders[1] = null
    }

    override fun onDraw(c: Canvas) {
        c.drawBitmap(photo, null, dst, img)
        for (s in 0..1) {
            val o = s * 4
            if (colors[o] == 0 && colors[o + 1] == 0 && colors[o + 2] == 0 && colors[o + 3] == 0) continue
            val cx = dst.left + centres[s * 2] * scale
            val cy = dst.top + centres[s * 2 + 1] * scale
            val sh = shaders[s] ?: run {
                val ul = vivid(colors[o]); val ur = vivid(colors[o + 1]); val lr = vivid(colors[o + 2]); val ll = vivid(colors[o + 3])
                val edge = mix(ur, lr)
                sweep[0] = edge; sweep[1] = lr; sweep[2] = ll; sweep[3] = ul; sweep[4] = ur; sweep[5] = edge
                android.graphics.SweepGradient(cx, cy, sweep, stops).also { shaders[s] = it }
            }
            glow.shader = sh; core.shader = sh
            c.drawCircle(cx, cy, ringR * scale, glow)
            c.drawCircle(cx, cy, ringR * scale, core)
        }
    }

    private fun mix(a: Int, b: Int): Int = Engine.lerp(a and 0xFFFFFF, b and 0xFFFFFF, 0.5) or (if (a ushr 24 == 0 && b ushr 24 == 0) 0 else 0xFF000000.toInt())

    /** LED values are low (dimmed hardware range): lift them so the on-screen hue is clear. Off stays transparent. */
    private fun vivid(c: Int): Int {
        val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
        val m = maxOf(r, g, b)
        if (m == 0) return 0x00000000
        val k = (0.45 + 0.55 * m / 255.0) * 255.0 / m
        return 0xFF000000.toInt() or ((r * k).toInt().coerceAtMost(255) shl 16) or ((g * k).toInt().coerceAtMost(255) shl 8) or (b * k).toInt().coerceAtMost(255)
    }
}

/** Small flat ring (4 LEDs) for mode and theme tiles. */
class MiniRing(ctx: Context) : View(ctx) {
    private val colors = IntArray(4)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF2A2F3A.toInt() }
    private val rect = RectF()
    private val starts = floatArrayOf(181f, 271f, 1f, 91f)

    fun setColors(src: IntArray, offset: Int) {
        var changed = false
        for (i in 0..3) if (colors[i] != src[offset + i]) { colors[i] = src[offset + i]; changed = true }
        if (changed) invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(cx, cy) * 0.78f
        val stroke = r * 0.3f
        arc.strokeWidth = stroke; base.strokeWidth = stroke
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawCircle(cx, cy, r, base)
        for (i in 0..3) {
            val col = colors[i]
            if (col == 0) continue
            arc.color = 0xFF000000.toInt() or lift(col)
            c.drawArc(rect, starts[i], 88f, false, arc)
        }
    }

    private fun lift(c: Int): Int {
        val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
        val m = maxOf(r, g, b)
        if (m == 0) return 0
        val k = (0.4 + 0.6 * m / 255.0) * 255.0 / m
        return ((r * k).toInt().coerceAtMost(255) shl 16) or ((g * k).toInt().coerceAtMost(255) shl 8) or (b * k).toInt().coerceAtMost(255)
    }
}
