package com.meo.camera.encode

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/**
 * Where CameraX frames become WebRTC frames.
 *
 * ## Why CameraX keeps the camera
 *
 * ADR 0008 leaves an explicit choice open: feed CameraX's analysis frames into
 * WebRTC, or hand the camera to WebRTC's own `Camera2Capturer`. This is the
 * first path, and the honest status is that it is **provisional** — the ADR
 * asks for the decision to be made on measured 720p30 CPU and thermal figures
 * from real devices, and no device has run this code.
 *
 * What the choice buys today is that the capture service, the zoom, torch and
 * lens controls, and the lifecycle already built and working stay exactly as
 * they are. What it costs is this file: a per-frame CPU copy from the camera's
 * `YUV_420_888` into I420, where `Camera2Capturer` could have handed WebRTC a
 * texture and avoided the copy entirely.
 *
 * That cost is the thing to measure. If 720p30 turns out to be too expensive
 * here, the seam is narrow: [FrameSink] is the only thing the capture path
 * knows about, and a texture-based implementation replaces this class without
 * touching the service or the controls.
 */
interface FrameSink {
    /** Called on the camera's analysis thread. Must not block it. */
    fun onFrame(image: ImageProxy)
    fun close() {}
}

/** A sink that drops everything, used before a desktop has asked for video. */
object NullFrameSink : FrameSink {
    override fun onFrame(image: ImageProxy) = Unit
}

class WebRtcFrameSink(
    private val observer: CapturerObserver
) : FrameSink {

    @Volatile
    var framesDelivered: Long = 0
        private set

    @Volatile
    var framesDropped: Long = 0
        private set

    override fun onFrame(image: ImageProxy) {
        if (image.format != ImageFormat.YUV_420_888) {
            // CameraX is configured for YUV_420_888 and nothing else, so this
            // is a device doing something unexpected rather than a case to
            // support. Counting it is more useful than crashing the session.
            framesDropped++
            return
        }

        val width = image.width
        val height = image.height
        val planes = image.planes
        if (planes.size < 3) {
            framesDropped++
            return
        }

        val buffer = JavaI420Buffer.allocate(width, height)
        try {
            YuvConverter.toI420(
                width = width,
                height = height,
                y = planes[0].buffer,
                yRowStride = planes[0].rowStride,
                yPixelStride = planes[0].pixelStride,
                u = planes[1].buffer,
                uRowStride = planes[1].rowStride,
                uPixelStride = planes[1].pixelStride,
                v = planes[2].buffer,
                vRowStride = planes[2].rowStride,
                vPixelStride = planes[2].pixelStride,
                outY = buffer.dataY,
                outYStride = buffer.strideY,
                outU = buffer.dataU,
                outUStride = buffer.strideU,
                outV = buffer.dataV,
                outVStride = buffer.strideV
            )
        } catch (_: IllegalArgumentException) {
            // A frame whose declared geometry does not match its buffers. Drop
            // it rather than send a torn picture or read out of bounds.
            buffer.release()
            framesDropped++
            return
        }

        // Rotation travels as metadata rather than being applied here: WebRTC
        // carries it to the far end, and rotating pixels on the phone would
        // spend CPU to lose the ability to correct it later.
        val frame = VideoFrame(
            buffer,
            image.imageInfo.rotationDegrees,
            image.imageInfo.timestamp * NANOS_PER_MILLI
        )
        try {
            observer.onFrameCaptured(frame)
            framesDelivered++
        } finally {
            frame.release()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
