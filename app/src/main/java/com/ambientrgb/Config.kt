package com.ambientrgb

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class Category(val label: String) { BASIC("Basic"), RAINBOW("Rainbow"), MOTION("Motion"), NATURE("Nature"), PARTY("Party"), SMART("Smart") }

/**
 * Every lighting mode. [colours] = how many palette colours it uses (0 = its own fixed colours),
 * [amount] = label for the extra slider, or null if the mode has none,
 * [global] = drawn across both sticks at once (uses the left stick's settings).
 */
enum class Effect(
    val label: String, val cat: Category, val desc: String,
    val colours: Int = 1, val amount: String? = null, val animated: Boolean = true, val global: Boolean = false,
) {
    // ---- basic
    STATIC("Static", Category.BASIC, "One steady colour.", animated = false),
    SOLID("Per-LED", Category.BASIC, "Every LED its own colour. Set them in the LEDs section below.", colours = 0, animated = false),
    BREATHE("Breathing", Category.BASIC, "Slowly fades in and out, next colour on every breath.", colours = 3),
    PULSE("Colour pulse", Category.BASIC, "A quick flare of colour that slowly fades away.", colours = 3),
    FLASH("Flash", Category.BASIC, "Blinks on and off.", colours = 3),
    DOUBLE_FLASH("Double flash", Category.BASIC, "Two quick blinks, then a pause.", colours = 3),
    STROBE("Strobe", Category.BASIC, "Fast strobe light.", colours = 3, amount = "Strobe rate"),
    COLOR_CYCLE("Colour cycle", Category.BASIC, "Smoothly fades from one colour to the next.", colours = 6),
    COLOR_SHIFT("Colour shift", Category.BASIC, "Holds a colour, then glides to the next one.", colours = 2),

    // ---- rainbow
    RAINBOW("Spectrum", Category.RAINBOW, "The whole stick slowly cycles through every colour.", colours = 0),
    SPIN("Rainbow spin", Category.RAINBOW, "A rainbow spinning around each ring.", colours = 0),
    RAINBOW_WAVE("Rainbow wave", Category.RAINBOW, "A rainbow sweeping across both sticks, left to right.", colours = 0, global = true),
    DOUBLE_RAINBOW("Double rainbow", Category.RAINBOW, "Two rainbows chasing each other around the ring.", colours = 0),
    RAINBOW_BREATHE("Rainbow breathing", Category.RAINBOW, "Breathing, with a new rainbow colour each breath.", colours = 0),
    RAINBOW_FLASH("Rainbow flash", Category.RAINBOW, "Flashes through the rainbow.", colours = 0),

    // ---- motion
    CHASE("Comet", Category.MOTION, "A bright head racing around the ring with a fading tail.", colours = 3, amount = "Tail length"),
    DOUBLE_COMET("Double comet", Category.MOTION, "Two comets on opposite sides of the ring.", colours = 2, amount = "Tail length"),
    MARQUEE("Marquee", Category.MOTION, "Cinema-sign chaser: every other LED marches round.", colours = 2),
    SCANNER("Scanner", Category.MOTION, "Knight Rider style: a light sweeping back and forth across both sticks.", colours = 1, amount = "Width", global = true),
    PING_PONG("Ping-pong", Category.MOTION, "A ball of light bouncing between the two sticks.", colours = 3, global = true),
    STACK("Stack", Category.MOTION, "LEDs fill up one by one, then empty, next colour.", colours = 3),
    TWIST("Twist", Category.MOTION, "Two colours twisting around the ring.", colours = 2),
    YOYO("Yo-yo", Category.MOTION, "A band of light that grows and shrinks as it turns.", colours = 3),
    RADAR("Radar", Category.MOTION, "A radar sweep with a long glowing trail.", colours = 1),
    COLOR_WAVE("Colour wave", Category.MOTION, "Your colours flowing around the ring.", colours = 4),
    WAVE("Light wave", Category.MOTION, "A brightness wave rolling around the ring.", colours = 3),
    CROSSFIRE("Crossfire", Category.MOTION, "The two sticks trade colours back and forth.", colours = 2, global = true),

    // ---- nature
    FIRE("Fire", Category.NATURE, "Flickering flames.", colours = 0, amount = "Flicker"),
    CANDLE("Candle", Category.NATURE, "A calm, warm candle glow.", colours = 0),
    LAVA("Lava", Category.NATURE, "Slow molten reds and oranges.", colours = 0),
    OCEAN("Ocean", Category.NATURE, "Rolling blue and teal waves.", colours = 0),
    AURORA("Aurora", Category.NATURE, "Northern lights: drifting greens, teals and purples.", colours = 0),
    SUNSET("Sunset", Category.NATURE, "Orange, pink and purple sky.", colours = 0),
    FOREST("Forest", Category.NATURE, "Greens with flickers of sunlight.", colours = 0),
    STARRY("Starry night", Category.NATURE, "Stars (2nd colour) twinkling over a dim sky (1st colour).", colours = 2, amount = "Stars"),
    RAIN("Rain", Category.NATURE, "Raindrops landing and fading out.", colours = 1, amount = "Rain amount"),
    LIGHTNING("Lightning", Category.NATURE, "A dark stormy sky with lightning strikes.", colours = 0),

    // ---- party
    POLICE("Police", Category.PARTY, "Triple flashes, one half then the other.", colours = 2),
    EMERGENCY("Emergency", Category.PARTY, "Fast red and white warning lights.", colours = 0),
    HAZARD("Hazard", Category.PARTY, "Amber hazard double-blink.", colours = 0),
    HEARTBEAT("Heartbeat", Category.PARTY, "A double pulse like a heartbeat.", colours = 1),
    DISCO("Disco", Category.PARTY, "Every LED jumps to a random colour on the beat.", colours = 0),
    CONFETTI("Confetti", Category.PARTY, "Random colour pops fading away.", colours = 0, amount = "Amount"),
    CHRISTMAS("Christmas", Category.PARTY, "Red and green with white twinkles.", colours = 0),
    SPOOKY("Spooky", Category.PARTY, "Flickering orange and purple.", colours = 0),
    CANDY_CANE("Candy cane", Category.PARTY, "Red and white stripes spinning.", colours = 0),
    MATRIX("Matrix", Category.PARTY, "Green code raining down.", colours = 0),
    SYNTHWAVE("Synthwave", Category.PARTY, "Neon pink, cyan and purple waves.", colours = 0),

    // ---- smart
    BATTERY("Battery level", Category.SMART, "The 8 LEDs show your battery: red when low, green when full. Breathes while charging.", colours = 0, global = true),
    TEMPERATURE("Temperature", Category.SMART, "Blue when cool, red when hot. Pulses faster as the battery heats up.", colours = 0),
    MUSIC("Music", Category.SMART, "Reacts to sound playing on the device. Left stick follows the bass, right stick the treble.", colours = 2, amount = "Sensitivity", global = true),
    REACTIVE("Reactive", Category.SMART, "Flashes when you press buttons: left-side buttons light the left stick, right-side the right.", colours = 2, amount = "Fade time"),

    OFF("Off", Category.BASIC, "This stick's lights are off.", colours = 0, animated = false);
}

/** Positions around one stick ring, clockwise. */
object Pos {
    const val UL = 0; const val UR = 1; const val LR = 2; const val LL = 3
    val NAMES = arrayOf("Top left", "Top right", "Bottom right", "Bottom left")
}

class StickConfig {
    var effect = Effect.SPIN
    var palette: MutableList<Int> = mutableListOf(0xFF0040, 0x00A0FF, 0x40FF00)
    var ledColors = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF)   // Per-LED mode, by Pos
    var ledOn = booleanArrayOf(true, true, true, true)                      // by Pos
    var speed = 100        // 10..300 %
    var brightness = 100   // 0..100 %
    var amount = 50        // 0..100, meaning depends on the effect
    var reverse = false

    fun copy(): StickConfig = fromJson(toJson())

    fun toJson(): JSONObject = JSONObject().apply {
        put("effect", effect.name)
        put("palette", JSONArray(palette))
        put("ledColors", JSONArray(ledColors.toList()))
        put("ledOn", JSONArray(ledOn.toList()))
        put("speed", speed); put("brightness", brightness); put("amount", amount); put("reverse", reverse)
    }

    companion object {
        fun fromJson(o: JSONObject?): StickConfig {
            val s = StickConfig()
            if (o == null) return s
            s.effect = runCatching { Effect.valueOf(o.getString("effect")) }.getOrDefault(s.effect)
            o.optJSONArray("palette")?.let { a -> s.palette = MutableList(a.length()) { a.getInt(it) }.ifEmpty { s.palette } }
            o.optJSONArray("ledColors")?.let { a -> if (a.length() == 4) s.ledColors = IntArray(4) { a.getInt(it) } }
            o.optJSONArray("ledOn")?.let { a -> if (a.length() == 4) s.ledOn = BooleanArray(4) { a.getBoolean(it) } }
            s.speed = o.optInt("speed", s.speed).coerceIn(10, 300)
            s.brightness = o.optInt("brightness", s.brightness).coerceIn(0, 100)
            s.amount = o.optInt("amount", s.amount).coerceIn(0, 100)
            s.reverse = o.optBoolean("reverse", s.reverse)
            return s
        }
    }
}

class AppConfig {
    var enabled = false
    var brightness = 100           // master, % of the stock maximum
    var sync = true                // right stick copies the left one
    var mirror = true              // ...mirrored left/right, spins in the opposite direction
    var linked = false             // spin / comet / wave treat both rings as one 8-LED ring
    var startOnBoot = false
    var accent = 0                 // index into Aya.ACCENTS
    var fps = 24                   // animation frames per second: 12 (battery saver), 24, 30
    var left = StickConfig()
    var right = StickConfig()
    /** Hardware LED index for each position: [L-UL, L-UR, L-LR, L-LL, R-UL, R-UR, R-LR, R-LL]. */
    var layout = DEFAULT_LAYOUT.copyOf()
    var calibrated = false

    fun copy(): AppConfig = fromJson(toJson())

    fun toJson(withLayout: Boolean = true): JSONObject = JSONObject().apply {
        put("enabled", enabled); put("brightness", brightness)
        put("sync", sync); put("mirror", mirror); put("linked", linked); put("startOnBoot", startOnBoot); put("accent", accent); put("fps", fps)
        put("left", left.toJson()); put("right", right.toJson())
        if (withLayout) { put("layout", JSONArray(layout.toList())); put("calibrated", calibrated) }
    }

    /** Apply a preset's look, keeping power state, master brightness, boot setting, theme and calibration. */
    fun applyLook(p: AppConfig) {
        sync = p.sync; mirror = p.mirror; linked = p.linked
        left = p.left.copy(); right = p.right.copy()
    }

    companion object {
        // Best guess until calibrated: LEDs 1-4 sit on the screen side of each stick, 5-8 on the outer side.
        val DEFAULT_LAYOUT = intArrayOf(6, 0, 1, 7, 2, 4, 5, 3)

        fun fromJson(o: JSONObject?): AppConfig {
            val c = AppConfig()
            if (o == null) return c
            c.enabled = o.optBoolean("enabled", c.enabled)
            c.brightness = o.optInt("brightness", c.brightness).coerceIn(0, 100)
            c.sync = o.optBoolean("sync", c.sync)
            c.mirror = o.optBoolean("mirror", c.mirror)
            c.linked = o.optBoolean("linked", c.linked)
            c.startOnBoot = o.optBoolean("startOnBoot", c.startOnBoot)
            c.accent = o.optInt("accent", 0)
            c.fps = o.optInt("fps", 24).let { if (it in listOf(12, 24, 30)) it else 24 }
            c.left = StickConfig.fromJson(o.optJSONObject("left"))
            c.right = StickConfig.fromJson(o.optJSONObject("right"))
            o.optJSONArray("layout")?.let { a -> if (a.length() == 8) c.layout = IntArray(8) { a.getInt(it) } }
            c.calibrated = o.optBoolean("calibrated", false)
            return c
        }
    }
}

/** Saved configuration, user presets and recent colours, in SharedPreferences. */
class Store(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("stickrgb", Context.MODE_PRIVATE)

    fun load(): AppConfig = AppConfig.fromJson(sp.getString("config", null)?.let { runCatching { JSONObject(it) }.getOrNull() })
    fun save(c: AppConfig) { sp.edit().putString("config", c.toJson().toString()).apply() }

    fun userPresets(): List<Pair<String, AppConfig>> {
        val a = sp.getString("presets", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            o.optString("name") to AppConfig.fromJson(o.optJSONObject("config"))
        }
    }

    fun saveUserPresets(list: List<Pair<String, AppConfig>>) {
        val a = JSONArray()
        for ((name, c) in list) a.put(JSONObject().put("name", name).put("config", c.toJson(withLayout = false)))
        sp.edit().putString("presets", a.toString()).apply()
    }

    fun recentColours(): List<Int> {
        val a = sp.getString("recent", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return List(a.length()) { a.getInt(it) }
    }

    fun addRecent(c: Int) {
        val list = (listOf(c) + recentColours().filter { it != c }).take(8)
        sp.edit().putString("recent", JSONArray(list).toString()).apply()
    }
}

class Preset(val name: String, val cat: String, val config: AppConfig)

object Presets {
    private fun stick(effect: Effect, vararg palette: Int, speed: Int = 100, amount: Int = 50, reverse: Boolean = false) = StickConfig().apply {
        this.effect = effect
        if (palette.isNotEmpty()) this.palette = palette.toMutableList()
        this.speed = speed
        this.amount = amount
        this.reverse = reverse
    }

    private fun look(left: StickConfig, right: StickConfig? = null, mirror: Boolean = true, linked: Boolean = false) = AppConfig().apply {
        this.left = left
        this.right = right ?: left.copy()
        this.sync = right == null
        this.mirror = mirror
        this.linked = linked
    }

    private fun solid(c: Int) = stick(Effect.STATIC, c)

    val CATEGORIES = listOf("Gaming", "Chill", "Nature", "Party", "Seasonal", "Smart")

    val BUILT_IN: List<Preset> by lazy {
        listOf(
            // gaming
            Preset("AYANEO red", "Gaming", look(stick(Effect.BREATHE, 0xEE3233, speed = 60))),
            Preset("Rainbow spin", "Gaming", look(stick(Effect.SPIN))),
            Preset("Cyber chase", "Gaming", look(stick(Effect.CHASE, 0x00E5FF, 0xFF00D0, 0x7C4DFF, speed = 120), linked = true)),
            Preset("Knight Rider", "Gaming", look(stick(Effect.SCANNER, 0xFF0000, speed = 90, amount = 40))),
            Preset("Tron", "Gaming", look(stick(Effect.MARQUEE, 0x00E5FF, 0x002030, speed = 80))),
            Preset("Toxic", "Gaming", look(stick(Effect.PULSE, 0x60FF00, 0x00FF60, speed = 90))),
            Preset("Red vs Blue", "Gaming", look(solid(0xFF0000), solid(0x0030FF))),
            Preset("Gold rush", "Gaming", look(stick(Effect.TWIST, 0xFFB000, 0xFF5000, speed = 70))),
            // chill
            Preset("Ocean breathe", "Chill", look(stick(Effect.BREATHE, 0x0050FF, 0x00D5C0, 0x00A0FF, speed = 60))),
            Preset("Colour cycle", "Chill", look(stick(Effect.COLOR_CYCLE, 0xFF0000, 0xFF8000, 0xFFFF00, 0x00FF40, 0x0080FF, 0xA000FF, speed = 70))),
            Preset("Ice", "Chill", look(stick(Effect.COLOR_SHIFT, 0x80E0FF, 0xFFFFFF, speed = 60))),
            Preset("Sakura", "Chill", look(stick(Effect.COLOR_WAVE, 0xFF70B0, 0xFFC0E0, 0xFF3080, 0xFFFFFF, speed = 50))),
            Preset("Lavender", "Chill", look(stick(Effect.WAVE, 0xA070FF, 0x7040FF, speed = 60), linked = true)),
            Preset("Mint", "Chill", look(solid(0x23FFB0))),
            Preset("Spectrum", "Chill", look(stick(Effect.RAINBOW, speed = 50))),
            Preset("Rainbow wave", "Chill", look(stick(Effect.RAINBOW_WAVE, speed = 80))),
            // nature
            Preset("Fire", "Nature", look(stick(Effect.FIRE))),
            Preset("Candle", "Nature", look(stick(Effect.CANDLE))),
            Preset("Lava", "Nature", look(stick(Effect.LAVA))),
            Preset("Aurora", "Nature", look(stick(Effect.AURORA))),
            Preset("Sunset", "Nature", look(stick(Effect.SUNSET))),
            Preset("Forest", "Nature", look(stick(Effect.FOREST))),
            Preset("Starry night", "Nature", look(stick(Effect.STARRY, 0x0010A0, 0xFFFFFF))),
            Preset("Thunderstorm", "Nature", look(stick(Effect.LIGHTNING))),
            Preset("Rain", "Nature", look(stick(Effect.RAIN, 0x2080FF, amount = 60))),
            // party
            Preset("Police", "Party", look(stick(Effect.POLICE, 0xFF0000, 0x0030FF), mirror = false)),
            Preset("Disco", "Party", look(stick(Effect.DISCO))),
            Preset("Confetti", "Party", look(stick(Effect.CONFETTI, amount = 60))),
            Preset("Synthwave", "Party", look(stick(Effect.SYNTHWAVE))),
            Preset("Strobe party", "Party", look(stick(Effect.STROBE, 0xFFFFFF, 0xFF00FF, 0x00FFFF))),
            Preset("Ping-pong", "Party", look(stick(Effect.PING_PONG, 0xFF00A0, 0x00E5FF, 0xFFE000))),
            Preset("Neon heartbeat", "Party", look(stick(Effect.HEARTBEAT, 0xFF0080))),
            Preset("Matrix", "Party", look(stick(Effect.MATRIX))),
            // seasonal
            Preset("Christmas", "Seasonal", look(stick(Effect.CHRISTMAS))),
            Preset("Candy cane", "Seasonal", look(stick(Effect.CANDY_CANE, speed = 70))),
            Preset("Halloween", "Seasonal", look(stick(Effect.SPOOKY))),
            Preset("New Year", "Seasonal", look(stick(Effect.STARRY, 0x301C00, 0xFFD700, amount = 75, speed = 120))),
            Preset("Valentine's", "Seasonal", look(stick(Effect.HEARTBEAT, 0xFF0040, 0xFF70A0, speed = 80))),
            Preset("Easter", "Seasonal", look(stick(Effect.COLOR_WAVE, 0xFFB3DE, 0xB5E8FF, 0xFFF3A0, 0xC8FFB8, speed = 50))),
            Preset("Spring", "Seasonal", look(stick(Effect.COLOR_SHIFT, 0x7CFF6B, 0xFF9CD6, speed = 60))),
            Preset("Summer", "Seasonal", look(stick(Effect.SUNSET, speed = 70))),
            Preset("Autumn", "Seasonal", look(stick(Effect.COLOR_WAVE, 0xFF5A00, 0xC02000, 0xFFA000, speed = 50))),
            Preset("Winter frost", "Seasonal", look(stick(Effect.STARRY, 0x0A2848, 0xE0F4FF, speed = 60))),
            Preset("Fireworks", "Seasonal", look(stick(Effect.CONFETTI, amount = 80, speed = 130))),
            // smart
            Preset("Music", "Smart", look(stick(Effect.MUSIC, 0xFF0060, 0x00E5FF, amount = 60))),
            Preset("Reactive", "Smart", look(stick(Effect.REACTIVE, 0x101830, 0xFFFFFF, amount = 40))),
            Preset("Battery", "Smart", look(stick(Effect.BATTERY))),
            Preset("Temperature", "Smart", look(stick(Effect.TEMPERATURE))),
        )
    }
}
