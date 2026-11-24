package com.retq.solitairebot.capture

import java.nio.ByteBuffer
import java.util.Random
import kotlin.system.measureNanoTime

private fun fails(block: () -> Unit): Boolean = try { block(); false } catch (_: IllegalArgumentException) { true }

fun main() {
    var checks = 0
    fun ok(test: Boolean, reason: String) { check(test) { reason }; checks++; println("PASS $checks: $reason") }
    val packer = RgbaPlanePacker()
    val random = Random(810)
    // Differential test on complete RGBA bytes, including all color channels and
    // non-opaque alpha: padding and a nonzero input position must never shift a pixel.
    for (pixelStride in listOf(4, 5, 8)) for (padding in listOf(0, 1, 16, 64)) {
        val width = 19; val height = 13; val start = 7
        val rowStride = width * pixelStride + padding
        val last = start + (height - 1) * rowStride + (width - 1) * pixelStride + 4
        val data = ByteBuffer.allocateDirect(last)
        val expected = ByteArray(width * height * 4)
        random.nextBytes(expected)
        for (y in 0 until height) for (x in 0 until width) for (channel in 0..3)
            data.put(start + y * rowStride + x * pixelStride + channel,
                expected[(y * width + x) * 4 + channel])
        data.position(start)
        val actual = ByteArray(expected.size)
        packer.pack(data, width, height, rowStride, pixelStride).get(actual)
        ok(actual.contentEquals(expected) && data.position() == start && data.limit() == last,
            "Exact RGBA bytes, stride=$pixelStride padding=$padding; source cursor preserved")
    }
    val truncated = ByteBuffer.allocate(7)
    ok(fails { packer.pack(truncated, 2, 1, 8, 4) }, "Rejects last byte outside buffer")
    ok(fails { packer.pack(ByteBuffer.allocate(16), 2, 2, 4, 4) }, "Rejects overlapping rows")
    ok(fails { packer.pack(ByteBuffer.allocate(16), 2, 2, 8, 3) }, "Rejects unsupported short pixel stride")
    ok(fails { packer.pack(ByteBuffer.allocate(16), Int.MAX_VALUE, 2, 8, 4) }, "Rejects dimensions before overflowing allocation")
    val reusable = ByteBuffer.allocateDirect(48)
    val old = packer.pack(reusable, 2, 3, 16, 4)
    reusable.put(0, 92.toByte())
    val fresh = packer.pack(reusable, 2, 3, 16, 4)
    ok(old === fresh && fresh.get(0) == 92.toByte(), "Padded buffer reused but updated from current frame")
    packer.clear()
    ok(packer.pack(reusable, 2, 3, 16, 4) !== old, "Close releases retained scratch buffer")

    // CPU microbenchmark only: old Java per-pixel RGBA→ARGB conversion vs the
    // new native-buffer preparation. Android's final bitmap copy and actual
    // device capture/recognition are deliberately NOT claimed by this number.
    val width = 1080; val height = 2316; val stride = width * 4 + 64
    val pixels = ByteBuffer.allocateDirect(stride * height)
    for (i in 0 until pixels.capacity()) pixels.put(i, (i * 17).toByte())
    val oldArgb = IntArray(width * height)
    fun original() {
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * stride + x * 4
            oldArgb[y * width + x] = (255 shl 24) or ((pixels.get(i).toInt() and 255) shl 16) or
                ((pixels.get(i + 1).toInt() and 255) shl 8) or (pixels.get(i + 2).toInt() and 255)
        }
    }
    repeat(12) { original(); packer.pack(pixels, width, height, stride, 4) }
    val before = LongArray(35); val after = LongArray(35)
    var checksum = 0
    repeat(35) { i ->
        before[i] = measureNanoTime { original() }
        checksum = checksum xor oldArgb[(i * 91) % oldArgb.size]
        after[i] = measureNanoTime { val bytes = packer.pack(pixels, width, height, stride, 4); checksum = checksum xor bytes.get(i).toInt() }
    }
    before.sort(); after.sort()
    println("FRAME_PREPARATION_ONLY width=$width height=$height oldMedianMs=${before[17] / 1e6} newMedianMs=${after[17] / 1e6} oldP95Ms=${before[33] / 1e6} newP95Ms=${after[33] / 1e6} checksum=$checksum")
    println("ALL $checks frame packing checks passed; no Android bitmap/device benchmark")
}
