package com.meo.camera.capture

/**
 * Small, allocation-free frame-rate sampler used by the CameraX analyzer.
 * It reports no more than once per interval so camera threads do not flood UI state.
 */
class FrameRateTracker(
    private val reportIntervalNanos: Long = 1_000_000_000L
) {
    private var windowStartedAtNanos = 0L
    private var frameCount = 0

    @Synchronized
    fun sample(timestampNanos: Long): Double? {
        if (windowStartedAtNanos == 0L) {
            windowStartedAtNanos = timestampNanos
            frameCount = 1
            return null
        }

        frameCount += 1
        val elapsed = timestampNanos - windowStartedAtNanos
        if (elapsed < reportIntervalNanos) return null

        val fps = (frameCount - 1) * NANOS_PER_SECOND / elapsed.toDouble()
        windowStartedAtNanos = timestampNanos
        frameCount = 1
        return fps
    }

    @Synchronized
    fun reset() {
        windowStartedAtNanos = 0L
        frameCount = 0
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
