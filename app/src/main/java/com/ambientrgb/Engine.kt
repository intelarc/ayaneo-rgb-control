package com.ambientrgb

import android.graphics.Color
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Renders one frame of both sticks.
 * Output is 8 colours (0xRRGGBB) by position: [L-UL, L-UR, L-LR, L-LL, R-UL, R-UR, R-LR, R-LL].
 */
object Engine {
    private val RING_EFFECTS = setOf(
        Effect.SPIN, Effect.DOUBLE_RAINBOW, Effect.CHASE, Effect.DOUBLE_COMET, Effect.MARQUEE, Effect.STACK,
        Effect.TWIST, Effect.YOYO, Effect.RADAR, Effect.COLOR_WAVE, Effect.WAVE, Effect.CANDY_CANE,
    )
    private val MIRROR = intArrayOf(Pos.UR, Pos.UL, Pos.LL, Pos.LR)   // left/right flip of a ring
    private val COLUMN = intArrayOf(0, 1, 1, 0)                         // x column of each position inside a stick
    private val hsv = FloatArray(3)
    private val WHITE = listOf(0xFFFFFF)
    private val AURORA_PAL = listOf(0x00FF70, 0x00C8FF, 0x8000FF, 0x30FF30)
    private val SUNSET_PAL = listOf(0xFF3C00, 0xFF0060, 0x9000C0, 0xFF7A00)
    private val SYNTH_PAL = listOf(0xFF00A0, 0x8000FF, 0x00E0FF)

    fun isAnimated(c: AppConfig): Boolean {
        if (!c.enabled) return false
        val r = if (c.sync) c.left else c.right
        return c.left.effect.animated || r.effect.animated
    }

    @Synchronized
    fun render(c: AppConfig, tMs: Long, out: IntArray) {
        val right = if (c.sync) c.left else c.right
        for (s in 0..1) {
            val sc = if (s == 0) c.left else right
            // global modes always use the left stick's settings so both sticks stay in step
            val src = if (sc.effect.global || (s == 1 && c.left.effect.global && c.sync)) c.left else sc
            val linked = c.linked && src.effect in RING_EFFECTS && (c.sync || c.left.effect == right.effect)
            val mirrored = s == 1 && c.sync && c.mirror && !linked && !src.effect.global
            for (i in 0..3) {
                val pi = if (mirrored) MIRROR[i] else i
                val ringSize = if (linked) 8 else 4
                val ring = if (linked) s * 4 + pi else pi
                val col = if (mirrored) 3 - (s * 2 + COLUMN[i]) else s * 2 + COLUMN[i]
                val color = effectColor(src, s, pi, ring, ringSize, col, tMs)
                out[s * 4 + i] = if (!sc.ledOn[i] || sc.effect == Effect.OFF) 0 else scale(color, src.brightness / 100.0)
            }
        }
    }

    /**
     * @param s     stick (0 left, 1 right)
     * @param i     position on the ring (Pos.*)
     * @param ring  index on the (4 or 8 LED) ring, clockwise
     * @param col   x column across both sticks, 0..3
     */
    private fun effectColor(sc: StickConfig, s: Int, i: Int, ring: Int, ringSize: Int, col: Int, tMs: Long): Int {
        val sp = sc.speed / 100.0
        val t = tMs / 1000.0 * sp
        val pal: List<Int> = if (sc.palette.isEmpty()) WHITE else sc.palette
        val n = pal.size
        val amt = sc.amount / 100.0
        val dir = if (sc.reverse) -1 else 1
        val seed = s * 97 + 13
        fun p(k: Int) = pal[Math.floorMod(k, n)]
        fun p2(k: Int, fallback: Int) = if (k < n) pal[k] else fallback
        val ringPos = ring.toDouble() / ringSize
        val left = i == Pos.UL || i == Pos.LL

        return when (sc.effect) {
            // ------------------------------------------------------------ basic
            Effect.STATIC -> p(0)
            Effect.SOLID -> sc.ledColors[i]
            Effect.OFF -> 0
            Effect.BREATHE -> {
                val x = t / 3.0; val k = floor(x).toInt()
                val lvl = (1 - cos(2 * PI * (x - k))) / 2
                scale(p(k), lvl * lvl)
            }
            Effect.PULSE -> {
                val x = t / 1.6; val k = floor(x).toInt(); val f = x - k
                val lvl = if (f < 0.12) f / 0.12 else exp(-(f - 0.12) * 4.5)
                scale(p(k), lvl)
            }
            Effect.FLASH -> { val x = t / 0.8; val k = floor(x).toInt(); if (x - k < 0.45) p(k) else 0 }
            Effect.DOUBLE_FLASH -> {
                val x = t / 1.2; val k = floor(x).toInt(); val f = x - k
                if (f < 0.08 || (f > 0.18 && f < 0.26)) p(k) else 0
            }
            Effect.STROBE -> {
                val hz = 6 + amt * 14
                if (frac(tMs / 1000.0 * hz) < 0.25) p(floor(t).toInt()) else 0
            }
            Effect.COLOR_CYCLE -> paletteFade(pal, t / 2.5)
            Effect.COLOR_SHIFT -> {
                val x = t / 3.0; val k = floor(x).toInt(); val f = x - k
                lerp(p(k), p(k + 1), smooth(((f - 0.35) / 0.5).coerceIn(0.0, 1.0)))
            }

            // ------------------------------------------------------------ rainbow
            Effect.RAINBOW -> hue(t * 60)
            Effect.SPIN -> hue(t * 90 * dir + ringPos * 360)
            Effect.RAINBOW_WAVE -> hue(t * 120 * dir - col * 70.0)
            Effect.DOUBLE_RAINBOW -> hue(t * 90 * dir + ringPos * 720)
            Effect.RAINBOW_BREATHE -> {
                val x = t / 3.0; val k = floor(x).toInt()
                val lvl = (1 - cos(2 * PI * (x - k))) / 2
                scale(hue(k * 47.0), lvl * lvl)
            }
            Effect.RAINBOW_FLASH -> { val x = t / 0.6; val k = floor(x).toInt(); if (x - k < 0.5) hue(k * 60.0) else 0 }

            // ------------------------------------------------------------ motion
            Effect.CHASE -> {
                val head = posMod(t * 2.0 * dir, ringSize.toDouble())
                val behind = posMod((head - ring) * dir, ringSize.toDouble())
                val tail = 0.7 + amt * 2.8
                val lap = floor(t * 2.0 / ringSize).toInt()
                scale(p(lap), max(0.0, 1 - behind / tail).pow(2))
            }
            Effect.DOUBLE_COMET -> {
                val tail = 0.6 + amt * 1.6
                var best = 0
                for (h in 0..1) {
                    val head = posMod(t * 2.0 * dir + h * ringSize / 2.0, ringSize.toDouble())
                    val behind = posMod((head - ring) * dir, ringSize.toDouble())
                    val lvl = max(0.0, 1 - behind / tail).pow(2)
                    best = maxColor(best, scale(p(h), lvl))
                }
                best
            }
            Effect.MARQUEE -> {
                val shift = floor(t * 4).toInt() * dir
                if (Math.floorMod(ring + shift, 2) == 0) p(0) else if (n > 1) p(1) else 0
            }
            Effect.SCANNER -> {
                val head = tri(t * 0.45) * 3.0
                val width = 0.5 + amt * 1.5
                scale(p(0), max(0.0, 1 - abs(col - head) / width).pow(1.6))
            }
            Effect.PING_PONG -> {
                val u = t * 0.6
                val ball = tri(u)                       // 0 = left stick, 1 = right stick
                val lvl = max(0.0, 1 - abs(ball - s) * 1.7).pow(1.5)
                scale(p(floor(u).toInt()), lvl)
            }
            Effect.STACK -> {
                val step = floor(t * 3).toInt()
                val cycle = ringSize * 2
                val k = Math.floorMod(step, cycle)
                val idx = if (dir > 0) ring else ringSize - 1 - ring
                val lit = if (k < ringSize) idx <= k else idx > k - ringSize
                if (lit) p(Math.floorDiv(step, cycle)) else 0
            }
            Effect.TWIST -> {
                val a = 2 * PI * (ringPos + t * 0.35 * dir)
                lerp(p(1), p(0), 0.5 + 0.5 * cos(a))
            }
            Effect.YOYO -> {
                val len = (0.15 + 0.85 * (0.5 + 0.5 * sin(t * 2.2))) * ringSize
                val centre = posMod(t * 0.7 * dir, ringSize.toDouble())
                val d = ringDist(ring.toDouble(), centre, ringSize.toDouble())
                scale(p(floor(t / 2.85).toInt()), (1 - ((d - len / 2) / 0.6).coerceIn(0.0, 1.0)))
            }
            Effect.RADAR -> {
                val head = posMod(t * 1.5 * dir, ringSize.toDouble())
                val behind = posMod((head - ring) * dir, ringSize.toDouble())
                scale(p(0), (1 - behind / ringSize).pow(3))
            }
            Effect.COLOR_WAVE -> paletteFade(pal, t * 0.6 * dir + ringPos * n)
            Effect.WAVE -> {
                val w = 0.5 + 0.5 * sin(2 * PI * (t * 0.8 * dir - ringPos))
                scale(paletteFade(pal, t / 4.0), 0.06 + 0.94 * w * w)
            }
            Effect.CROSSFIRE -> {
                val x = t / 1.6; val k = floor(x).toInt()
                val f = smooth(((x - k - 0.3) / 0.4).coerceIn(0.0, 1.0))
                if (s == 0) lerp(p(k), p(k + 1), f) else lerp(p(k + 1), p(k), f)
            }

            // ------------------------------------------------------------ nature
            Effect.FIRE -> {
                val nz = noise(t * (5 + amt * 6) + ring * 3.7, seed + ring)
                scale(lerp(0xFF1000, 0xFFA020, nz * nz), 0.3 + 0.7 * nz)
            }
            Effect.CANDLE -> {
                val nz = noise(t * 3 + ring * 2.3, seed + ring) * 0.7 + noise(t * 9 + ring, seed + 40) * 0.3
                scale(lerp(0xFF4A08, 0xFFA040, nz), 0.55 + 0.45 * nz)
            }
            Effect.LAVA -> {
                val nz = noise(t * 0.9 + ring * 1.3 + col * 0.7, seed + 5)
                scale(lerp(0xB00000, 0xFF5A00, nz), 0.45 + 0.55 * nz)
            }
            Effect.OCEAN -> {
                val w = 0.5 + 0.5 * sin(2 * PI * (t * 0.25 - col * 0.18))
                val nz = noise(t * 0.8 + ring * 2.1, seed + 9)
                scale(lerp(0x0028FF, 0x00E0C8, w * 0.7 + nz * 0.3), 0.45 + 0.55 * (0.6 * w + 0.4 * nz))
            }
            Effect.AURORA -> {
                val nz = noise(t * 0.35 + col * 0.9 + ring * 0.6, seed + 21)
                val lvl = 0.35 + 0.65 * noise(t * 0.6 + ring * 1.7, seed + 22)
                scale(paletteFade(AURORA_PAL, nz * 3.0), lvl)
            }
            Effect.SUNSET -> paletteFade(SUNSET_PAL, t / 5.0 + col * 0.15)
            Effect.FOREST -> {
                val nz = noise(t * 0.7 + ring * 2.9, seed + 31)
                val sun = noise(t * 2.5 + ring * 5.0, seed + 32)
                val base = lerp(0x0A7A10, 0x50FF20, nz)
                if (sun > 0.82) lerp(base, 0xFFE060, (sun - 0.82) / 0.18) else scale(base, 0.5 + 0.5 * nz)
            }
            Effect.STARRY -> {
                val star = noise(t * 0.9 + ring * 5.1 + s * 11.0, seed + 41)
                val th = 0.85 - amt * 0.35
                val k = smooth(((star - th) / (1 - th)).coerceIn(0.0, 1.0))
                lerp(scale(p(0), 0.18), p2(1, 0xFFFFFF), k)
            }
            Effect.RAIN -> {
                val x = t * 2.5 + hash(ring, seed) * 7
                val slot = floor(x).toInt()
                if (hash(slot, seed * 7 + ring) < 0.12 + amt * 0.55) scale(p(0), exp(-frac(x) * 4.5)) else 0
            }
            Effect.LIGHTNING -> {
                val x = tMs / 1000.0 * 0.9 * sp + s * 0.37
                val slot = floor(x).toInt(); val f = frac(x)
                val strike = hash(slot, seed + 61) < 0.4
                val on = strike && (f < 0.04 || (f > 0.09 && f < 0.12) || (f > 0.18 && f < 0.2))
                if (on) 0xC8DCFF else scale(0x0010A0, 0.25 + 0.1 * noise(t + ring, seed + 62))
            }

            // ------------------------------------------------------------ party
            Effect.POLICE -> {
                val f = frac(t / 1.2)
                val firstHalf = f < 0.5
                val sub = (if (firstHalf) f else f - 0.5) / 0.5 * 3
                val on = frac(sub) < 0.55
                when {
                    !on -> 0
                    firstHalf && left -> p2(0, 0xFF0000)
                    !firstHalf && !left -> p2(1, 0x0030FF)
                    else -> 0
                }
            }
            Effect.EMERGENCY -> {
                val f = frac(t / 0.5)
                val a = f < 0.5
                if (frac(f * 6) < 0.6) (if (a == left) 0xFF0000 else 0xFFFFFF) else 0
            }
            Effect.HAZARD -> {
                val f = frac(t / 1.4)
                if (f < 0.12 || (f > 0.22 && f < 0.34)) 0xFF7A00 else 0
            }
            Effect.HEARTBEAT -> {
                val f = frac(t / 1.1)
                val a = exp(-sq((f - 0.08) / 0.045)) + 0.7 * exp(-sq((f - 0.30) / 0.055))
                scale(p(0), a.coerceIn(0.0, 1.0))
            }
            Effect.DISCO -> {
                val slot = floor(t * 2.5).toInt()
                hue(hash(slot, seed * 13 + ring * 7 + s) * 360)
            }
            Effect.CONFETTI -> {
                val x = t * 3 + hash(ring, seed + 3) * 5
                val slot = floor(x).toInt()
                if (hash(slot, seed * 3 + ring) < 0.2 + amt * 0.5) scale(hue(hash(slot + 99, ring + s * 5) * 360), exp(-frac(x) * 3)) else 0
            }
            Effect.CHRISTMAS -> {
                if (hash(floor(t * 6).toInt(), seed * 5 + ring) < 0.07) 0xFFFFFF
                else if (Math.floorMod(ring + floor(t).toInt(), 2) == 0) 0xFF0000 else 0x00C020
            }
            Effect.SPOOKY -> {
                val nz = noise(t * 2 + ring * 1.9, seed + 71)
                scale(lerp(0xFF5000, 0x7A00FF, nz), 0.4 + 0.6 * noise(t * 5 + ring, seed + 72))
            }
            Effect.CANDY_CANE -> if (Math.floorMod(ring + floor(t * 4).toInt() * dir, 2) == 0) 0xFF0010 else 0xFFFFFF
            Effect.MATRIX -> {
                val x = t * 2.2 + hash(ring, seed + 81) * 3
                val drip = hash(floor(x).toInt(), seed + 82 + ring) < 0.35
                val lvl = if (drip) exp(-frac(x) * 3.5) else 0.0
                lerp(0x002A00, 0x60FF60, lvl)
            }
            Effect.SYNTHWAVE -> {
                val w = 0.5 + 0.5 * sin(2 * PI * (t * 0.5 - ringPos))
                scale(paletteFade(SYNTH_PAL, t / 3.0 + ringPos), 0.35 + 0.65 * w)
            }

            // ------------------------------------------------------------ smart
            Effect.BATTERY -> {
                val pct = Live.batteryPct
                if (pct < 0) scale(0xFFFFFF, 0.1) else {
                    val idx = s * 4 + ring            // 0..7 across both sticks
                    val lit = ceil(pct / 12.5).toInt().coerceIn(1, 8)
                    val c = hue(pct * 1.2)            // 0 = red .. 120 = green
                    when {
                        idx < lit - 1 -> c
                        idx == lit - 1 -> if (Live.charging) scale(c, 0.25 + 0.75 * (0.5 + 0.5 * sin(tMs / 400.0))) else c
                        else -> scale(c, 0.06)
                    }
                }
            }
            Effect.TEMPERATURE -> {
                val tc = if (Live.tempC < 0) 35f else Live.tempC
                val heat = ((tc - 30) / 20.0).coerceIn(0.0, 1.0)
                val rate = 0.4 + heat * 2.2
                val lvl = 0.55 + 0.45 * sin(tMs / 1000.0 * rate * 2 * PI)
                scale(hue(200 * (1 - heat)), lvl)
            }
            Effect.MUSIC -> {
                val sens = 0.4 + amt * 1.6
                val v = ((if (s == 0) Live.bass else Live.treble) * sens).coerceIn(0.0, 1.0)
                val lit = v * 4                       // VU meter around the ring
                val lvl = (lit - ring).coerceIn(0.0, 1.0).let { if (ring == 0) max(it, 0.08) else it }
                scale(lerp(p(0), p2(1, p(0)), v), lvl)
            }
            Effect.REACTIVE -> {
                val since = SystemClock.uptimeMillis() - (if (s == 0) Live.lastPressLeft else Live.lastPressRight)
                val fade = 150 + amt * 1200
                val k = if (since < 0) 0.0 else exp(-since / fade)
                lerp(scale(p(0), 0.6), p2(1, 0xFFFFFF), k)
            }
        }
    }

    // ------------------------------------------------------------------ colour helpers

    fun scale(c: Int, f: Double): Int {
        val k = f.coerceIn(0.0, 1.0)
        val r = (((c shr 16) and 0xff) * k + 0.5).toInt()
        val g = (((c shr 8) and 0xff) * k + 0.5).toInt()
        val b = ((c and 0xff) * k + 0.5).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    fun lerp(a: Int, b: Int, f: Double): Int {
        val k = f.coerceIn(0.0, 1.0)
        fun ch(sh: Int) = (((a shr sh) and 0xff) * (1 - k) + ((b shr sh) and 0xff) * k + 0.5).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun maxColor(a: Int, b: Int): Int =
        (max((a shr 16) and 0xff, (b shr 16) and 0xff) shl 16) or (max((a shr 8) and 0xff, (b shr 8) and 0xff) shl 8) or max(a and 0xff, b and 0xff)

    private fun paletteFade(pal: List<Int>, x: Double): Int {
        val k = floor(x).toInt()
        val n = pal.size
        return lerp(pal[Math.floorMod(k, n)], pal[Math.floorMod(k + 1, n)], smooth(x - k))
    }

    fun hue(h: Double): Int = synchronized(hsv) {
        hsv[0] = posMod(h, 360.0).toFloat(); hsv[1] = 1f; hsv[2] = 1f
        Color.HSVToColor(hsv) and 0xFFFFFF
    }

    private fun smooth(f: Double) = f * f * (3 - 2 * f)
    private fun frac(x: Double) = x - floor(x)
    private fun tri(x: Double) = 1 - abs(posMod(x, 2.0) - 1)          // 0..1..0 triangle wave
    private fun posMod(x: Double, m: Double): Double { val r = x % m; return if (r < 0) r + m else r }
    private fun ringDist(a: Double, b: Double, m: Double): Double { val d = posMod(a - b, m); return min(d, m - d) }
    private fun sq(x: Double) = x * x

    private fun hash(a: Int, b: Int): Double {
        var h = a * 374761393 + b * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7fffffff) / 2147483647.0
    }

    private fun noise(x: Double, seed: Int): Double {
        val i = floor(x).toInt()
        val s = smooth(x - i)
        return hash(i, seed) * (1 - s) + hash(i + 1, seed) * s
    }
}
