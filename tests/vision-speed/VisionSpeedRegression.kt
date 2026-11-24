package regression

import android.content.Context
import android.graphics.Bitmap
import com.retq.solitairebot.capture.*
import com.retq.solitairebot.core.*
import com.retq.solitairebot.vision.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

private fun pixels(file:File):Bitmap {
 val image=ImageIO.read(file)
 return Bitmap(image.width,image.height,image.getRGB(0,0,image.width,image.height,null,0,image.width))
}
fun main(args:Array<String>) {
 val root=File(args[0]);val lib=GlyphTemplates(Context("speed-regression"));var passed=0
 fun pass(s:String){passed++;println("PASS $s")}
 check(lib.automaticRecognitionReady)
 val stockBlue=pixels(File(root,"stock-130.png"))
 check(!lib.stockBusyAutomatic(stockBlue,0,0,stockBlue.width,stockBlue.height))
 check(lib.recognizeRecycleAutomatic(stockBlue,0,0,stockBlue.width,stockBlue.height)==null)
 pass("observed ordinary blue stock is not a busy or recycle icon")
 for(time in listOf(135,175,220)) {
  val b=pixels(File(root,"stock-$time.png"))
  check(lib.stockBusyAutomatic(b,0,0,b.width,b.height))
  check(lib.recognizeRecycleAutomatic(b,0,0,b.width,b.height)==null)
 }
 pass("three observed gold rings on blue/green backgrounds block taps and never recycle")
 val calibrations=setOf("reset-green-04.png","reset-later-08.png")
 var heldOutAccepted=0;var heldOutUnknown=0;var calibrationAccepted=0
 for(file in root.listFiles()!!.filter{it.name.startsWith("reset-")}) {
  val b=pixels(file);check(!lib.stockBusyAutomatic(b,0,0,b.width,b.height))
  val match=lib.recognizeRecycleAutomatic(b,0,0,b.width,b.height)
  if(file.name in calibrations){check(match!=null);calibrationAccepted++}
  else if(match!=null)heldOutAccepted++ else heldOutUnknown++
 }
 check(calibrationAccepted==2 && heldOutAccepted>=4)
 println("Green reset crops: calibration=$calibrationAccepted/2; heldout accepted=$heldOutAccepted unknown=$heldOutUnknown; filmed blur remains unsupported when uncertain")
 pass("faint green static reset uses its shape; unclear heldouts remain unknown")
 var calibrationCards=0;var heldOutCards=0;var unknownCards=0
 for(file in root.listFiles()!!.filter{it.name.startsWith("card-")}) {
  val b=pixels(file);val actual=lib.recognizeAutomatic(b,0,0,b.width,b.height,fullFaceVisible=true)
  val expected=file.nameWithoutExtension.substringAfterLast('-')
  check(actual==null || actual.first.code==expected){"Wrong identity: ${file.name} -> $actual"}
  if(file.name.startsWith("card-calibration-")){check(actual!=null);calibrationCards++}
  else if(actual!=null)heldOutCards++ else unknownCards++
  check(lib.recognizeRecycleAutomatic(b,0,0,b.width,b.height)==null)
 }
 check(calibrationCards==9 && heldOutCards==7 && unknownCards==0)
 println("Observed card crops: calibration=$calibrationCards/9; heldout accepted=$heldOutCards unknown=$unknownCards; confidently wrong=0")
 pass("fresh card masks retain identity with no false recycle recognition")
 val ambiguousFive=pixels(File(root,"card-heldout-5C.png"))
 val ambiguousSix=pixels(File(root,"card-heldout-6H.png"))
 for (card in listOf(ambiguousFive,ambiguousSix)) {
  check(lib.recognizeAutomatic(card,0,0,card.width,card.height)==null)
  check(lib.recognizeAutomatic(card,0,0,card.width,card.height,fullFaceVisible=false)==null)
 }
 fun replaceLower(target:Bitmap, source:Bitmap, turn:Boolean=false):Bitmap {
  val out=target.copyPixels()
  val left=(target.width*.60f).roundToInt();val right=(target.width*.95f).roundToInt()
  val top=(target.height*.70f).roundToInt();val bottom=(target.height*.96f).roundToInt()
  for(y in top until bottom)for(x in left until right)
   out.setPixel(x,y,source.getPixel(if(turn)right-1-(x-left)else x,if(turn)bottom-1-(y-top)else y))
  return out
 }
 // The lower pixels may belong to a neighboring card when the upper card is
 // covered. Such a view is not authorized to resolve an ambiguous upper rank.
 val covered=replaceLower(ambiguousFive,pixels(File(root,"card-calibration-6S.png")))
 check(lib.recognizeAutomatic(covered,0,0,covered.width,covered.height,fullFaceVisible=false)==null)
 check(lib.recognizeAutomatic(covered,0,0,covered.width,covered.height,fullFaceVisible=true)==null)
 // This deck's lower 6 is upright. A rotated lower 6 looks like a 9 and must
 // conflict with the upper 6, never be fixed by trying another orientation.
 val reversedSix=replaceLower(ambiguousSix,ambiguousSix,turn=true)
 check(lib.recognizeAutomatic(reversedSix,0,0,reversedSix.width,reversedSix.height,fullFaceVisible=true)==null)
 check(lib.recognizeAutomatic(ambiguousFive,-1,0,ambiguousFive.width,ambiguousFive.height,true)==null)
 check(lib.recognizeAutomatic(ambiguousFive,0,0,ambiguousFive.width+1,ambiguousFive.height,true)==null)
 val blankFace=Bitmap(87,126,IntArray(87*126){0xFFFFFF})
 check(lib.recognizeAutomatic(blankFace,0,0,87,126,true)==null)
 check(lib.recognizeAutomatic(stockBlue,0,0,stockBlue.width,stockBlue.height,true)==null)
 pass("lower-rank corroboration requires an explicitly complete face and rejects conflicts, rotated6/9, blanks and invalid bounds")
 var scaledMatches=0
 for (code in listOf("5C","6H")) {
  val original=ImageIO.read(File(root,"card-heldout-$code.png"))
  for(method in listOf(java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)) {
   val scaled=java.awt.image.BufferedImage(131,190,java.awt.image.BufferedImage.TYPE_INT_RGB)
   val drawing=scaled.createGraphics()
   drawing.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,method)
   drawing.drawImage(original,0,0,131,190,null);drawing.dispose()
   val image=Bitmap(131,190,scaled.getRGB(0,0,131,190,null,0,131))
   val scaledMatch=lib.recognizeAutomatic(image,0,0,131,190,true)
   println("SCALED heldout$code method=$method actual=$scaledMatch")
   check(scaledMatch==null || scaledMatch.first.code==code)
   if(scaledMatch!=null)scaledMatches++
  }
 }
 check(scaledMatches>=3)
 println("SCALED full-face heldouts accepted=$scaledMatches/4 unknown=${4-scaledMatches}; resampling is not independent capture evidence")
 pass("clear rescaled lower ranks resolve ambiguity; uncertain resamples remain unknown without new teaching")
 // An equal-sized buffer must be overwritten for every fresh frame, even
 // following an exception. Byte reuse never reuses an old frame's evidence.
 val a=Bitmap(20,20,IntArray(400){0x123456});val b=Bitmap(20,20,IntArray(400){0x234567})
 val box=PixelBox(2,2,18,18)
 check(FramePixels.withFrame(a,box){FramePixels.get(a,5,5)}==0x123456)
 check(FramePixels.withFrame(b,box){FramePixels.get(b,5,5)}==0x234567)
 try { FramePixels.withFrame(a,box){error("cancelled observation")} } catch(_:IllegalStateException) {}
 check(FramePixels.withFrame(b,box){FramePixels.get(b,5,5)}==0x234567)
 pass("bulk frame evidence is fresh and released after cancellation")
 val initial=pixels(File(args[1],"initial.png"));val layout=AutoBoardSetup.detect(initial,lib).layout!!
 val reader=CardVision(lib)
 val first=reader.inspect(initial,layout,null,true,true,1000,1,42,1,automatic=true,automaticSnapshot=initial)
 println("Synthetic covered-back evidence=${first.coveredTableauBacks}; counts=${first.board.columns.map{it.hiddenCount}}")
 check(0 !in first.coveredTableauBacks && first.coveredTableauBacks.isNotEmpty())
 check(first.coveredTableauBacks.all{first.board.columns[it].hiddenCount==null})
 val regionReader=AutomaticRegionReader(lib);check(regionReader.seed(initial,layout))
 val column=first.coveredTableauBacks.first();val region=layout.tableauColumns[column].toPixels(initial.width,initial.height)
 val faceY=first.positions.getValue("T$column:0").y
 check(regionReader.coveredBackConfidence(initial,region,faceY)!=null)
 val pale=initial.copyPixels();pale.rectangle(region.left,region.top,region.right-region.left,faceY-region.top,0xFFFFFF)
 check(regionReader.coveredBackConfidence(pale,region,faceY)==null)
 val blank=initial.copyPixels();blank.rectangle(region.left,region.top,region.right-region.left,faceY-region.top,initial.getPixel(region.left-3,region.top))
 check(regionReader.coveredBackConfidence(blank,region,faceY)==null)
 check(AutomaticRegionReader(lib).coveredBackConfidence(initial,region,faceY)==null)
 check(regionReader.coveredBackConfidence(initial,region,region.top)==null)
 pass("covered-back priority requires learned pattern evidence, never pale face strips, blank gaps or invented counts")
 if (args.size > 2) {
  fun region(l:Int,t:Int,r:Int,b:Int)=NormalizedBox(l/716f,t/1536f,r/716f,b/1536f)
  val xs=listOf(26,122,217,312,408,503,599)
  val photoLayout=NormalizedBoardLayout(region(599,490,686,616),region(441,490,591,616),
   listOf(26,122,217,312).map{region(it,490,it+87,616)},xs.map{region(it,649,it+87,861)})
  val screenshot=pixels(File(args[2],"137242.jpg"))
  val observed=CardVision(lib).inspect(screenshot,photoLayout,null,true,true,1000,1,78,1,
   automatic=true,automaticSnapshot=screenshot)
  check(observed.board.columns.map{it.top?.code}==listOf("2S","6D","QD","KC","4S","QS","5C"))
  println("USER preliminary covered=${observed.coveredTableauBacks} positions=${observed.positions.filterKeys{it.startsWith("T")}.mapValues{it.value.y}}")
  check(2 in observed.coveredTableauBacks && observed.board.columns[2].hiddenCount==null)
  val choices=SolitaireSolver.candidates(observed.board,observedCoveredBacks=observed.coveredTableauBacks)
  val queen=choices.firstOrNull{val move=it.move; move is Move.TableauToTableau && move.from==2 && move.to==3}
  check(queen!=null){"Clearly visible QD -> KC reveal was filtered out"}
  val draw=choices.firstOrNull{it.move==Move.DrawThree || it.move==Move.DrawOne}
  check(draw==null || queen.score>draw.score){"Observed reveal must outrank stock draw"}
  println("USER screenshot137242 covered=${observed.coveredTableauBacks}; QD->KC score=${queen.score}; stock=${draw?.score}; hidden counts=${observed.board.columns.map{it.hiddenCount}}")
  pass("actual screenshot pixels make QD-to-KC reveal eligible ahead of stock without guessing hidden counts")
 }
 println("ALL $passed vision speed/evidence regression groups passed; desktop RGB harness, not an Android device round")
}
