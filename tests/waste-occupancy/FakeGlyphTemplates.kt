package com.retq.solitairebot.vision
import android.graphics.Bitmap
import com.retq.solitairebot.capture.PixelBox
import com.retq.solitairebot.core.Suit
class GlyphTemplates {
    fun stockBusyAutomatic(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Boolean = false

 companion object { const val EMPTY_COLUMN="EMPTY_COLUMN";const val EMPTY_WASTE="EMPTY_WASTE";
 const val EMPTY_STOCK="EMPTY_STOCK";const val EMPTY_FOUNDATION="EMPTY_FOUNDATION";const val STOCK_BACK="STOCK_BACK" }
 data class Match(val label:String,val confidence:Float)
 val currentRevision:Long get()=0
 fun reloadIfChanged(){}
 fun classifyRegion(bitmap:Bitmap,box:PixelBox,labels:Set<String>):Match?=null
 fun recognize(bitmap:Bitmap,x:Int,y:Int,w:Int,h:Int):Pair<com.retq.solitairebot.core.Card,Float>?=null
 fun recognizeAutomatic(bitmap:Bitmap,x:Int,y:Int,w:Int,h:Int,fullFaceVisible:Boolean=false):Pair<com.retq.solitairebot.core.Card,Float>?=null
 fun prefixSimilarity(a:ByteArray,b:ByteArray,visibleFraction:Float):Float=0f
 fun signatureId(bitmap:Bitmap,box:PixelBox):String=""
 fun regionSignature(bitmap:Bitmap,box:PixelBox):ByteArray?=ByteArray(32*32*3)
 fun regionSimilarity(a:ByteArray,b:ByteArray):Float=0f
 fun recognizeSuitAutomatic(bitmap:Bitmap,x:Int,y:Int,w:Int,h:Int):Pair<Suit,Float>?=null
 fun recognizeRecycleAutomatic(bitmap:Bitmap,x:Int,y:Int,w:Int,h:Int):Float?=null
}
