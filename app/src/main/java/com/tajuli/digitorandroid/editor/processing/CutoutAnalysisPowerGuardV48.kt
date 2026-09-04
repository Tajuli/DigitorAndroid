package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps a long Pro Cutout analysis alive when the display times out or the user turns the screen off.
 * MainActivity observes [active] and applies FLAG_KEEP_SCREEN_ON while visible; the PARTIAL_WAKE_LOCK
 * keeps the app CPU/decoder orchestration runnable if the display is turned off manually.
 */
object CutoutAnalysisPowerGuardV48 {
    private val lock = Any()
    private val leases = AtomicInteger(0)
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    fun acquire(context: Context): Lease {
        synchronized(lock) {
            val next = leases.incrementAndGet()
            if (next == 1) {
                val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val created = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Digitor:ProCutoutAnalysis",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                wakeLock = created
                _active.value = true
            }
        }
        return Lease()
    }

    private fun releaseOne() {
        synchronized(lock) {
            val remaining = (leases.decrementAndGet()).coerceAtLeast(0)
            if (remaining == 0) {
                leases.set(0)
                runCatching {
                    wakeLock?.takeIf { it.isHeld }?.release()
                }
                wakeLock = null
                _active.value = false
            }
        }
    }

    class Lease internal constructor() : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) releaseOne()
        }
    }
}
