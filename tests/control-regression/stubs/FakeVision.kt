package com.retq.solitairebot.vision
import android.content.Context
import android.graphics.Bitmap
import com.retq.solitairebot.capture.NormalizedBoardLayout
import com.retq.solitairebot.core.*
data class CardPosition(val x:Int,val y:Int,val width:Int,val height:Int,val card:Card,val confidence:Float,val exposedHeight:Int=height) {
 val touchX:Float get()=x+width*.5f
 val touchY:Float get()=y+minOf(height*.35f,exposedHeight*.5f)
}
data class VisionReadout(val board:BoardState,val positions:Map<String,CardPosition>,val found:Int,
 val confidence:Float,val calibrated:Boolean,val warnings:List<String>,val atMs:Long,
 val frameWidth:Int,val frameHeight:Int,val verifiedFoundations:Set<Suit>,val observedFoundations:Set<Suit>,
 val capturedAtMs:Long=atMs,val frameId:Long=capturedAtMs,val sessionId:Long=0,val layoutRevision:Long=0,
 val observationEvidence:ObservationEvidence=ObservationEvidence(),
 val tableauBackPoints:Map<Int,Pair<Float,Float>> = emptyMap(),
 val analysisDurationMs:Long = 0L, val hudScore:Int?=null, val hudSeconds:Int?=null, val stockBusy:Boolean=false,
 val coveredTableauBacks:Set<Int> = emptySet()) {
 fun summary()="fixture"
 fun evidence()=observationEvidence
 val unknownRegions get()=observationEvidence.unknownRegions
}
class GlyphTemplates(context:Context) {
 val numberOfSamples=0 // Legacy0.8.2 prefs may have valid glyphs but no card-event counter.
 val glyphPatternCount=6
 val ranksLearned=3
 val suitsLearned=2
 val automaticRecognitionReady=true
 fun reloadIfChanged(){}
}
class CardVision(glyphs:GlyphTemplates) {
 companion object { var analysisDelayMs=0L }
 fun reset(sessionId:Long=0,layoutRevision:Long=0){}
 fun confirmedMove(move:Move){}
 fun unconfirmedMove(move:Move){}
 fun needsManualReference(board:BoardState)=false
 fun invalidateManualReference(){}
 fun needsAutomaticReference()=false
 fun inspect(bitmap:Bitmap,layout:NormalizedBoardLayout,manual:BoardState?,calibrated:Boolean,
   referenceAspectMatches:Boolean,capturedAtMs:Long=0,frameId:Long=capturedAtMs,
   sessionId:Long=0,layoutRevision:Long=0,manualSnapshot:Bitmap?=null,
   automatic:Boolean=false,drawCount:Int=3,automaticSnapshot:Bitmap?=null):VisionReadout {
     android.os.SystemClock.now += analysisDelayMs
     return bitmap.observation.copy(capturedAtMs=capturedAtMs,frameId=frameId,sessionId=sessionId,layoutRevision=layoutRevision)
 }
}

class GreenHudReader(assets: Any) {
 data class Reading(val score:Int?=null, val remainingSeconds:Int?=null)
 fun read(bitmap:Bitmap) = Reading()
}
