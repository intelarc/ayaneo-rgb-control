package com.ambientrgb

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Drives the stick LEDs through the firmware's hidden "custom_function" system service
 * (android.app.customfunction.CustomFunctionManager, added by AYANEO to framework.jar).
 *
 * Reverse-engineered from AYASpace (com.ayaneo.home):
 *  - setLedColor(int[24]) takes 8 LEDs x (G, R, B), values 0..255.
 *  - AYASpace's own LedTest helper multiplies every value by 0.3 before sending, so 0.3 is the
 *    brightest the stock software ever drives them. We never go above that.
 *  - setLedColse() (sic) switches them off.
 */
object LedBackend {
    private const val TAG = "AmbientRGB"
    private const val SERVICE = "custom_function"
    private const val DESCRIPTOR = "android.app.customfunction.ICustomFunctionManager"
    private const val TX_SET_LED = 21   // ICustomFunctionManager.Stub.TRANSACTION_setLedColor
    private const val TX_LED_OFF = 22   // ICustomFunctionManager.Stub.TRANSACTION_setLedColse
    const val LED_COUNT = 8
    const val MAX_SCALE = 0.3f

    @Volatile var via: String = "not initialised"
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var sends = 0L
        private set
    @Volatile var lastCallMs = 0L
        private set
    @Volatile var maxCallMs = 0L
        private set

    private var manager: Any? = null
    private var setMethod: Method? = null
    private var offMethod: Method? = null
    private var binder: IBinder? = null
    private val arr = IntArray(LED_COUNT * 3)

    @Synchronized
    fun init(ctx: Context): Boolean {
        if (setMethod != null || binder != null) return true
        val errors = ArrayList<String>()
        try {
            val m = ctx.applicationContext.getSystemService(SERVICE)
            if (m == null) errors += "getSystemService(\"$SERVICE\") returned null"
            else {
                setMethod = m.javaClass.getMethod("setLedColor", IntArray::class.java)
                offMethod = runCatching { m.javaClass.getMethod("setLedColse") }.getOrNull()
                manager = m
                via = "CustomFunctionManager"
                lastError = null
                return true
            }
        } catch (t: Throwable) {
            errors += "manager: ${t.javaClass.simpleName}: ${t.message}"
        }
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val b = sm.getMethod("getService", String::class.java).invoke(null, SERVICE) as IBinder?
            if (b == null) errors += "ServiceManager.getService(\"$SERVICE\") returned null (blocked by SELinux?)"
            else {
                binder = b
                via = "raw binder"
                lastError = null
                return true
            }
        } catch (t: Throwable) {
            errors += "binder: ${t.javaClass.simpleName}: ${t.message}"
        }
        via = "unavailable"
        lastError = errors.joinToString("\n")
        Log.w(TAG, "LED backend unavailable:\n$lastError")
        return false
    }

    /** Same colour on every LED. r/g/b 0..255, level 0..1 of the stock maximum brightness. */
    fun setAll(ctx: Context, r: Int, g: Int, b: Int, level: Float): Boolean =
        setEach(ctx, IntArray(LED_COUNT) { (r shl 16) or (g shl 8) or b }, level)

    /** One 0xRRGGBB colour per LED (index 0..7). */
    @Synchronized
    fun setEach(ctx: Context, rgb: IntArray, level: Float): Boolean {
        if (!init(ctx)) return false
        val scale = MAX_SCALE * level.coerceIn(0f, 1f)
        for (i in 0 until LED_COUNT) {
            val c = rgb[i]
            arr[i * 3] = (((c shr 8) and 0xff) * scale).toInt()      // G
            arr[i * 3 + 1] = (((c shr 16) and 0xff) * scale).toInt() // R
            arr[i * 3 + 2] = ((c and 0xff) * scale).toInt()          // B
        }
        return call(TX_SET_LED, arr)
    }

    @Synchronized
    fun off(ctx: Context): Boolean {
        if (!init(ctx)) return false
        return call(TX_LED_OFF, null)
    }

    private fun call(code: Int, data: IntArray?): Boolean {
        val t0 = android.os.SystemClock.uptimeMillis()
        try {
            return callInner(code, data)
        } finally {
            val dt = android.os.SystemClock.uptimeMillis() - t0
            lastCallMs = dt
            if (dt > maxCallMs) maxCallMs = dt
            if (dt > 100) Log.w(TAG, "LED call $code took $dt ms")
        }
    }

    private fun callInner(code: Int, data: IntArray?): Boolean {
        try {
            val m = manager
            if (m != null) {
                val method = if (code == TX_SET_LED) setMethod else offMethod
                if (method != null) {
                    val res = if (data != null) method.invoke(m, data.clone()) else method.invoke(m)
                    sends++
                    lastError = null
                    return res as? Boolean ?: true
                }
            }
            val b = binder ?: return fail("no binder")
            val p = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                p.writeInterfaceToken(DESCRIPTOR)
                if (data != null) p.writeIntArray(data)
                b.transact(code, p, reply, 0)
                reply.readException()
                sends++
                lastError = null
                return reply.dataAvail() < 4 || reply.readInt() != 0
            } finally {
                p.recycle(); reply.recycle()
            }
        } catch (e: InvocationTargetException) {
            val c = e.targetException
            return fail("${c.javaClass.simpleName}: ${c.message}")
        } catch (t: Throwable) {
            return fail("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun fail(msg: String): Boolean {
        if (lastError != msg) Log.w(TAG, "LED call failed: $msg")
        lastError = msg
        return false
    }
}
