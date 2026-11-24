package com.retq.solitairebot.vision

import android.graphics.Bitmap
import com.retq.solitairebot.capture.PixelBox

/** One bulk RGB read per analyzed frame. The backing array is reused, but a
 * previous frame is never consulted: source identity is scoped to the caller
 * and revoked in finally, including exceptions and cancelled analyses. */
internal object FramePixels {
    private class Buffer {
        var pixels = IntArray(0)
        var source: Bitmap? = null
        var left = 0
        var top = 0
        var width = 0
        var height = 0
    }
    private val local = ThreadLocal.withInitial { Buffer() }

    fun <T> withFrame(bitmap: Bitmap, bounds: PixelBox, block: () -> T): T {
        val buffer = checkNotNull(local.get())
        check(buffer.source == null) { "Nested frame reads are not supported" }
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        val required = width * height
        if (buffer.pixels.size < required) buffer.pixels = IntArray(required)
        bitmap.getPixels(buffer.pixels, 0, width, left, top, width, height)
        buffer.left = left; buffer.top = top; buffer.width = width; buffer.height = height
        buffer.source = bitmap
        return try { block() } finally { buffer.source = null }
    }

    fun get(bitmap: Bitmap, x: Int, y: Int): Int {
        val buffer = checkNotNull(local.get())
        return if (buffer.source === bitmap && x >= buffer.left && y >= buffer.top &&
            x < buffer.left + buffer.width && y < buffer.top + buffer.height)
            buffer.pixels[(y - buffer.top) * buffer.width + x - buffer.left]
        else bitmap.getPixel(x, y)
    }
}
