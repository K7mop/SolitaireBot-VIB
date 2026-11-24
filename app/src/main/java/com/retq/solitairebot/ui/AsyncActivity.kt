package com.retq.solitairebot.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One worker owns each screen's decoding/training work; late results never touch a destroyed Activity. */
abstract class AsyncActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val disposed = AtomicBoolean(false)
    private val ownedBitmaps = mutableListOf<Bitmap>()

    protected fun ownBitmap(bitmap: Bitmap) { ownedBitmaps += bitmap }
    protected fun <T> background(work: () -> T, discard: (T) -> Unit = {},
        onError: (Exception) -> Unit, ready: (T) -> Unit) {
        if (disposed.get()) return
        worker.execute {
            if (disposed.get()) return@execute
            try {
                val result = work()
                main.post { if (disposed.get()) discard(result) else ready(result) }
            } catch (error: Exception) {
                main.post { if (!disposed.get()) onError(error) }
            }
        }
    }
    override fun onDestroy() {
        disposed.set(true)
        // Queue after all workers that could still read these bitmaps; never recycle under an OCR task.
        val bitmaps = ownedBitmaps.toList()
        ownedBitmaps.clear()
        worker.execute { bitmaps.forEach { if (!it.isRecycled) it.recycle() } }
        worker.shutdown()
        super.onDestroy()
    }
}
