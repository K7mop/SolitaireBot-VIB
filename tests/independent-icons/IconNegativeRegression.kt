package regression
import android.content.Context
import android.graphics.Bitmap
import com.retq.solitairebot.vision.GlyphTemplates
import java.io.File
import javax.imageio.ImageIO
import org.json.JSONArray

fun main(args:Array<String>) {
 val root=File(args[0])
 val cases=JSONArray(File(root,"manifest.json").readText())
 val lib=GlyphTemplates(Context("independent-icon-negative"))
 var falseReset=0
 var missedBusy=0
 for(i in 0 until cases.length()) {
  val c=cases.getJSONObject(i)
  val im=ImageIO.read(File(root,"fixtures/"+c.getString("name")))
  val b=Bitmap(im.width,im.height,im.getRGB(0,0,im.width,im.height,null,0,im.width))
  val reset=lib.recognizeRecycleAutomatic(b,0,0,b.width,b.height)
  val busy=lib.stockBusyAutomatic(b,0,0,b.width,b.height)
  if(reset!=null){falseReset++;println("FALSE RESET ${c.getString("name")}: $reset busy=$busy")}
  if(!c.isNull("busy") && c.getBoolean("busy") && !busy){missedBusy++;println("MISSED BUSY ${c.getString("name")}")}
 }
 println("Independent icon geometry controls=${cases.length()}, false reset=$falseReset, missed positive gold spinner=$missedBusy")
 check(falseReset==0){"Non-reset control classified as reset"}
 check(missedBusy==0){"Gold spinner missed"}
}
