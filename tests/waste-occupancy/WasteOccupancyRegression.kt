import android.graphics.Bitmap
import com.retq.solitairebot.capture.NormalizedBoardLayout
import com.retq.solitairebot.vision.AutomaticRegionReader
import com.retq.solitairebot.vision.GlyphTemplates

private fun green():Bitmap=Bitmap(360,720,IntArray(360*720){0x004b31})

fun main(){
 val layout=NormalizedBoardLayout.PORTRAIT_STARTER
 val box=layout.waste.toPixels(360,720)
 val w=box.right-box.left; val h=box.bottom-box.top
 val reader=AutomaticRegionReader(GlyphTemplates())
 val first=green();check(reader.seed(first,layout))
 check(reader.unidentifiedWasteFace(first,box,w,h)==null)
 val occupied=first.copyPixels().also { it.rectangle(box.left+2,box.top+2,w-4,h-4,0xfafafa) }
 check(reader.unidentifiedWasteFace(occupied,box,w,h)!=null)
 val backing=first.copyPixels().also { it.rectangle(box.left+2,box.top+2,w-4,h-4,0x206ca1) }
 check(reader.unidentifiedWasteFace(backing,box,w,h)==null)
 val thinStrip=first.copyPixels().also { it.rectangle(box.left+2,box.top+2,w-4,h/9,0xfafafa) }
 check(reader.unidentifiedWasteFace(thinStrip,box,w,h)==null)
 println("PASS: positive white waste face; reject blank felt, blue back and a top-edge stripe")
}
