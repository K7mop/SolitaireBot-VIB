package com.retq.solitairebot.capture

data class CaptureConfig(
    val targetWidth: Int = 1280,
    val targetHeight: Int = 720,
    val maxFramesPerSecond: Int = 2
) {
    init {
        require(targetWidth > 0 && targetHeight > 0)
        require(maxFramesPerSecond in 1..10)
    }
}
