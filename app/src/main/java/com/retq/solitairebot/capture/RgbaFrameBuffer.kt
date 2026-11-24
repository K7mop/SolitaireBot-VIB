package com.retq.solitairebot.capture

import android.graphics.Bitmap
import android.media.Image

/** Owned by one ImageReader worker; neither the Bitmap nor its pixel buffer escapes recognition. */
internal class RgbaFrameBuffer {
    private val stamps = FrameStampGate()
    private val packer = RgbaPlanePacker()
    private var bitmap: Bitmap? = null
    fun acceptTimestamp(timestamp: Long): Boolean = stamps.accept(timestamp)

    fun convert(image: Image): Bitmap {
        val plane = image.planes.first()
        val width = image.width
        val height = image.height
        val rgba = packer.pack(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
        var result = bitmap
        if (result == null || result.width != width || result.height != height) {
            result?.recycle()
            result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = result
        }
        // ImageReader RGBA_8888 and Bitmap ARGB_8888 use Android's native RGBA
        // byte layout. This copies the packed plane in native code, including its
        // alpha channel, instead of millions of ByteBuffer.get()/Color.rgb calls.
        result.copyPixelsFromBuffer(rgba)
        return result
    }

    fun close() {
        bitmap?.recycle()
        bitmap = null
        packer.clear()
    }
}
