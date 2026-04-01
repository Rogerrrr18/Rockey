package com.example.rockey.sync

import android.os.Handler

class PeriodicFrameSyncScheduler(
    private val handler: Handler,
    private val intervalMs: Long,
    private val shouldRun: () -> Boolean,
    private val onTick: () -> Unit,
) {
    private val tickRunnable =
        object : Runnable {
            override fun run() {
                if (!running) {
                    return
                }
                if (shouldRun()) {
                    onTick()
                }
                scheduleNext()
            }
        }

    private var running = false

    fun start() {
        running = true
        scheduleNext()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
    }

    fun reset() {
        if (running) {
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        handler.removeCallbacks(tickRunnable)
        if (running) {
            handler.postDelayed(tickRunnable, intervalMs)
        }
    }
}
