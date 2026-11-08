package com.example.gleo_type.event.core

import android.os.SystemClock
import kotlin.math.abs

/**
 * 고빈도 수치 이벤트 필터.
 * 직전 전송값 대비 [minDelta] 이상 변했거나, [minIntervalMs] 이상 지났고 값이 바뀌었으면 통과.
 */
class ChangeThrottle(
    private val minDelta: Double,
    private val minIntervalMs: Long,
    private val now: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var lastValue: Double? = null
    private var lastAt = 0L

    @Synchronized
    fun shouldEmit(value: Double): Boolean {
        val previous = lastValue
        val time = now()
        val pass = previous == null ||
                abs(value - previous) >= minDelta ||
                (value != previous && time - lastAt >= minIntervalMs)
        if (pass) {
            lastValue = value
            lastAt = time
        }
        return pass
    }

    @Synchronized
    fun reset() {
        lastValue = null
        lastAt = 0L
    }
}

/** 최소 시간 간격 필터 (위치 등) */
class IntervalThrottle(
    private val minIntervalMs: Long,
    private val now: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var lastAt: Long? = null

    @Synchronized
    fun shouldEmit(): Boolean {
        val time = now()
        val previous = lastAt
        if (previous != null && time - previous < minIntervalMs) return false
        lastAt = time
        return true
    }

    @Synchronized
    fun reset() {
        lastAt = null
    }
}
