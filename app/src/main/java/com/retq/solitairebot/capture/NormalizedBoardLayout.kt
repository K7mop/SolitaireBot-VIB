package com.retq.solitairebot.capture

data class NormalizedBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }
    fun toPixels(width: Int, height: Int): PixelBox {
        require(width > 0 && height > 0)
        return PixelBox((left*width).toInt(), (top*height).toInt(),
            (right*width).toInt(), (bottom*height).toInt())
    }
}
data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class NormalizedBoardLayout(
    val stock: NormalizedBox,
    val waste: NormalizedBox,
    val foundations: List<NormalizedBox>,
    val tableauColumns: List<NormalizedBox>,
    /** Normalized bounds of the complete game viewport inside the captured display. */
    val screenBounds: NormalizedBox = NormalizedBox(0f, 0f, 1f, 1f)
) {
    init {
        require(foundations.size == 4) { "Four foundation regions required" }
        require(tableauColumns.size == 7) { "Seven tableau regions required" }
    }
    companion object {
        // Starter layout only. Calibrate using an actual screenshot before use.
        val PORTRAIT_STARTER = NormalizedBoardLayout(
            stock=NormalizedBox(.76f,.04f,.94f,.19f),
            waste=NormalizedBox(.55f,.04f,.73f,.19f),
            foundations=listOf(
                NormalizedBox(.04f,.04f,.20f,.19f),
                NormalizedBox(.22f,.04f,.38f,.19f),
                NormalizedBox(.40f,.04f,.54f,.19f),
                NormalizedBox(.57f,.21f,.72f,.36f)
            ),
            tableauColumns=(0..6).map { i ->
                val left=.025f+i*.139f
                NormalizedBox(left,.23f,(left+.125f).coerceAtMost(.99f),.96f)
            }
        )
    }
}
