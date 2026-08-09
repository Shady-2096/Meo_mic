package com.meo.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateTrackerTest {
    @Test
    fun reportsFramesPerSecondAfterOneWindow() {
        val tracker = FrameRateTracker(reportIntervalNanos = 1_000_000_000L)

        assertNull(tracker.sample(1_000_000_000L))
        for (frame in 1 until 30) {
            assertNull(tracker.sample(1_000_000_000L + frame * 33_333_333L))
        }

        val fps = tracker.sample(2_000_000_000L)
        assertEquals(30.0, fps ?: 0.0, 0.01)
    }

    @Test
    fun resetStartsANewWindow() {
        val tracker = FrameRateTracker(reportIntervalNanos = 10L)
        tracker.sample(100L)
        tracker.reset()

        assertNull(tracker.sample(1_000L))
    }
}
