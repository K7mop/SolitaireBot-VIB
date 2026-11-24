package regression

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.retq.solitairebot.capture.*
import com.retq.solitairebot.core.*
import com.retq.solitairebot.vision.*
import java.util.concurrent.atomic.AtomicReference

private fun card(code:String)=Card.parse(code)!!
private val shapes=mapOf(
    "A" to arrayOf("00100","01010","10001","11111","10001","10001","10001"),
    "K" to arrayOf("10001","10010","10100","11000","10100","10010","10001"),
    "Q" to arrayOf("01110","10001","10001","10001","10101","10010","01101"),
    "9" to arrayOf("01110","10001","10001","01111","00001","00010","11100"),
    "8" to arrayOf("01110","10001","10001","01110","10001","10001","01110"),
    "7" to arrayOf("11111","00001","00010","00100","01000","01000","01000"),
    "3" to arrayOf("11110","00001","00001","01110","00001","00001","11110"),
    "S" to arrayOf("00100","01110","11111","11111","10101","00100","01110"),
    "C" to arrayOf("01110","01110","10101","11111","10101","00100","01110"),
    "H" to arrayOf("01010","11111","11111","11111","01110","01110","00100"),
    "D" to arrayOf("00100","01110","01110","11111","01110","01110","00100"))
private fun glyph(b:Bitmap,x:Int,y:Int,label:String,color:Int) {
    val rows=shapes.getValue(label)
    rows.forEachIndexed { yy,row -> row.forEachIndexed {xx,ch ->if(ch=='1')b.rectangle(x+xx*2,y+yy*2,2,2,color)} }
}
private fun face(b:Bitmap,x:Int,y:Int,code:String) {
    b.rectangle(x,y,80,100,Color.BLACK);b.rectangle(x+1,y+1,78,98,Color.WHITE)
    val c=card(code);val ink=if(c.red)0xC51414 else Color.BLACK
    glyph(b,x+6,y+7,Card.RANKS[c.rank-1],ink);glyph(b,x+6,y+29,c.suit.symbol.toString(),ink)
}
private fun back(b:Bitmap,x:Int,y:Int){
    b.rectangle(x,y,80,100,0x123C90)
    for(yy in y+6 until y+94 step 10)b.rectangle(x+5,yy,70,3,0x85A5D0)
}
private fun box(x:Int,y:Int,w:Int,h:Int)=NormalizedBox(x/1000f,y/1000f,(x+w)/1000f,(y+h)/1000f)
private val layout=NormalizedBoardLayout(box(910,100,80,100),box(720,100,160,100),
    listOf(20,120,220,320).map{box(it,100,80,100)},(0..6).map{box(20+it*100,300,80,600)})
private fun emptyBoard()=BoardState(List(7){TableauColumn(0,emptyList())},Suit.entries.associateWith{0},null,StockStatus.UNKNOWN,1)
private fun train(lib:GlyphTemplates,code:String){val b=Bitmap(1000,1000);face(b,20,300,code);check(lib.train(b,20,300,80,100,card(code)))}

fun main(){
    var cases=0
    fun passed(name:String){cases++;println("PASS $name")}
    val context=Context("main")
    val lib=GlyphTemplates(context)
    listOf("KH","AS","QC","9C","8H","7S").forEach{train(lib,it)}
    check(lib.numberOfSamples==6 && lib.ranksLearned==6 && lib.suitsLearned==3 && lib.glyphPatternCount>=9)
    passed("card-example count is distinct from glyph coverage")

    val picture=Bitmap(1000,1000);face(picture,20,300,"KH")
    val declared=emptyBoard().copy(columns=listOf(TableauColumn(0,listOf(card("KH"))))+List(6){TableauColumn(0,emptyList())})
    val vision=CardVision(lib)
    val first=vision.inspect(picture,layout,declared,true,true,1000,1,11,2,manualSnapshot=picture)
    check(first.board.columns[0].top==card("KH"))
    check(first.observedEmptyColumns.size==6)
    check(MoveGenerator.generate(first.board).filterIsInstance<Move.TableauToTableau>().size==6)
    passed("confirmed empty columns survive a live read and accept kings")

    val second=vision.inspect(picture,layout,null,true,true,2000,2,11,2)
    check(second.verifiedFoundations.size==4 && second.observedEmptyColumns.size==6)
    passed("same-session empty visual evidence survives absence of manual data")

    val occupied=picture.copyPixels();face(occupied,120,100,"AS")
    val newReader=CardVision(lib)
    val changed=newReader.inspect(occupied,layout,declared,true,true,3000,3,11,2,manualSnapshot=picture)
    check(Suit.SPADES !in changed.observedEmptyFoundations)
    check(changed.board.foundations[Suit.SPADES]==1)
    passed("reviewed-empty/current-occupied cannot be certified empty from live pixels")

    val noReference=CardVision(lib).inspect(picture,layout,declared,true,true,4000,4,11,2)
    check(noReference.observedEmptyFoundations.isEmpty() && noReference.observedEmptyColumns.isEmpty())
    passed("manual declarations without exact reference bitmap give no empty proof")

    val stacked=Bitmap(1000,1000);face(stacked,20,300,"9C");face(stacked,20,360,"8H")
    val stackedRead=CardVision(lib).inspect(stacked,layout,null,true,true,5000,5,11,2)
    check(stackedRead.board.columns[0].faceUp==listOf(card("9C"),card("8H")))
    check(stackedRead.positions.getValue("T0").card==card("8H"))
    check(stackedRead.positions.getValue("T0:0").touchY<360)
    check(stackedRead.confidence==stackedRead.positions.values.minOf{it.confidence})
    passed("lowest physical card wins; multi-run anchors stay in exposed strips; minimum confidence")

    val trackedReader=CardVision(lib)
    val previous=Bitmap(1000,1000);face(previous,20,300,"9C")
    trackedReader.inspect(previous,layout,null,true,true,5100,51,11,2)
    val compressed=Bitmap(1000,1000);face(compressed,20,300,"9C");face(compressed,20,340,"8H")
    val tracked=trackedReader.inspect(compressed,layout,null,true,true,5200,52,11,2)
    check(tracked.board.columns[0].faceUp==listOf(card("9C"),card("8H")))
    check(tracked.positions.getValue("T0:0").touchY<340)
    passed("previously identified cards retain only matched visible-strip evidence after overlap")

    val unknownTop=Bitmap(1000,1000);face(unknownTop,20,300,"9C");face(unknownTop,20,360,"3D")
    val unknownRead=CardVision(lib).inspect(unknownTop,layout,null,true,true,6000,6,11,2)
    check(unknownRead.board.columns[0].top==null && "T0" in unknownRead.unknownRegions)
    passed("unknown lowest card never promotes a readable upper card")

    val fan=Bitmap(1000,1000);face(fan,720,100,"AS");face(fan,760,100,"KH")
    val fanRead=CardVision(lib).inspect(fan,layout,null,true,true,7000,7,11,2)
    check(fanRead.board.waste==card("KH") && fanRead.positions.getValue("W").x in 758..762)
    val reduced=Bitmap(1000,1000);face(reduced,720,100,"AS")
    val reducedRead=CardVision(lib).inspect(reduced,layout,null,true,true,8000,8,11,2)
    check(reducedRead.board.waste==card("AS") && reducedRead.positions.getValue("W").x in 718..722)
    passed("waste top follows the rightmost exposed card as fan shrinks")

    val stockEmpty=Bitmap(1000,1000);val stockFull=stockEmpty.copyPixels();back(stockFull,910,100)
    check(lib.trainRegion(stockFull,layout.stock.toPixels(1000,1000),GlyphTemplates.STOCK_BACK))
    check(lib.trainRegion(stockEmpty,layout.stock.toPixels(1000,1000),GlyphTemplates.EMPTY_STOCK))
    val stockReader=CardVision(lib)
    val full=stockReader.inspect(stockFull,layout,null,true,true,9000,9,11,2)
    val emptied=stockReader.inspect(stockEmpty,layout,null,true,true,10000,10,11,2)
    check(full.observedStock && full.board.stockStatus==StockStatus.AVAILABLE)
    check(emptied.observedStock && emptied.board.stockStatus==StockStatus.EMPTY)
    passed("stock back and empty stock are positive taught visual states")

    val rearmed=vision.inspect(picture,layout,declared,true,true,10500,105,11,2,manualSnapshot=picture)
    check(rearmed.observedEmptyColumns.size==6)
    val freshSession=vision.inspect(picture,layout,null,true,true,11000,11,12,2)
    check(freshSession.observedEmptyColumns.isEmpty() && freshSession.observedEmptyFoundations.isEmpty())
    passed("new session clears temporary manual visual evidence")

    val concurrent=Context("concurrency")
    val a=GlyphTemplates(concurrent);val b=GlyphTemplates(concurrent)
    val errors=AtomicReference<Throwable?>(null)
    val ta=Thread{try{repeat(20){train(a,"AS")}}catch(t:Throwable){errors.set(t)}}
    val tb=Thread{try{repeat(20){train(b,"KH")}}catch(t:Throwable){errors.set(t)}}
    ta.start();tb.start();ta.join();tb.join();errors.get()?.let{throw it};a.reloadIfChanged()
    check(a.numberOfSamples==40 && a.ranksLearned==2 && a.suitsLearned==2)
    passed("cross-instance training is serialized without lost revisions/examples")

    val storeBefore=lib.currentRevision
    val probe=picture.copyPixels();face(probe,20,300,"AS")
    val mutator=Thread{repeat(12){train(lib,if(it%2==0)"KH" else "AS")}}
    mutator.start();repeat(120){check(lib.recognize(probe,20,300,80,100)?.first==card("AS"))};mutator.join()
    check(lib.currentRevision>storeBefore)
    passed("concurrent recognition observes immutable template snapshots")
    println("ALL $cases vision regression scenarios passed (synthetic RGB fixtures, Android boundary doubles)")
}
