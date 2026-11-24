package com.retq.solitairebot.capture

import java.nio.ByteBuffer

/**
 * Worker-confined RGBA plane adapter. The ordinary tightly packed plane needs no
 * Java per-pixel conversion. Row padding is removed by bulk row copies into one
 * reusable buffer; uncommon pixel strides keep a tested compatibility path.
 * Returned bytes are borrowed until the next pack(), never queued to another thread.
 */
internal class RgbaPlanePacker {
    private var packed: ByteBuffer? = null
    private var row = ByteArray(0)

    fun pack(source: ByteBuffer, width: Int, height: Int, rowStride: Int,
        pixelStride: Int): ByteBuffer {
        require(width > 0 && height > 0 && width.toLong() * height <= 32_000_000L)
        require(pixelStride >= 4)
        val rowBytes = width.toLong() * 4
        val rowSpan = (width - 1L) * pixelStride + 4
        require(rowStride >= rowSpan && rowSpan <= Int.MAX_VALUE)
        val start = source.position()
        val end = start.toLong() + (height - 1L) * rowStride + rowSpan
        require(end <= source.limit()) { "Incomplete RGBA image plane" }
        val bytes = (rowBytes * height).toInt()
        val input = source.duplicate()
        if (pixelStride == 4 && rowStride.toLong() == rowBytes) {
            input.limit(start + bytes)
            return input.slice()
        }
        var output = packed
        if (output == null || output.capacity() != bytes) {
            output = ByteBuffer.allocateDirect(bytes)
            packed = output
        }
        output.clear()
        if (pixelStride == 4) {
            for (y in 0 until height) {
                val offset = start + y * rowStride
                input.limit(offset + rowBytes.toInt())
                input.position(offset)
                output.put(input)
            }
        } else {
            val span = rowSpan.toInt()
            if (row.size != span) row = ByteArray(span)
            for (y in 0 until height) {
                val offset = start + y * rowStride
                input.limit(offset + span)
                input.position(offset)
                input.get(row, 0, span)
                for (x in 0 until width) output.put(row, x * pixelStride, 4)
            }
        }
        output.flip()
        return output
    }

    fun clear() { packed = null; row = ByteArray(0) }
}
