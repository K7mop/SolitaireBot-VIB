package com.retq.solitairebot.control

/** Pure geometry: the emergency stop never covers a calibrated card/search region. */
object OverlayPlacement {
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun intersects(other: Rect): Boolean = left < other.right && right > other.left &&
            top < other.bottom && bottom > other.top
    }

    fun find(width: Int, height: Int, size: Int, topInset: Int, bottomInset: Int,
             margin: Int, regions: List<Rect>): Rect? {
        if (size <= 0 || width < size + margin * 2 || height < size + topInset + bottomInset) return null
        val right = width - margin - size
        val bottom = height - bottomInset - margin - size
        val left = margin
        val top = topInset + margin
        if (right < left || bottom < top) return null
        val xs = (listOf(left, right) + regions.flatMap { listOf(it.left - size - margin, it.right + margin) })
            .filter { it in left..right }.distinct()
        val ys = (listOf(top, bottom) + regions.flatMap { listOf(it.top - size - margin, it.bottom + margin) })
            .filter { it in top..bottom }.distinct()
        return ys.asSequence().flatMap { y -> xs.asSequence().map { x -> Rect(x, y, x + size, y + size) } }
            .firstOrNull { candidate -> regions.none { region ->
                candidate.intersects(Rect(region.left - margin, region.top - margin,
                    region.right + margin, region.bottom + margin))
            } }
    }
}
