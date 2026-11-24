package com.retq.solitairebot.vision

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color

/**
 * Optional, local game-HUD reader using pixels from user-provided game examples.
 * Returns null on unfamiliar fonts/layouts. It never authorizes a physical move.
 */
class GreenHudReader(assets: AssetManager) {
    data class Reading(val score: Int?, val remainingSeconds: Int?, val confidence: Float)
    private data class Glyph(val digit: Char, val bits: BooleanArray)
    private val glyphs: List<Glyph> = try {
        assets.open("offline_hud_digits.txt").bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith('#') && it.contains('|') }
                .mapNotNull { line ->
                    val parts = line.split('|', limit = 2)
                    val bits = parts.getOrNull(1) ?: ""
                    if (bits.length == 240 && parts[0].length == 1 && parts[0][0].isDigit())
                        Glyph(parts[0][0], BooleanArray(240) { bits[it] == '1' }) else null
                }.toList()
        }
    } catch (_: Exception) { emptyList() }

    private data class Parsed(val text: String, val confidence: Float)

    private var sampledPixels = IntArray(0)

    @Synchronized fun read(bitmap: Bitmap): Reading {
        if (glyphs.isEmpty() || bitmap.width < 500 || bitmap.height < 900 ||
            bitmap.width.toFloat() / bitmap.height !in .43f.. .51f) return Reading(null, null, 0f)
        val scoreText = parse(bitmap, .215, .400, false)
        val timerText = parse(bitmap, .545, .730, true)
        val score = scoreText?.text?.toIntOrNull()?.takeIf { it in 0..150_000 }
        val seconds = timerText?.text?.takeIf { it.length == 4 }
            ?.let {
                val minutes = it.take(2).toIntOrNull()
                val part = it.drop(2).toIntOrNull()
                if (minutes != null && part != null && part in 0..59) minutes * 60 + part else null
            }?.takeIf { it in 0..300 }
        val confidence = listOfNotNull(if (score != null) scoreText?.confidence else null,
            if (seconds != null) timerText?.confidence else null).minOrNull() ?: 0f
        return Reading(score, seconds, confidence)
    }

    private fun parse(bitmap: Bitmap, left: Double, right: Double, timer: Boolean): Parsed? {
        val x0 = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 1)
        val x1 = (bitmap.width * right).toInt().coerceIn(x0 + 1, bitmap.width)
        val y0 = (bitmap.height * .160).toInt().coerceIn(0, bitmap.height - 1)
        val y1 = (bitmap.height * .198).toInt().coerceIn(y0 + 1, bitmap.height)
        val w = x1 - x0; val h = y1 - y0
        val mask = BooleanArray(w * h)
        val cols = IntArray(w)
        if (sampledPixels.size < w * h) sampledPixels = IntArray(w * h)
        // Two small bulk reads replace tens of thousands of JNI getPixel calls
        // on every observed frame. The optional HUD never delays a move gate.
        bitmap.getPixels(sampledPixels, 0, w, x0, y0, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val pixel = sampledPixels[y * w + x]
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            val on = r > 36 && g > 100 && g * 100 > r * 135 && g * 2 > b * 3
            mask[y * w + x] = on
            if (on) cols[x]++
        }
        val groups = mutableListOf<IntRange>()
        var start = -1
        for (x in 0..w) {
            val lit = x < w && cols[x] > 0
            if (lit && start == -1) start = x
            if (!lit && start >= 0) { groups.add(start until x); start = -1 }
        }
        val digits = StringBuilder()
        var lowest = 1f
        for (group in groups) {
            val minX=group.first; val maxX=group.last
            var minY=h; var maxY=-1
            for (x in group) for (y in 0 until h) if (mask[y * w + x]) {
                minY=minOf(minY,y); maxY=maxOf(maxY,y)
            }
            val gh = maxY-minY+1
            // The colon is lower than a digit, and typically 1/4 the stroke width.
            if (group.last-group.first+1 < 2 || gh < h * .29) continue
            val gw=maxX-minX+1
            val bits = BooleanArray(240)
            for (yy in 0 until 20) for (xx in 0 until 12) {
                val px=minX + ((xx+.5)*gw/12).toInt().coerceIn(0,gw-1)
                val py=minY + ((yy+.5)*gh/20).toInt().coerceIn(0,gh-1)
                bits[yy*12+xx]=mask[py*w+px]
            }
            var best=-1.0; var runner=-1.0; var bestDigit='?'
            for (glyph in glyphs) {
                var common=0; var union=0
                for (i in bits.indices) {
                    if (bits[i] && glyph.bits[i]) common++
                    if (bits[i] || glyph.bits[i]) union++
                }
                val score=if (union>0) common.toDouble()/union else 0.0
                if (score > best) {
                    if (bestDigit != glyph.digit) runner=maxOf(runner,best)
                    best=score; bestDigit=glyph.digit
                } else if (glyph.digit != bestDigit) runner=maxOf(runner,score)
            }
            val required=if (bestDigit=='5' || bestDigit=='9') .89 else .77
            if (best < required || best-runner < .025) return null
            lowest=minOf(lowest,best.toFloat())
            digits.append(bestDigit)
        }
        val n=digits.length
        if (timer && n != 4) return null
        if (!timer && n !in 1..5) return null
        return Parsed(digits.toString(),lowest)
    }
}
