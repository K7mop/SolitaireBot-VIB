package regression
import android.content.Context
import android.graphics.Bitmap
import com.retq.solitairebot.vision.GlyphTemplates
import java.io.File
import javax.imageio.ImageIO
fun main(args:Array<String>) {
    val root=File(args[0]);val lib=GlyphTemplates(Context("automatic-knowledge"))
    check(lib.glyphPatternCount==0 && lib.ranksLearned==0 && lib.suitsLearned==0)
    val loadStart=System.nanoTime()
    check(lib.automaticRecognitionReady) { "Bundled recognition knowledge not loaded" }
    val loadMs=(System.nanoTime()-loadStart)/1_000_000.0
    var correct=0;var unknown=0;var wrong=0;var rejected=0;var suitCorrect=0;var recycleCorrect=0
    val failures=mutableListOf<String>()
    val byFamily=mutableMapOf<String,IntArray>()
    root.resolve("manifest.txt").readLines().forEach {line->
        val (file,expect)=line.split('|');val im=ImageIO.read(root.resolve(file))
        val b=Bitmap(im.width,im.height,im.getRGB(0,0,im.width,im.height,null,0,im.width))
        if(expect=="RECYCLE" || expect=="NO_RECYCLE") {
            val actual=lib.recognizeRecycleAutomatic(b,0,0,b.width,b.height)
            if ((actual!=null)==(expect=="RECYCLE"))recycleCorrect++ else failures+="$file expected $expect got $actual"
        } else if (expect.startsWith("SUIT:")) {
            val actual=lib.recognizeSuitAutomatic(b,0,0,b.width,b.height)
            if (actual?.first?.symbol.toString()==expect.removePrefix("SUIT:")) suitCorrect++ else failures+="$file expected $expect got $actual"
        } else {
            val ordinary=lib.recognizeAutomatic(b,0,0,b.width,b.height)
            val actual=lib.recognizeAutomatic(b,0,0,b.width,b.height,fullFaceVisible=true)
            check(ordinary==null || actual==ordinary){"A clear original classification changed in full-face mode"}
            if(expect=="-") {
                if(actual==null)rejected++ else failures+="$file expected rejection, got $actual"
            } else {
                val family=file.substringBeforeLast('_');val stats=byFamily.getOrPut(family){IntArray(3)}
                if(actual==null){unknown++;stats[1]++;failures+="$file unknown"}
                else if(actual.first.code==expect){correct++;stats[0]++}
                else {wrong++;stats[2]++;failures+="$file WRONG expected $expect got $actual"}
            }
        }
    }
    byFamily.forEach{(key,counts)->println("$key correct=${counts[0]} unknown=${counts[1]} wrong=${counts[2]}")}
    println("Automatic card fixtures: correct=$correct unknown=$unknown wrong=$wrong; negative rejections=$rejected/28; suit icons=$suitCorrect/16; recycle=$recycleCorrect/8")
    failures.filter{!it.endsWith(" unknown")}.forEach(::println)
    check(wrong==0){"A confidently wrong card must never pass validation"}
    check(rejected==28){"Negative-input rejection regression"}
    check(recycleCorrect==8){"Recycle glyph recognition regression"}
    check(suitCorrect==16){"Suit icon recognition regression"}
    check(correct>=600){"Insufficient clear generated card coverage"}
    check(lib.glyphPatternCount==0){"Bundled knowledge must not pretend to be user teaching"}
    val batch=listOf("AS","2H","3C","4D","5S","6H","7C").map { code ->
        val im=ImageIO.read(root.resolve("dejavu_sans_33_$code.png"))
        Bitmap(im.width,im.height,im.getRGB(0,0,im.width,im.height,null,0,im.width))
    }
    val durations=mutableListOf<Double>()
    repeat(30) { iteration ->
        val start=System.nanoTime()
        batch.forEach { check(lib.recognizeAutomatic(it,0,0,it.width,it.height)!=null) }
        if(iteration>=5)durations+=(System.nanoTime()-start)/1_000_000.0
    }
    val sorted=durations.sorted()
    println("Desktop JVM benchmark: asset load %.2f ms; 7-card batch median %.2f ms; max %.2f ms; samples=%d after5warmup; excludes capture/layout/Android".format(loadMs,sorted[sorted.size/2],sorted.last(),sorted.size))

}
