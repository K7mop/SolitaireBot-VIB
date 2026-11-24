package android.graphics
class Bitmap(val observation: com.retq.solitairebot.vision.VisionReadout) {
    val width get() = observation.frameWidth
    val height get() = observation.frameHeight
    var isRecycled = false
    fun recycle() { isRecycled = true }
}
data class PointF(val x:Float, val y:Float)
class Path { fun moveTo(x:Float,y:Float){}; fun lineTo(x:Float,y:Float){} }
