package com.retq.solitairebot.storage
import android.content.Context
import com.retq.solitairebot.capture.NormalizedBoardLayout
import com.retq.solitairebot.core.BoardState
import android.graphics.Bitmap
class AppStore(context:Context) {
    companion object { var allowed=true }
    val manualExecutionEnabled get()=allowed
    val autoSetupReady=false
    val drawModePreference=3
    val activeCaptureSessionId="test-session"
    val calibrated=true
    val calibrationArmedForSession=true
    val calibratedTargetPackage="practice.solitaire"
    val layoutRevision=1L
    val roundId:String?=null
    val manualProofId:String?=null
    fun remainingGameSeconds():Int?=null
    fun matchesReferenceAspect(width:Int,height:Int)=true
    fun loadLayout()=NormalizedBoardLayout.PORTRAIT_STARTER
    fun loadRecentlyVerifiedBoard():BoardState?=null
    fun invalidateManualVerification(){}
    data class SnapshotData(val bitmap:Bitmap)
    data class ManualProof(val board:BoardState,val snapshot:SnapshotData,val layoutRevision:Long)
    fun loadRecentManualProof():ManualProof?=null
    fun loadAutomaticReference():SnapshotData?=null
    fun saveAutomaticObservation(board:BoardState,summary:String,capturedAtMs:Long,expectedSession:String?,expectedLayout:Long,unknownRegions:Set<String> = emptySet()){}
}
class CaptureMonitorStore(context:Context) {
    companion object { var activityVisible = false }
    var captureActive=true
    var monitoringEnabled=true
    var assistantUiVisible: Boolean
        get() = activityVisible
        set(value) { activityVisible = value }
    var autoPlaying=false
    var aiStatus=""
}
