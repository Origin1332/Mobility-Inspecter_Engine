package com.mobility.engine

import org.junit.Assert.*
import org.junit.Test

class FrameGateTest {
    @Test fun oneResultInFlightAcrossRebinds() {
        val gate=FrameGate();val old=gate.start()
        assertTrue(gate.acquire(old));assertFalse(gate.acquire(old))
        val current=gate.start()
        assertFalse(gate.accepts(old));assertFalse(gate.acquire(current))
        gate.release()
        assertTrue(gate.acquire(current));gate.release()
    }
    @Test fun pauseOrBackgroundRejectsOldResultsEvenAfterResume() {
        val gate=FrameGate();val old=gate.start();assertTrue(gate.acquire(old))
        gate.stop();assertFalse(gate.accepts(old));assertFalse(gate.acquire(old))
        val resumed=gate.start();assertFalse(gate.accepts(old));gate.release()
        assertTrue(gate.acquire(resumed));gate.release()
    }
    @Test fun overlayAgeIncludesInferenceTimeAndExpiresAtBoundary() {
        assertTrue(FrameGate.fresh(2000,1000))
        assertTrue(FrameGate.fresh(2499,1000))
        assertFalse(FrameGate.fresh(2500,1000))
        assertFalse(FrameGate.fresh(5000,1000))
        assertFalse(FrameGate.fresh(999,1000))
    }
}
