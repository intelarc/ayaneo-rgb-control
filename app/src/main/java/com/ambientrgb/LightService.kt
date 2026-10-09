package com.ambientrgb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.BatteryManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/**
 * Runs the effect engine and pushes frames to the stick LEDs.
 * Animated effects: ~30 frames/s, one LED call at a time on this service's own thread.
 * Static colours: sent once, then refreshed every few seconds in case something else changed them.
 */
class LightService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val ACTION_STOP = "com.ambientrgb.STOP"
        private const val TAG = "StickRGB"
        private const val CHANNEL = "lights"
        private const val STATIC_REFRESH_MS = 15000L
        private const val SLOW_CALL_MS = 250L

        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, LightService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LightService::class.java))
        }
    }

    private lateinit var store: Store
    @Volatile private var cfg = AppConfig()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val frame = IntArray(8)
    private val hw = IntArray(8)
    private val lastHw = IntArray(8) { -1 }
    private var lastSend = 0L
    private var pausedUntil = 0L
    private val t0 = SystemClock.uptimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) = readBattery(i)
    }

    private fun readBattery(i: Intent?) {
        if (i == null) return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) Live.batteryPct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        Live.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        if (t > 0) Live.tempC = t / 10f
    }

    // ---------------------------------------------------------------- music (audio visualizer on the output mix)
    private var viz: Visualizer? = null
    private var bassPeak = 1f
    private var treblePeak = 1f

    private fun wantsMusic(c: AppConfig) = c.enabled && (c.left.effect == Effect.MUSIC || (!c.sync && c.right.effect == Effect.MUSIC))

    private fun updateAudio(c: AppConfig) {
        val want = wantsMusic(c) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (want && viz == null) startAudio() else if (!want && viz != null) stopAudio()
    }

    private fun startAudio() {
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) { if (fft != null) onFft(fft) }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            viz = v
            Live.audioOk = true
        } catch (t: Throwable) {
            Live.audioOk = false
            Log.w(TAG, "music capture unavailable: $t")
        }
    }

    private fun stopAudio() {
        runCatching { viz?.enabled = false; viz?.release() }
        viz = null
        Live.audioOk = false
        Live.bass = 0f; Live.treble = 0f; Live.level = 0f
    }

    private fun onFft(fft: ByteArray) {
        val bins = fft.size / 2
        fun mag(k: Int): Float {
            val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
            return kotlin.math.sqrt(re * re + im * im)
        }
        var bass = 0f; var treble = 0f
        val bassEnd = (bins * 0.012).toInt().coerceAtLeast(2)       // ~ up to 250 Hz
        for (k in 1..bassEnd) bass += mag(k)
        val tStart = (bins * 0.09).toInt(); val tEnd = (bins * 0.40).toInt()   // ~2 kHz .. 9 kHz
        for (k in tStart until tEnd) treble += mag(k)
        bass /= bassEnd; treble /= (tEnd - tStart).coerceAtLeast(1)
        // automatic gain: compare against a slowly decaying peak
        bassPeak = maxOf(bassPeak * 0.995f, bass, 2f)
        treblePeak = maxOf(treblePeak * 0.995f, treble, 1f)
        val b = (bass / bassPeak).coerceIn(0f, 1f)
        val tr = (treble / treblePeak).coerceIn(0f, 1f)
        Live.bass = if (b > Live.bass) b else Live.bass * 0.8f + b * 0.2f
        Live.treble = if (tr > Live.treble) tr else Live.treble * 0.8f + tr * 0.2f
        Live.level = maxOf(Live.bass, Live.treble)
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        cfg = store.load()
        store.sp.registerOnSharedPreferenceChangeListener(this)
        readBattery(registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cfg = store.load().also { it.enabled = false; store.save(it) }
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (thread == null) {
            val t = HandlerThread("stick-rgb").also { it.start() }
            thread = t
            handler = Handler(t.looper).also { it.post(tick) }
        }
        updateAudio(cfg)
        running = true
        return START_STICKY
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key != "config") return
        cfg = store.load()
        if (!cfg.enabled) { stopSelf(); return }
        updateAudio(cfg)
        handler?.let { it.removeCallbacks(tick); it.post(tick) }   // apply immediately
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val c = cfg
            val now = SystemClock.uptimeMillis()
            Engine.render(c, now - t0, frame)
            val lvl = c.brightness / 100f
            for (p in 0 until 8) hw[c.layout[p].coerceIn(0, 7)] = frame[p]
            val changed = !hw.contentEquals(lastHw)
            if (now >= pausedUntil && (changed || now - lastSend > STATIC_REFRESH_MS)) {
                val start = SystemClock.uptimeMillis()
                LedBackend.setEach(this@LightService, hw, lvl)
                val dt = SystemClock.uptimeMillis() - start
                hw.copyInto(lastHw)
                lastSend = SystemClock.uptimeMillis()
                if (dt > SLOW_CALL_MS) {
                    pausedUntil = lastSend + 3000
                    Log.w(TAG, "LED service slow ($dt ms), pausing 3 s")
                }
            }
            val delay = if (Engine.isAnimated(c)) 1000L / c.fps.coerceIn(12, 30) else STATIC_REFRESH_MS
            handler?.postDelayed(this, delay)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Stick lights", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LightService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("RGB+ is running")
            .setContentText("Tap to change the lights")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        store.sp.unregisterOnSharedPreferenceChangeListener(this)
        runCatching { unregisterReceiver(batteryReceiver) }
        stopAudio()
        val h = handler
        val t = thread
        handler = null; thread = null
        if (h != null && t != null) {
            h.removeCallbacksAndMessages(null)
            h.post { LedBackend.off(applicationContext) }
            t.quitSafely()
        } else {
            LedBackend.off(applicationContext)
        }
        super.onDestroy()
    }
}
