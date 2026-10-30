package com.mobility.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One in-flight result across camera rebinds. Old generations may finish but never publish. */
class FrameGate {
    private val generation=AtomicLong(0)
    private val pending=AtomicBoolean(false)
    @Volatile private var enabled=false
    fun start(): Long { val token=generation.incrementAndGet();enabled=true;return token }
    fun stop() { enabled=false;generation.incrementAndGet() }
    fun accepts(token: Long): Boolean = enabled && generation.get()==token
    fun acquire(token: Long): Boolean {
        if(!accepts(token) || !pending.compareAndSet(false,true)) return false
        if(!accepts(token)) {pending.set(false);return false}
        return true
    }
    fun release() { pending.set(false) }
    companion object {
        const val MAX_OVERLAY_AGE_MS=1500L
        fun fresh(now: Long, started: Long): Boolean = now>=started && now-started<MAX_OVERLAY_AGE_MS
    }
}
