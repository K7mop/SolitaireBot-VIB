package regression

import android.graphics.Bitmap
import android.content.res.AssetManager
import com.retq.solitairebot.vision.GreenHudReader

private fun paint(bitmap:Bitmap, asset:String, text:String, left:Double, center:Int) {
 val lines=java.io.File(asset).readLines().filter { it.contains('|') && !it.startsWith('#') }
 val pats=lines.groupBy({it.substringBefore('|').single()},{it.substringAfter('|')})
 val valid=text.filter{it.isDigit()}
 val dw=(bitmap.width/716.0*16).toInt().coerceAtLeast(9)
 val dh=(bitmap.height/1536.0*20).toInt().coerceAtLeast(12)
 var x=(bitmap.width*left).toInt() + (bitmap.width/716.0*47).toInt()
 if (center>=0) x=center-dw*valid.length/2
 val y=(bitmap.height*.160).toInt() + (bitmap.height/1536.0*20).toInt()
 for (ch in valid) {
   val bit=pats.getValue(ch).first()
   for (yy in 0 until dh)for(xx in 0 until dw){
     val sx=(xx*12/dw).coerceIn(0,11)
     val sy=(yy*20/dh).coerceIn(0,19)
     if(bit[sy*12+sx]=='1') bitmap.setPixel(x+xx,y+yy,0x59BA50)
   }
   x+=dw+(bitmap.width/716.0*2).toInt()
 }
}
fun main() {
 val asset=System.getProperty("solitaire.assets")+"/offline_hud_digits.txt"
 val bitmap=Bitmap(716,1536, IntArray(716*1536){0x003b2d})
 paint(bitmap,asset,"240",.215,-1)
 paint(bitmap,asset,"0347",.545,-1)
 val reader=GreenHudReader(AssetManager())
 val read=reader.read(bitmap)
 println("Score=${read.score} time=${read.remainingSeconds} confidence=${read.confidence}")
 // The source pixel regression on user screenshots is run separately with a small
 // Python mask harness; this synthetic example exercises Android pixel plumbing.
 check(read.score==240) { "score decode failed" }
 check(read.remainingSeconds==227) { "timer decode failed" }
 println("PASS: green HUD source path decodes score and timer in a synthetic image")
 val malformed=Bitmap(716,1536,IntArray(716*1536){0x003b2d})
 paint(malformed,asset,"240",.215,-1)
 paint(malformed,asset,"0074",.545,-1)
 check(reader.read(malformed).remainingSeconds==null) { "00:74 is not a valid clock" }
 println("PASS: malformed seconds >=60 do not become a trusted clock")
}
