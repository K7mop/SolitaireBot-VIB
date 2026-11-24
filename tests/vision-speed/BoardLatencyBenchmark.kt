package regression

import android.content.Context
import android.graphics.Bitmap
import com.retq.solitairebot.capture.*
import com.retq.solitairebot.core.*
import com.retq.solitairebot.vision.*
import java.io.File
import javax.imageio.ImageIO

private fun image(file: File, targetWidth: Int = 0, targetHeight: Int = 0): Bitmap {
    val original = ImageIO.read(file)
    val source = if (targetWidth > 0) java.awt.image.BufferedImage(targetWidth,targetHeight,java.awt.image.BufferedImage.TYPE_INT_RGB).also {
        val graphics=it.createGraphics()
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(original,0,0,targetWidth,targetHeight,null)
        graphics.dispose()
    } else original
    return Bitmap(source.width, source.height, source.getRGB(0,0,source.width,source.height,null,0,source.width))
}

fun main(args: Array<String>) {
    val fixtures = File(args[0]); val assets = GlyphTemplates(Context("vision-latency"))
    val initial = image(File(fixtures,"initial.png"))
    val layout = AutoBoardSetup.detect(initial,assets).layout ?: error("Synthetic initial board unsupported")
    val reader = CardVision(assets); val hud = GreenHudReader(Context().assets)
    val initialRead = reader.inspect(initial,layout,null,true,true,1000,1,1,1,automatic=true,automaticSnapshot=initial)
    check(initialRead.board.columns.map{it.top?.code} == listOf("AS","KD","QC","JH","TC","9D","8S"))
    println("initial=${initialRead.summary()} unknown=${initialRead.unknownRegions}")
    val durations = mutableListOf<Double>()
    var singleReads = 0L; var bulkReads = 0L
    repeat(70) { i ->
        val previousSingle = Bitmap.singlePixelReads; val previousBulk = Bitmap.bulkReads
        val started = System.nanoTime()
        hud.read(initial)
        val result = reader.inspect(initial,layout,null,true,true,2000L+i*100,i+2L,1,1,automatic=true)
        check(result.board.columns == initialRead.board.columns)
        if (i >= 20) {
            durations += (System.nanoTime()-started)/1_000_000.0
            singleReads += Bitmap.singlePixelReads - previousSingle
            bulkReads += Bitmap.bulkReads - previousBulk
        }
    }
    val times = durations.sorted()
    println("DESKTOP full-board+HUD steady-frame medianMs=%.3f p95Ms=%.3f maxMs=%.3f samples=%d singlePixelCallsPerFrame=%d bulkCallsPerFrame=%d".format(
        times[times.size/2], times[(times.size*.95).toInt().coerceAtMost(times.lastIndex)], times.last(),times.size,
        singleReads/times.size,bulkReads/times.size))
    var frameId = 500L
    val sequence = listOf("drawn","foundation_and_empty","exposed_back","flipped","recycle","initial")
    val varying = mutableListOf<Double>()
    val pictures = sequence.associateWith { image(File(fixtures,"$it.png")) }
    repeat(12) { round -> for (name in sequence) {
        val bitmap = pictures.getValue(name); val start = System.nanoTime();frameId++
        val result = reader.inspect(bitmap,layout,null,true,true,frameId*100,frameId,1,1,automatic=true)
        if (round > 1) varying += (System.nanoTime()-start)/1_000_000.0
        when(name) {
            "drawn" -> check(result.board.waste?.code == "5H")
            "foundation_and_empty" -> check(result.board.foundations[Suit.SPADES] == 1 && 0 in result.observedEmptyColumns)
            "exposed_back" -> check(result.board.columns[1].exposedBack)
            "flipped" -> check(result.board.columns[1].top?.code == "KH")
            "recycle" -> check(result.board.recycleAvailable)
        }
    } }
    val changes = varying.sorted()
    println("DESKTOP changing-board medianMs=%.3f p95Ms=%.3f maxMs=%.3f samples=%d (all semantic transitions checked)".format(
        changes[changes.size/2], changes[(changes.size*.95).toInt().coerceAtMost(changes.lastIndex)],changes.last(),changes.size))
    if (args.size > 1) {
        val phone = File(args[1])
        // Explicit observed screen geometry for these two mid-game screenshots;
        // this is a regression input, not a claim of automatic setup mid-game.
        val xs = listOf(26,122,217,312,408,503,599)
        fun box(l:Int,t:Int,r:Int,b:Int)=NormalizedBox(l/716f,t/1536f,r/716f,b/1536f)
        val photoLayout=NormalizedBoardLayout(box(599,490,686,616),box(441,490,591,616),
            listOf(26,122,217,312).map{box(it,490,it+87,616)},xs.map{box(it,649,it+87,861)})
        for (name in listOf("137242.jpg","137246.jpg")) for (scale in listOf(716 to 1536,1080 to 2316)) {
            val b=if(scale.first==716)image(File(phone,name)) else image(File(phone,name),scale.first,scale.second)
            val newReader=CardVision(assets)
            val read=newReader.inspect(b,photoLayout,null,true,true,1000,1,17,1,automatic=true,automaticSnapshot=b)
            println("USER $name size=${scale.first}x${scale.second} ${read.summary()} stock=${read.board.stockStatus} unknown=${read.unknownRegions} hud=${hud.read(b)} (1080 is same-source resampling, not an independent phone capture)")
        }
    }
}
