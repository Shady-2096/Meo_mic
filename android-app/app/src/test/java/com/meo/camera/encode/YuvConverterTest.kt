package com.meo.camera.encode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The stride and pixel-stride combinations a real phone actually produces.
 *
 * These are constructed on purpose because waiting for a device that happens to
 * pad its rows is not a test strategy — and a padding bug is invisible in code
 * review and unmistakable on screen, as a picture sheared into a diagonal.
 */
class YuvConverterTest {

    /** Luma value chosen so every pixel is distinguishable from its neighbours. */
    private fun lumaAt(x: Int, y: Int) = ((x * 7 + y * 13) % 251 + 1).toByte()
    private fun uAt(x: Int, y: Int) = ((x * 3 + y * 5) % 251 + 1).toByte()
    private fun vAt(x: Int, y: Int) = ((x * 11 + y * 17) % 251 + 1).toByte()

    private class Frame(width: Int, height: Int) {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val yStride = width
        val uStride = chromaWidth
        val vStride = chromaWidth
        val y: ByteBuffer = ByteBuffer.allocate(width * height)
        val u: ByteBuffer = ByteBuffer.allocate(chromaWidth * chromaHeight)
        val v: ByteBuffer = ByteBuffer.allocate(chromaWidth * chromaHeight)
    }

    /**
     * Builds a planar source with the given row padding, the ordinary
     * `pixelStride == 1` case.
     */
    private fun planarSource(width: Int, height: Int, padding: Int): Triple<ByteBuffer, ByteBuffer, ByteBuffer> {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val y = ByteBuffer.allocate((width + padding) * height)
        val u = ByteBuffer.allocate((chromaWidth + padding) * chromaHeight)
        val v = ByteBuffer.allocate((chromaWidth + padding) * chromaHeight)

        for (row in 0 until height) {
            for (column in 0 until width) {
                y.put(row * (width + padding) + column, lumaAt(column, row))
            }
        }
        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                u.put(row * (chromaWidth + padding) + column, uAt(column, row))
                v.put(row * (chromaWidth + padding) + column, vAt(column, row))
            }
        }
        return Triple(y, u, v)
    }

    private fun assertConverted(width: Int, height: Int, frame: Frame) {
        for (row in 0 until height) {
            for (column in 0 until width) {
                assertEquals(
                    "luma at ($column,$row)",
                    lumaAt(column, row),
                    frame.y.get(row * frame.yStride + column)
                )
            }
        }
        for (row in 0 until frame.chromaHeight) {
            for (column in 0 until frame.chromaWidth) {
                assertEquals(
                    "U at ($column,$row)",
                    uAt(column, row),
                    frame.u.get(row * frame.uStride + column)
                )
                assertEquals(
                    "V at ($column,$row)",
                    vAt(column, row),
                    frame.v.get(row * frame.vStride + column)
                )
            }
        }
    }

    @Test
    fun `a tightly packed planar frame converts exactly`() {
        val width = 64
        val height = 48
        val (y, u, v) = planarSource(width, height, padding = 0)
        val out = Frame(width, height)

        YuvConverter.toI420(
            width, height,
            y, width, 1,
            u, (width + 1) / 2, 1,
            v, (width + 1) / 2, 1,
            out.y, out.yStride, out.u, out.uStride, out.v, out.vStride
        )
        assertConverted(width, height, out)
    }

    @Test
    fun `row padding is skipped rather than copied`() {
        // The bug this catches shears the picture into a diagonal, and it is the
        // single most common mistake in this conversion.
        val width = 64
        val height = 48
        val padding = 16
        val (y, u, v) = planarSource(width, height, padding)
        val out = Frame(width, height)

        YuvConverter.toI420(
            width, height,
            y, width + padding, 1,
            u, (width + 1) / 2 + padding, 1,
            v, (width + 1) / 2 + padding, 1,
            out.y, out.yStride, out.u, out.uStride, out.v, out.vStride
        )
        assertConverted(width, height, out)
    }

    @Test
    fun `interleaved chroma is de-interleaved correctly`() {
        // NV12/NV21: U and V are two windows onto one buffer, offset by a byte
        // and read with pixelStride 2. Treating them as planar yields a picture
        // with plausible luma and wrong colour, which is easy to miss.
        val width = 32
        val height = 16
        val chromaWidth = width / 2
        val chromaHeight = height / 2

        val y = ByteBuffer.allocate(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                y.put(row * width + column, lumaAt(column, row))
            }
        }

        val interleaved = ByteBuffer.allocate(chromaWidth * chromaHeight * 2)
        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                val base = row * chromaWidth * 2 + column * 2
                interleaved.put(base, uAt(column, row))
                interleaved.put(base + 1, vAt(column, row))
            }
        }
        val u = interleaved.duplicate()
        val v = interleaved.duplicate().apply { position(1) }.slice()

        val out = Frame(width, height)
        YuvConverter.toI420(
            width, height,
            y, width, 1,
            u, chromaWidth * 2, 2,
            v, chromaWidth * 2, 2,
            out.y, out.yStride, out.u, out.uStride, out.v, out.vStride
        )
        assertConverted(width, height, out)
    }

    @Test
    fun `odd dimensions keep their last row and column`() {
        // 1280x720 is even, but a device that reports an odd capture size must
        // not silently lose a line of chroma.
        val width = 17
        val height = 9
        val (y, u, v) = planarSource(width, height, padding = 3)
        val out = Frame(width, height)

        YuvConverter.toI420(
            width, height,
            y, width + 3, 1,
            u, (width + 1) / 2 + 3, 1,
            v, (width + 1) / 2 + 3, 1,
            out.y, out.yStride, out.u, out.uStride, out.v, out.vStride
        )
        assertConverted(width, height, out)
        assertEquals(9, out.chromaWidth)
        assertEquals(5, out.chromaHeight)
    }

    @Test
    fun `a destination wider than the image is filled without disturbing its padding`() {
        val width = 16
        val height = 8
        val (y, u, v) = planarSource(width, height, padding = 0)

        val yStride = width + 8
        val chromaWidth = width / 2
        val chromaStride = chromaWidth + 4
        val outY = ByteBuffer.allocate(yStride * height)
        val outU = ByteBuffer.allocate(chromaStride * (height / 2))
        val outV = ByteBuffer.allocate(chromaStride * (height / 2))
        // Poison the padding so a write into it is visible.
        for (index in 0 until outY.limit()) outY.put(index, 0x7F)

        YuvConverter.toI420(
            width, height,
            y, width, 1, u, chromaWidth, 1, v, chromaWidth, 1,
            outY, yStride, outU, chromaStride, outV, chromaStride
        )

        for (row in 0 until height) {
            for (column in 0 until width) {
                assertEquals(lumaAt(column, row), outY.get(row * yStride + column))
            }
            for (column in width until yStride) {
                assertEquals(
                    "padding at row $row column $column must be untouched",
                    0x7F.toByte(),
                    outY.get(row * yStride + column)
                )
            }
        }
    }

    @Test
    fun `a plane shorter than its declared geometry is refused rather than read past`() {
        // The frame arrives from the camera HAL. A geometry that does not match
        // the buffer is a device quirk, not a reason to read out of bounds.
        val width = 32
        val height = 16
        val truncated = ByteBuffer.allocate(width * height / 2)
        val chroma = ByteBuffer.allocate(width * height / 4)
        val out = Frame(width, height)

        assertThrows(IllegalArgumentException::class.java) {
            YuvConverter.toI420(
                width, height,
                truncated, width, 1, chroma, width / 2, 1, chroma, width / 2, 1,
                out.y, out.yStride, out.u, out.uStride, out.v, out.vStride
            )
        }
    }

    @Test
    fun `the reported I420 size matches the planes actually written`() {
        assertEquals(1280 * 720 * 3 / 2, YuvConverter.i420Size(1280, 720))
        assertEquals(17 * 9 + 2 * 9 * 5, YuvConverter.i420Size(17, 9))
    }
}
