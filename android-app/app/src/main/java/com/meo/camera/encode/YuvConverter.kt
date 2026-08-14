package com.meo.camera.encode

import java.nio.ByteBuffer

/**
 * Converts CameraX's `YUV_420_888` output into the I420 layout WebRTC wants.
 *
 * `YUV_420_888` is a *family* of layouts, not one layout, and that is the whole
 * difficulty. The same code path receives:
 *
 * - **Row padding.** `rowStride` is frequently larger than the width, because
 *   hardware likes aligned rows. Copying `width * height` bytes in one go from
 *   a padded buffer produces the diagonally skewed picture that every camera
 *   pipeline produces exactly once.
 * - **Interleaved chroma.** When `pixelStride` is 2 the U and V planes are two
 *   views into one interleaved NV12/NV21 buffer, so every second byte belongs
 *   to the other component and must be skipped.
 * - **Planar chroma.** When `pixelStride` is 1 the plane is contiguous and can
 *   be copied a row at a time.
 *
 * Deliberately free of Android types so it can be tested against synthetic
 * buffers on the JVM, where the interesting stride and pixel-stride
 * combinations can be constructed on purpose rather than waited for.
 *
 * Reads are absolute-indexed rather than position-based: these buffers belong
 * to the camera, and mutating their positions would corrupt anything else
 * reading the same frame.
 */
object YuvConverter {

    /**
     * @param outY destination luma, [outYStride] bytes per row, [height] rows.
     * @param outU destination U, [outUStride] bytes per row, `(height+1)/2` rows.
     * @param outV destination V, same shape as U.
     */
    @Suppress("LongParameterList")
    fun toI420(
        width: Int,
        height: Int,
        y: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        u: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        v: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        outY: ByteBuffer,
        outYStride: Int,
        outU: ByteBuffer,
        outUStride: Int,
        outV: ByteBuffer,
        outVStride: Int
    ) {
        require(width > 0 && height > 0) { "frame dimensions must be positive" }

        copyPlane(y, yRowStride, yPixelStride, width, height, outY, outYStride)

        // Chroma is quarter resolution, rounded up so odd dimensions do not
        // lose their last row or column.
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2

        copyPlane(u, uRowStride, uPixelStride, chromaWidth, chromaHeight, outU, outUStride)
        copyPlane(v, vRowStride, vPixelStride, chromaWidth, chromaHeight, outV, outVStride)
    }

    /** Bytes needed for an I420 frame with tightly packed rows. */
    fun i420Size(width: Int, height: Int): Int {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        return width * height + 2 * chromaWidth * chromaHeight
    }

    private fun copyPlane(
        source: ByteBuffer,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        width: Int,
        height: Int,
        destination: ByteBuffer,
        destinationRowStride: Int
    ) {
        require(sourcePixelStride >= 1) { "pixel stride must be at least 1" }
        require(destinationRowStride >= width) { "destination row stride is narrower than the image" }

        if (sourcePixelStride == 1) {
            // Contiguous rows. Still copied row by row rather than in one block,
            // because the source rows may be padded and the destination rows may
            // be padded differently.
            for (row in 0 until height) {
                val sourceOffset = row * sourceRowStride
                val destinationOffset = row * destinationRowStride
                requireReadable(source, sourceOffset + width)
                for (column in 0 until width) {
                    destination.put(destinationOffset + column, source.get(sourceOffset + column))
                }
            }
            return
        }

        // Interleaved: take every sourcePixelStride-th byte.
        for (row in 0 until height) {
            val sourceRowStart = row * sourceRowStride
            val destinationOffset = row * destinationRowStride
            val lastByte = sourceRowStart + (width - 1) * sourcePixelStride
            requireReadable(source, lastByte + 1)
            for (column in 0 until width) {
                destination.put(
                    destinationOffset + column,
                    source.get(sourceRowStart + column * sourcePixelStride)
                )
            }
        }
    }

    private fun requireReadable(buffer: ByteBuffer, requiredBytes: Int) {
        require(buffer.limit() >= requiredBytes) {
            "plane is shorter than its declared geometry: need $requiredBytes, have ${buffer.limit()}"
        }
    }
}
