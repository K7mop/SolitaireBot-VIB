package regression
import android.content.Context
import android.graphics.Bitmap
import com.retq.solitairebot.capture.AutoBoardSetup
import com.retq.solitairebot.core.*
import com.retq.solitairebot.vision.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
private fun load(root:File,name:String):Bitmap {
    val im=ImageIO.read(File(root,name+".png"))
    return Bitmap(im.width,im.height,im.getRGB(0,0,im.width,im.height,null,0,im.width))
}
fun main(args:Array<String>) {
    val root=File(args[0]);val lib=GlyphTemplates(Context("auto-board"));var cases=0
    fun pass(name:String){cases++;println("PASS $name")}
    val initial=load(root,"initial")
    val geometry=AutoBoardSetup.findGeometry(initial)
    println("Detected geometry: $geometry")
    check(geometry!=null)
    check(abs(geometry.width-115)<=2 && abs(geometry.height-155)<=3 && abs(geometry.tableauTop-650)<=3)
    pass("seven columns and card dimensions derived from pixels")
    val setup=AutoBoardSetup.detect(initial,lib);println(setup)
    val layout=setup.layout ?: error(setup.message)
    check(abs(layout.stock.toPixels(920,1850).top-450)<=3)
    check(layout.foundations.size==4 && layout.tableauColumns.size==7)
    pass("all thirteen regions and four foundation suits detected")
    val reader=CardVision(lib)
    fun read(name:String,id:Long,seed:Boolean=false)=reader.inspect(load(root,name),layout,null,true,true,
        id*1000,id,11,5,automatic=true,automaticSnapshot=if(seed)initial else null)
    val before=read("initial",1,true);println(before.summary());println(before.warnings)
    check(before.board.columns.map{it.top?.code}==listOf("AS","KD","QC","JH","TC","9D","8S"))
    check(before.observedEmptyFoundations.size==4 && before.observedWasteEmpty)
    check(before.board.stockStatus==StockStatus.AVAILABLE && before.unknownRegions.isEmpty())
    check(before.board.columns[0].hiddenCount==0 && before.board.columns.drop(1).all{it.hiddenCount==null})
    pass("first image populates visible cards and positive empty/back states without teaching")
    val after=read("foundation_and_empty",2)
    println(after.summary());println(after.warnings)
    check(after.observedEmptyColumns.contains(0) && after.board.foundations[Suit.SPADES]==1)
    check(VisualSafety.verifiesMove(before.board,after.board,Move.TableauToFoundation(0,Card.parse("AS")!!),after.evidence(),before.evidence()))
    pass("a real visual transition verifies foundation move and newly empty column")
    val back=read("exposed_back",3)
    check(back.board.columns[1].exposedBack && back.tableauBackPoints.containsKey(1))
    check(back.board.columns[1].faceUp.isEmpty() && 1 in back.evidence().exposedTableauBacks)
    pass("matched exposed back yields a flip target without invented card identity")
    val flipped=read("flipped",4)
    check(flipped.board.columns[1].top?.code=="KH" && !flipped.board.columns[1].exposedBack)
    check(VisualSafety.verifiesMove(back.board,flipped.board,Move.FlipTableau(1),flipped.evidence(),back.evidence()))
    pass("fresh recognized face verifies successful flip")
    val drawn=read("drawn",5)
    check(drawn.board.waste?.code=="5H")
    check(VisualSafety.verifiesMove(before.board,drawn.board,Move.DrawThree,drawn.evidence(),before.evidence()))
    pass("stock tap is verified by changed top waste card")
    val recycle=read("recycle",6)
    check(recycle.board.stockStatus==StockStatus.EMPTY && recycle.board.recycleAvailable && recycle.evidence().recycleAvailable)
    check(MoveGenerator.generate(recycle.board).contains(Move.RecycleStock))
    pass("positive circular-arrow affordance enables bounded recycling")
    val unknown=read("unknown_stock",7)
    check(unknown.board.stockStatus==StockStatus.UNKNOWN && "S" in unknown.unknownRegions)
    pass("an unreadable stock is not silently treated as empty")
    val newSession=reader.inspect(initial,layout,null,true,true,7000,7,12,5,automatic=true)
    check(!newSession.observedStock && newSession.observedEmptyFoundations.isEmpty() && reader.needsAutomaticReference())
    pass("a new session loses initial-image evidence until rebound")
    val connectedBacks=load(root,"connected_backs")
    val connectedGeometry=AutoBoardSetup.findGeometry(connectedBacks) ?: error("connected outlines rejected")
    check(abs(connectedGeometry.width-115)<=3 && abs(connectedGeometry.height-155)<=3 &&
        abs(connectedGeometry.tableauTop-650)<=3)
    check(AutoBoardSetup.detect(connectedBacks,lib).layout!=null)
    pass("back-outline bridges do not inflate face height or reject seven columns")
    val footerImage=load(root,"footer")
    val footerLayout=AutoBoardSetup.detect(footerImage,lib).layout ?: error("footer layout rejected")
    check(footerLayout.tableauColumns.all{it.toPixels(920,1850).bottom<1300})
    pass("footer controls excluded from column search regions")
    val scaled=AutoBoardSetup.findGeometry(load(root,"scaled")) ?: error("scaled geometry rejected")
    check(abs(scaled.width-173)<=3 && abs(scaled.height-233)<=4 && abs(scaled.tableauTop-975)<=3)
    pass("geometry scales with full capture resolution")
    val paleImage=load(root,"pale_foundations")
    val paleLayout=AutoBoardSetup.detect(paleImage,lib).layout ?: error("pale foundation layout rejected")
    val paleRead=CardVision(lib).inspect(paleImage,paleLayout,null,true,true,8000,8,13,6,
        automatic=true,drawCount=1,automaticSnapshot=paleImage)
    check(paleRead.observedEmptyFoundations.size==4 && paleRead.board.drawCount==1)
    check(MoveGenerator.generate(paleRead.board).contains(Move.DrawOne))
    pass("pale placeholders require blank index corners; Draw 1 retained")
    val permuted=AutoBoardSetup.detect(load(root,"permuted_foundations"),lib).layout ?: error("permuted order rejected")
    check(permuted.foundations[0].left>permuted.foundations[3].left)
    pass("foundation coordinates are reordered from observed suit icons")
    for(name in listOf("blank","occluded","truncated_face","missing_foundation_icon")) {
        check(AutoBoardSetup.detect(load(root,name),lib).layout==null){"Unsafe automatic setup accepted $name"}
    }
    pass("blank, occluded, truncated and unlabelled layouts reject auto setup")
    val recoveryReader=CardVision(lib)
    val recoveryImage=load(root,"foundation_and_empty")
    val declared=after.board.copy(columns=after.board.columns.mapIndexed { i,c -> if(i==1)c.copy(hiddenCount=2) else c },redealsRemaining=1)
    val armed=recoveryReader.inspect(recoveryImage,layout,declared,true,true,9000,9,14,7,
        manualSnapshot=recoveryImage,automatic=true,automaticSnapshot=initial)
    check(armed.board.columns[1].hiddenCount==2)
    val uncertain=Move.TableauToTableau(1,0,0,listOf(Card.parse("KD")!!))
    check(MoveGenerator.generate(armed.board).contains(uncertain))
    recoveryReader.unconfirmedMove(uncertain)
    val reread=recoveryReader.inspect(recoveryImage,layout,declared,true,true,10000,10,14,7,automatic=true)
    check(reread.board.columns[1].hiddenCount==null)
    recoveryReader.unconfirmedMove(Move.RecycleStock)
    val afterUnconfirmedRecycle=recoveryReader.inspect(recoveryImage,layout,declared,true,true,11000,11,14,7,automatic=true)
    check(afterUnconfirmedRecycle.board.redealsRemaining==null)
    pass("ambiguous touches revoke hidden-count and redeal assumptions before recovery")
    println("ALL $cases automatic-board RGB regression scenarios passed; synthetic supported artwork only")
}
