package android.graphics
/** Pixel-backed boundary double. The production recognizer still reads actual RGB samples. */
class Bitmap(val width: Int, val height: Int, private val pixels: IntArray = IntArray(width*height) {0x006600}) {
    companion object { var singlePixelReads = 0L; var bulkReads = 0L }
    fun getPixel(x:Int,y:Int):Int { singlePixelReads++; require(x in 0 until width && y in 0 until height);return pixels[y*width+x] }
    fun getPixels(out:IntArray,offset:Int,stride:Int,x:Int,y:Int,w:Int,h:Int) {
        bulkReads++
        for (row in 0 until h) pixels.copyInto(out,offset+row*stride,(y+row)*width+x,(y+row)*width+x+w)
    }
    fun setPixel(x:Int,y:Int,c:Int) { pixels[y*width+x]=c }
    fun rectangle(x:Int,y:Int,w:Int,h:Int,c:Int) {for(yy in y until y+h)for(xx in x until x+w)setPixel(xx,yy,c)}
    fun copyPixels():Bitmap = Bitmap(width,height,pixels.copyOf())
}
object Color {
    const val WHITE=0xFFFFFF;const val BLACK=0
    fun red(c:Int)=(c shr 16) and 255
    fun green(c:Int)=(c shr 8) and 255
    fun blue(c:Int)=c and 255
}
