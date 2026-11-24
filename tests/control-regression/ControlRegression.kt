import android.os.*
import android.content.Context
import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.retq.solitairebot.control.*
import com.retq.solitairebot.core.*
import com.retq.solitairebot.storage.*
import com.retq.solitairebot.vision.*

fun board(moved:Boolean=false):BoardState=BoardState(
 listOf(TableauColumn(0,if(moved) emptyList() else listOf(Card(1,Suit.SPADES))),
 TableauColumn(0,listOf(Card(2,Suit.SPADES))))+List(5){TableauColumn(0,emptyList())},
 Suit.entries.associateWith{if(it==Suit.SPADES && moved) 1 else 0},null,StockStatus.UNKNOWN)
fun observation(b:BoardState,time:Long):VisionReadout {
 val positions=mutableMapOf<String,CardPosition>()
 b.columns.forEachIndexed{i,c->c.top?.let{
  val p=CardPosition(i*100+10,700,80,120,it,1f);positions["T$i"]=p;positions["T$i:0"]=p
 }}
 b.foundations.filterValues{it>0}.forEach{(s,r)->positions["F${s.symbol}"]=CardPosition(10,400,80,120,Card(r,s),1f)}
 b.waste?.let { positions["W"]=CardPosition(780,400,80,120,it,1f) }
 val conf=(0..6).associate{"T$it" to 1f}+positions.mapValues{1f}+Suit.entries.associate{"F${it.symbol}" to 1f}+mapOf("W" to 1f,"S" to 1f)
 val proof=ObservationEvidence(observedColumns=b.columns.indices.filter{b.columns[it].top!=null}.toSet(),
  emptyColumns=b.columns.indices.filter{b.columns[it].definitelyEmpty}.toSet(),completeColumns=(0..6).toSet(),
  observedFoundations=b.foundations.filterValues{it>0}.keys,emptyFoundations=b.foundations.filterValues{it==0}.keys,
  wasteObserved=b.waste!=null,wasteEmpty=b.waste==null,regionConfidences=conf,
  observedStock=b.stockStatus!=StockStatus.UNKNOWN,
  exposedTableauBacks=b.columns.indices.filter{b.columns[it].exposedBack}.toSet(),
  recycleAvailable=b.recycleAvailable)
 return VisionReadout(b,positions,positions.size,1f,true,emptyList(),time,1080,2316,
  Suit.entries.toSet(),b.foundations.filterValues{it>0}.keys,
  capturedAtMs=time,frameId=time,sessionId=7,layoutRevision=1,observationEvidence=proof)
}
data class Fixture(val pipeline:LivePipeline,val monitor:CaptureMonitorStore,val service:GestureAccessibilityService,
 var autoRestores:Int=0,var manualStarts:Int=0,var manualFinishes:Int=0,var readFinishes:Int=0)
fun fresh(auto:Boolean=true):Fixture {
 CardVision.analysisDelayMs=0L
 Handler.scheduled.clear();AccessibilityService.deliveries.clear();AppStore.allowed=true;CaptureMonitorStore.activityVisible=false;SystemClock.now=100_000L
 val service=GestureAccessibilityService();service.onServiceConnected();service.onAccessibilityEvent(AccessibilityEvent())
 val context=Context();val monitor=CaptureMonitorStore(context);lateinit var f:Fixture
 val pipeline=LivePipeline(context,monitor,{f.autoRestores++},{f.readFinishes++},
  onManualStarted={f.manualStarts++},onManualFinished={f.manualFinishes++})
 f=Fixture(pipeline,monitor,service);pipeline.beginSession(7)
 if(auto)check(pipeline.startAuto(1080,2316).accepted)
 return f
}
fun emit(f:Fixture,b:BoardState,time:Long,drain:Boolean=true){SystemClock.now=time;f.pipeline.observe(Bitmap(observation(b,time)),time,time,7);if(drain)Handler.runReady()}
fun complete(index:Int=0,time:Long=102_500L){SystemClock.now=time;val d=AccessibilityService.deliveries[index];d.second.onCompleted(d.first)}
fun armSession(b:BoardState,move:Move,time:Long):LiveSession.Action {
 LiveSession.acceptObservation(observation(b,time-500),time-500)
 val o=observation(b,time);LiveSession.acceptObservation(o,time)
 LiveSession.publishSuggestion(Suggestion(move,100,"fixture"))
 return checkNotNull(LiveSession.beginAction(o,move,time))
}
fun main(){
 var checks=0
 fun pass(name:String){checks++;println("PASS $checks: $name")}
 run {
  val f=fresh(auto=false)
  val before = GestureAccessibilityService.foregroundWindowRevision
  f.service.onAccessibilityEvent(AccessibilityEvent(packageName="com.retq.solitairebot", windowId=77))
  check(GestureAccessibilityService.foregroundPackageName == "practice.solitaire")
  check(GestureAccessibilityService.foregroundWindowRevision == before)
  pass("assistant overlay event preserves game foreground and window revision")
  CaptureMonitorStore.activityVisible = true
  f.service.onAccessibilityEvent(AccessibilityEvent(packageName="com.retq.solitairebot", windowId=79))
  check(GestureAccessibilityService.foregroundPackageName == "com.retq.solitairebot")
  check(GestureAccessibilityService.foregroundWindowRevision > before)
  pass("real assistant activity invalidates foreground game when visible")
  CaptureMonitorStore.activityVisible = false
  f.service.onAccessibilityEvent(AccessibilityEvent(packageName="another.app", windowId=85))
  check(GestureAccessibilityService.foregroundPackageName == "another.app")
  pass("unrelated app window takes over foreground and blocks game gestures")
 }
 run {
  val f=fresh();emit(f,board(),101_500);emit(f,board(),102_000);check(AccessibilityService.deliveries.size==1)
  complete();emit(f,board(true),105_000)
  check(f.pipeline.autoPlaying);check(AccessibilityService.deliveries.size==1)
  emit(f,board(true),105_500)
  check(f.pipeline.autoPlaying);check(AccessibilityService.deliveries.size==2)
  // The second fresh, verified observation already proves the board settled.
  // No extra fixed cooldown or heartbeat is needed after confirmation.
  check(LiveSession.currentAction()?.requestedAtMs == 105_500L)
  val failures=LivePipeline::class.java.getDeclaredField("failuresThisRun")
  failures.isAccessible=true
  check(failures.getInt(f.pipeline)==0)
  pass("verified move dispatches next move immediately after two fresh frames and resets failure streak")
 }
 run {
  val f=fresh();emit(f,board(),101_500);emit(f,board(),102_000,false)
  check(Handler.runOne());check(AccessibilityService.deliveries.isEmpty())
  f.pipeline.stopAuto("explicit stop");Handler.runReady();check(AccessibilityService.deliveries.isEmpty())
  pass("stop invalidates queued touch before accessibility dispatch")
 }
 run {
  fresh();val move=Move.TableauToFoundation(0,Card(1,Suit.SPADES));val a=armSession(board(),move,102_000)
  LiveSession.cancelPending("new request");val b=armSession(board(),move,106_000)
  val event=LiveSession.gestureResult(a.id,false,nowMs=106_100)
  check(event.result==LiveSession.Result.IGNORED);check(LiveSession.isCurrent(b.id))
  pass("late callback cannot complete or cancel another action")
 }
 run {
  val f=fresh();emit(f,board(),101_500);emit(f,board(),102_000)
  SystemClock.now=104_500;f.pipeline.tick();check(f.pipeline.autoPlaying);check(!LiveSession.moveInFlight)
  check(AccessibilityService.deliveries.size==1);check(LiveSession.readout==null)
  emit(f,board(),105_300);check(AccessibilityService.deliveries.size==1)
  emit(f,board(),106_100);emit(f,board(),107_500);emit(f,board(),109_200)
  check(f.pipeline.autoPlaying);check(!LiveSession.moveInFlight);check(AccessibilityService.deliveries.size==1)
  pass("missing callback is blacklisted; automatic mode keeps watching without repeating a failed move")
 }
 run {
  val f=fresh();emit(f,board(),101_500);emit(f,board(),102_000);complete()
  SystemClock.now=110_500;f.pipeline.tick();Handler.runReady()
  check(f.pipeline.autoPlaying);check(!LiveSession.moveInFlight);check(!f.monitor.aiStatus.contains("جار التحقق"))
  val failed=LiveSession.currentAction();check(failed==null)
  emit(f,board(),111_300);emit(f,board(),112_100);emit(f,board(),113_500);emit(f,board(),115_200)
  check(f.pipeline.autoPlaying);check(AccessibilityService.deliveries.size==1)
  pass("verification timeout updates status then keeps observing without repeating failed move")
 }
 run {
  val f=fresh();val field=LivePipeline::class.java.getDeclaredField("guard");field.isAccessible=true
  val guard=field.get(f.pipeline) as AutoPlayGuard;val move=Move.TableauToFoundation(0,Card(1,Suit.SPADES))
  check(guard.authorize(board(),move)==null);val reason=checkNotNull(guard.authorize(board(),move))
  val before=f.autoRestores;f.pipeline.stopAuto(reason);check(f.autoRestores==before+1)
  guard.start();check(guard.blocked(board(),move));check(guard.authorize(board(),move)!=null)
  pass("guard stop restores overlay and restart retains the attempted edge")
 }
 run {
  val f=fresh(auto=false);val start=f.pipeline.manualMove();check(start.accepted);check(f.manualStarts==1)
  emit(f,board(),101_500);emit(f,board(),102_000);complete();emit(f,board(true),105_000);emit(f,board(true),105_500)
  check(!f.pipeline.actionActive);check(f.manualFinishes>=2);check(AccessibilityService.deliveries.size==1)
  pass("manual mode reacquires frames, sends exactly one move and restores after verification")
 }
 run {
  val f=fresh();emit(f,board(),101_500);emit(f,board(),102_000,false);check(Handler.runOne())
  SystemClock.now=102_001;f.service.onAccessibilityEvent(AccessibilityEvent(windowId=8))
  Handler.runReady();check(AccessibilityService.deliveries.isEmpty())
  pass("same-package dialog window invalidates queued gesture")
 }
 run {
  val f=fresh(auto=false);check(f.pipeline.readNow().accepted)
  emit(f,board(),101_500);emit(f,board(),102_000)
  check(!f.pipeline.readingManual);check(AccessibilityService.deliveries.isEmpty())
  pass("legacy trained glyphs with zero card-example counter remain usable for read-only mode")
 }
 run {
  val layout=com.retq.solitairebot.capture.NormalizedBoardLayout.PORTRAIT_STARTER
  val base=observation(board().copy(stockStatus=StockStatus.AVAILABLE),102_000)
  val known=base.copy(observationEvidence=base.evidence().copy(observedStock=true,
    wasteObserved=false,wasteEmpty=true,regionConfidences=base.evidence().regionConfidences+mapOf("S" to 1f,"W" to 1f)))
  val unknownWaste=known.copy(observationEvidence=known.evidence().copy(wasteObserved=false,wasteEmpty=false))
  check(!MoveExecutor.canLocate(Move.DrawThree,unknownWaste,layout))
  check(MoveExecutor.canLocate(Move.DrawThree,known,layout))
  val lowWaste=known.copy(observationEvidence=known.evidence().copy(regionConfidences=known.evidence().regionConfidences+("W" to .89f)))
  check(!MoveExecutor.canLocate(Move.DrawThree,lowWaste,layout))
  val recycle=known.copy(board=known.board.copy(stockStatus=StockStatus.EMPTY,waste=Card(3,Suit.HEARTS),redealsRemaining=1),
    observationEvidence=known.evidence().copy(wasteObserved=true,wasteEmpty=false))
  check(MoveExecutor.canLocate(Move.RecycleStock,recycle,layout))
  check(!MoveExecutor.canLocate(Move.RecycleStock,recycle.copy(observationEvidence=recycle.evidence().copy(
    regionConfidences=recycle.evidence().regionConfidences+("W" to .89f))),layout))
  pass("stock actions require positive confident waste evidence; known empty waste permits draw")
 }
 run {
  val layout=com.retq.solitairebot.capture.NormalizedBoardLayout.PORTRAIT_STARTER
  val b=board().copy(columns=listOf(TableauColumn(2,emptyList(),true))+board().columns.drop(1))
  val box=layout.tableauColumns[0]
  val point=(box.left+box.right)*.5f*1080 to (box.top+.04f)*2316
  val base=observation(b,102_000).copy(tableauBackPoints=mapOf(0 to point))
  val flip=Move.FlipTableau(0)
  check(MoveExecutor.canLocate(flip,base,layout))
  check(!MoveExecutor.canLocate(flip,base.copy(tableauBackPoints=emptyMap()),layout))
  check(!MoveExecutor.canLocate(flip,base.copy(tableauBackPoints=mapOf(0 to (Float.NaN to point.second))),layout))
  check(!MoveExecutor.canLocate(flip,base.copy(tableauBackPoints=mapOf(0 to (-1f to point.second))),layout))
  check(!MoveExecutor.canLocate(flip,base.copy(tableauBackPoints=mapOf(0 to (point.first to box.bottom*2316))),layout))
  check(!MoveExecutor.canLocate(flip,base.copy(observationEvidence=base.evidence().copy(exposedTableauBacks=emptySet())),layout))
  check(!MoveExecutor.canLocate(flip,base.copy(observationEvidence=base.evidence().copy(
   regionConfidences=base.evidence().regionConfidences+("T0" to .89f))),layout))
  val unknown=b.copy(columns=listOf(TableauColumn(null,emptyList()))+b.columns.drop(1))
  check(!MoveExecutor.canLocate(flip,base.copy(board=unknown),layout))
  pass("flip tap needs a positive back, confident region and finite point strictly inside that column")
 }
 run {
  fresh(auto=false)
  val b=board().copy(columns=listOf(TableauColumn(2,emptyList(),true))+board().columns.drop(1))
  fun backFrame(time:Long,x:Float)=observation(b,time).copy(tableauBackPoints=mapOf(0 to (x to 800f)))
  SystemClock.now=101_500;LiveSession.acceptObservation(backFrame(101_500,90f))
  SystemClock.now=102_000;LiveSession.acceptObservation(backFrame(102_000,115f))
  check(LiveSession.readout==null) // equal board model, but the visible back is still moving
  SystemClock.now=103_000;val stable=backFrame(103_000,115f);LiveSession.acceptObservation(stable)
  check(LiveSession.readout===stable)
  LiveSession.publishSuggestion(Suggestion(Move.FlipTableau(0),100,"flip fixture"))
  val action=checkNotNull(LiveSession.beginAction(stable,Move.FlipTableau(0)))
  check(LiveSession.canDispatch(action.id))
  SystemClock.now=104_000;LiveSession.acceptObservation(backFrame(104_000,140f))
  check(!LiveSession.canDispatch(action.id))
  pass("moving back points cannot satisfy two-frame stability or authorize a queued stale flip tap")
 }
 run {
  fresh(auto=false)
  val action=armSession(board(),Move.TableauToFoundation(0,Card(1,Suit.SPADES)),102_000)
  SystemClock.now=102_100;check(LiveSession.canDispatch(action.id))
  val next=observation(board(),102_500)
  val shifted=next.copy(positions=next.positions.mapValues{(key,p)->if(key.startsWith("T0"))p.copy(x=p.x+30) else p})
  SystemClock.now=102_500;LiveSession.acceptObservation(shifted)
  check(LiveSession.readout==null);check(!LiveSession.canDispatch(action.id))
  pass("new card coordinates invalidate queued touch even when all recognized card identities are unchanged")
 }
 run {
  val layout=com.retq.solitairebot.capture.NormalizedBoardLayout.PORTRAIT_STARTER
  val three=observation(board().copy(stockStatus=StockStatus.AVAILABLE),102_000)
  check(!MoveExecutor.canLocate(Move.DrawOne,three,layout))
  val one=three.copy(board=three.board.copy(drawCount=1))
  check(MoveExecutor.canLocate(Move.DrawOne,one,layout))
  check(!MoveExecutor.canLocate(Move.DrawThree,one,layout))
  val recycleBoard=one.board.copy(stockStatus=StockStatus.EMPTY,waste=Card(5,Suit.HEARTS),recycleAvailable=true)
  val positive=observation(recycleBoard,102_000)
  check(MoveExecutor.canLocate(Move.RecycleStock,positive,layout))
  check(!MoveExecutor.canLocate(Move.RecycleStock,positive.copy(observationEvidence=
   positive.evidence().copy(recycleAvailable=false)),layout))
  check(!MoveExecutor.canLocate(Move.RecycleStock,positive.copy(board=recycleBoard.copy(recycleAvailable=false)),layout))
  check(!MoveExecutor.canLocate(Move.RecycleStock,positive.copy(board=recycleBoard.copy(redealsRemaining=0)),layout))
  pass("stock uses configured draw variant; unknown redeals require a positively seen recycle control")
 }
 run {
  val f=fresh()
  val aces=board().copy(columns=listOf(Suit.SPADES,Suit.HEARTS,Suit.CLUBS).map{
   TableauColumn(0,listOf(Card(1,it)))}+List(4){TableauColumn(0,emptyList())})
  emit(f,aces,101_500);emit(f,aces,102_000)
  val attempted=mutableSetOf(checkNotNull(LiveSession.currentAction()).move.signature)
  check(AccessibilityService.deliveries.size==1)
  // Physical delivery cancelled: retain the failed edge, discard old frame proposals.
  SystemClock.now=102_500;AccessibilityService.deliveries[0].let{it.second.onCancelled(it.first)}
  check(f.pipeline.autoPlaying);check(!LiveSession.moveInFlight);check(LiveSession.readout==null)
  emit(f,aces,102_900);check(AccessibilityService.deliveries.size==1) // before recovery freshness fence
  emit(f,aces,103_300);check(AccessibilityService.deliveries.size==1) // only first fresh frame
  emit(f,aces,104_100);check(AccessibilityService.deliveries.size==2)
  check(attempted.add(checkNotNull(LiveSession.currentAction()).move.signature))
  SystemClock.now=104_600;AccessibilityService.deliveries[1].let{it.second.onCancelled(it.first)}
  check(f.pipeline.autoPlaying)
  emit(f,aces,105_400);check(AccessibilityService.deliveries.size==2)
  emit(f,aces,106_200);check(AccessibilityService.deliveries.size==3)
  check(attempted.add(checkNotNull(LiveSession.currentAction()).move.signature))
  SystemClock.now=106_700;AccessibilityService.deliveries[2].let{it.second.onCancelled(it.first)}
  check(f.pipeline.autoPlaying);check(!LiveSession.moveInFlight)
  emit(f,aces,108_000);emit(f,aces,109_000);check(AccessibilityService.deliveries.size==3)
  check(f.monitor.aiStatus.contains("المراقبة"))
  val changed=aces.copy(columns=listOf(TableauColumn(0,emptyList()))+aces.columns.drop(1),
      foundations=aces.foundations+(Suit.SPADES to 1))
  emit(f,changed,110_900);check(AccessibilityService.deliveries.size==3)
  emit(f,changed,111_600);check(AccessibilityService.deliveries.size==4)
  check(f.pipeline.autoPlaying)
  pass("three failed touches quarantine an unchanged board; a different stable board resumes safely")
 }
 run {
  val f=fresh(auto=false);check(f.pipeline.manualMove().accepted)
  emit(f,board(),101_500);emit(f,board(),102_000)
  SystemClock.now=102_500;AccessibilityService.deliveries[0].let{it.second.onCancelled(it.first)}
  check(!f.pipeline.actionActive);check(!LiveSession.moveInFlight);check(f.manualFinishes>=2)
  emit(f,board(),104_000);emit(f,board(),105_000);check(AccessibilityService.deliveries.size==1)
  pass("manual one-move mode stops on failure and never enters automatic recovery")
 }
 run {
  val f=fresh();val aces=board().copy(columns=listOf(TableauColumn(0,listOf(Card(1,Suit.SPADES))),
   TableauColumn(0,listOf(Card(1,Suit.HEARTS))))+List(5){TableauColumn(0,emptyList())})
  emit(f,aces,101_500);emit(f,aces,102_000)
  val old=AccessibilityService.deliveries.single()
  SystemClock.now=104_500;f.pipeline.tick();check(f.pipeline.autoPlaying)
  emit(f,aces,105_300);emit(f,aces,106_100)
  val current=checkNotNull(LiveSession.currentAction());check(AccessibilityService.deliveries.size==2)
  SystemClock.now=106_200;old.second.onCompleted(old.first)
  check(LiveSession.currentAction()==current);check(f.pipeline.autoPlaying)
  pass("late callback from timed-out gesture cannot complete the different recovery action")
 }
 run {
  val f=fresh()
  fun idleBoard(rank:Int)=BoardState(
    listOf(TableauColumn(0,listOf(Card(rank,Suit.SPADES))))+List(6){TableauColumn(0,emptyList())},
    Suit.entries.associateWith{0},null,StockStatus.UNKNOWN)
  emit(f,idleBoard(7),101_500);emit(f,idleBoard(7),102_000)
  // Keep observations unstable without exceeding the last-good-frame deadline.
  // This avoids a fake private-field clock setup and keeps the real controller running.
  for(t in 103_000L..129_000L step 1_000L){
    emit(f,idleBoard(if((t/1_000L)%2L==1L)8 else 7),t)
    f.pipeline.tick();check(f.pipeline.autoPlaying)
  }
  emit(f,idleBoard(7),130_000);f.pipeline.tick();check(f.pipeline.autoPlaying)
  emit(f,idleBoard(7),131_000);f.pipeline.tick();check(f.pipeline.autoPlaying)
  emit(f,idleBoard(8),131_701);f.pipeline.tick()
  check(LiveSession.readout==null);check(f.pipeline.autoPlaying)
  SystemClock.now=150_000;f.pipeline.tick();check(f.pipeline.autoPlaying)
  SystemClock.now=161_001;f.pipeline.tick();check(f.pipeline.autoPlaying)
  check(f.monitor.aiStatus.contains("غير مستقرة"))
  pass("an unreadable board pauses touch safely without ending timed monitoring")
 }
 run {
  for(changedCapture in listOf(false,true)){
    val f=fresh();emit(f,board(),101_500);emit(f,board(),102_000,false)
    check(AccessibilityService.deliveries.isEmpty())
    if(changedCapture)f.pipeline.invalidateCapture("capture replaced",8)
    else f.pipeline.stopAuto("stopped before vision publication")
    check(f.pipeline.startAuto(1080,2316).accepted)
    val currentMessage=f.monitor.aiStatus
    Handler.runReady()
    check(LiveSession.readout==null);check(AccessibilityService.deliveries.isEmpty())
    check(f.monitor.aiStatus==currentMessage)
  }
  pass("late vision publication after stop or capture replacement cannot publish or dispatch after restart")
 }
 run {
  // A Draw-3 fan may have a clearly visible top card while the font/OCR is unknown.
  // That is positive OCCUPANCY, not a guessed card identity.
  val base=observation(board().copy(stockStatus=StockStatus.AVAILABLE),102_000)
  val proof=base.evidence().copy(observedStock=true,wasteObserved=false,wasteEmpty=false,
   wasteOccupiedUnidentified=true,wasteSignature="waste-front-A",
   unknownRegions=setOf("W"),regionConfidences=base.evidence().regionConfidences+("W" to .96f))
  val frame=base.copy(observationEvidence=proof)
  val layout=com.retq.solitairebot.capture.NormalizedBoardLayout.PORTRAIT_STARTER
  check(MoveExecutor.canLocate(Move.DrawThree,frame,layout))
  check(!MoveExecutor.canLocate(Move.DrawThree,frame.copy(observationEvidence=proof.copy(
   wasteOccupiedUnidentified=false)),layout))
  val after=frame.copy(observationEvidence=proof.copy(wasteSignature="waste-front-B"))
  check(VisualSafety.verifiesMove(frame.board,after.board,Move.DrawThree,after.evidence(),frame.evidence()))
  check(!VisualSafety.verifiesMove(frame.board,frame.board,Move.DrawThree,frame.evidence(),frame.evidence()))
  val guard=AutoPlayGuard();guard.start()
  check(guard.authorize(frame.board,Move.DrawThree,visibleWaste="waste-front-A")==null)
  check(guard.blocked(frame.board,Move.DrawThree,"waste-front-A"))
  check(!guard.blocked(after.board,Move.DrawThree,"waste-front-B"))
  pass("unidentified visible waste allows a verified draw, never a blind or identical-image draw")
 }
 run {
  val f=fresh()
  val stuck=board().copy(columns=listOf(TableauColumn(0,listOf(Card(7,Suit.SPADES))))+
   List(6){TableauColumn(0,emptyList())},stockStatus=StockStatus.UNKNOWN)
  emit(f,stuck,101_500);emit(f,stuck,102_000)
  for (t in listOf(103_000L,104_000L,105_000L,106_000L,107_000L,108_000L)) emit(f,stuck,t)
  check(f.pipeline.autoPlaying)
  check(AccessibilityService.deliveries.isEmpty())
  check(f.monitor.aiStatus.contains("المراقبة مستمرة"))
  pass("no current legal move parks monitoring; it does not terminate the timed round")
 }
 run {
  fresh(auto=false)
  val b=board().copy(stockStatus=StockStatus.AVAILABLE)
  val first=observation(b,101_500).copy(observationEvidence=observation(b,100_500).evidence().copy(
   wasteSignature="fan-A"))
  val changed=observation(b,102_000).copy(observationEvidence=first.evidence().copy(wasteSignature="fan-B"))
  SystemClock.now=101_500;LiveSession.acceptObservation(first)
  SystemClock.now=102_000;LiveSession.acceptObservation(changed)
  check(LiveSession.readout==null)
  val same=changed.copy(frameId=102_600,capturedAtMs=102_600,atMs=102_600)
  SystemClock.now=102_600;LiveSession.acceptObservation(same)
  check(LiveSession.readout!=null)
  pass("waste visual fingerprint must stabilize across two frames before any gesture")
 }
 run {
  // Recycling is a positive physical event: the icon + waste occupancy are
  // present, and a fresh frame must show stock restored and waste cleared.
  val b=board().copy(stockStatus=StockStatus.EMPTY,recycleAvailable=true,waste=null)
  val base=observation(b,102_000)
  val proof=base.evidence().copy(observedStock=true, wasteOccupiedUnidentified=true,
   recycleAvailable=true,wasteSignature="waste-has-cards",unknownRegions=setOf("W"),
   regionConfidences=base.evidence().regionConfidences+("W" to .96f))
  val before=base.copy(observationEvidence=proof)
  check(MoveGenerator.generate(b).contains(Move.RecycleStock))
  check(MoveExecutor.canLocate(Move.RecycleStock,before,
   com.retq.solitairebot.capture.NormalizedBoardLayout.PORTRAIT_STARTER))
  val after=before.copy(board=b.copy(stockStatus=StockStatus.AVAILABLE,recycleAvailable=false),
   observationEvidence=proof.copy(wasteOccupiedUnidentified=false,wasteEmpty=true,
    wasteSignature="waste-cleared",unknownRegions=emptySet()))
  check(VisualSafety.verifiesMove(before.board,after.board,Move.RecycleStock,
   after.evidence(),before.evidence()))
  check(!VisualSafety.verifiesMove(before.board,before.board,Move.RecycleStock,
   before.evidence(),before.evidence()))
  check(!MoveExecutor.canLocate(Move.RecycleStock,before.copy(
   observationEvidence=proof.copy(wasteOccupiedUnidentified=false)),
   com.retq.solitairebot.capture.NormalizedBoardLayout.PORTRAIT_STARTER))
  pass("recycle requires confirmed stock arrow and occupied waste, then a verified reset")
 }
 run {
  val f=fresh()
  check(!f.pipeline.wantsFrame(100_100L)) // Start/overlay fence.
  check(f.pipeline.wantsFrame(100_500L))
  emit(f,board(),100_500)
  check(!f.pipeline.wantsFrame(100_520L)) // Do not copy/convert every display frame.
  check(f.pipeline.wantsFrame(100_550L))
  emit(f,board(),100_550)
  val action=checkNotNull(LiveSession.currentAction())
  check(action.gestureDurationMs==ExecutionPacing.INITIAL_DRAG_MS)
  check(!f.pipeline.wantsFrame(100_600L)) // Physical gesture in flight: no OCR allocation.
  complete(time=100_695L)
  check(!f.pipeline.wantsFrame(100_720L)) // In-game animation fence after callback.
  check(f.pipeline.wantsFrame(100_750L))
  emit(f,board(true),100_750L)
  check(AccessibilityService.deliveries.size==1)
  emit(f,board(true),100_800L)
  check(AccessibilityService.deliveries.size==2)
  check(LiveSession.currentAction()?.requestedAtMs==100_800L)
  pass("fast demand-gated pipeline performs next action in 250 ms with both post-gesture observations")
 }
 run {
  fresh(auto=false)
  val action=armSession(board(),Move.TableauToFoundation(0,Card(1,Suit.SPADES)),102_000)
  LiveSession.gestureResult(action.id,true,nowMs=102_145)
  val tooEarly=observation(board(true),102_170)
  check(LiveSession.acceptObservation(tooEarly,102_170).result==LiveSession.Result.IGNORED)
  check(LiveSession.readout==null)
  LiveSession.acceptObservation(observation(board(true),102_210),102_210)
  check(LiveSession.acceptObservation(observation(board(true),102_225),102_225).result==LiveSession.Result.UNCHANGED)
  check(LiveSession.moveInFlight)
  check(LiveSession.acceptObservation(observation(board(true),102_275),102_275).result==LiveSession.Result.CONFIRMED)
  pass("animation fence and minimum independent frame separation remain enforced at high sampling rates")
 }
 run {
  fresh(auto=false)
  val action=armSession(board(),Move.TableauToFoundation(0,Card(1,Suit.SPADES)),102_000)
  LiveSession.gestureResult(action.id,true,nowMs=102_145)
  LiveSession.acceptObservation(observation(board(),102_800),102_800)
  check(LiveSession.acceptObservation(observation(board(),102_850),102_850).result==LiveSession.Result.UNCHANGED)
  LiveSession.acceptObservation(observation(board(),103_300),103_300)
  check(LiveSession.acceptObservation(observation(board(),103_350),103_350).result==LiveSession.Result.FAILED)
  check(!LiveSession.moveInFlight)
  check(LiveSession.pollTimeout(111_000)==null)
  pass("positively unchanged settled board reports no-op early without consuming the eight-second OCR timeout")
 }
 run {
  val pacing=ExecutionPacing()
  val move=Move.TableauToFoundation(0,Card(1,Suit.SPADES))
  check(pacing.durationFor(move)==145L && pacing.durationFor(Move.DrawThree)==42L)
  repeat(20){pacing.failed()}
  check(pacing.durationFor(move)==320L && pacing.durationFor(Move.DrawThree)==82L)
  check(pacing.verificationDelayMs()==155L && pacing.unchangedDeadlineMs()<=2400L)
  repeat(30){pacing.confirmed(150)}
  check(pacing.durationFor(move)==145L && pacing.durationFor(Move.DrawThree)==42L)
  pass("gesture pacing slows within bounded limits after failure and recovers only after verified successes")
 }
 run {
  val f=fresh()
  // First action occurs late in this still-active session.
  emit(f,board(),399_500L);emit(f,board(),399_550L)
  complete(time=399_695)
  // Even without a heartbeat, the accept->dispatch path must enforce round end.
  emit(f,board(true),399_980L);emit(f,board(true),400_030L)
  check(AccessibilityService.deliveries.size==1)
  check(!f.pipeline.autoPlaying)
  pass("fast event-driven dispatch still enforces five-minute session limit before every action")
 }
 run {
  fresh(auto=false)
  val before=observation(board(),102_000)
  val layout=com.retq.solitairebot.capture.NormalizedBoardLayout.PORTRAIT_STARTER
  check(!MoveExecutor.canLocate(Move.TableauToFoundation(0,Card(1,Suit.SPADES)),before.copy(stockBusy=true),layout))
  val action=armSession(board(),Move.TableauToFoundation(0,Card(1,Suit.SPADES)),102_000)
  LiveSession.gestureResult(action.id,true,nowMs=102_145)
  // Loading may last longer than the normal verification timeout. Fresh positive
  // spinner observations must not become failed-edge/no-op evidence.
  for (t in 102_500L..112_500L step 500L) {
   val busy=observation(board(t>=107_000),t).copy(stockBusy=true)
   check(LiveSession.acceptObservation(busy,t).result==LiveSession.Result.UNCHANGED)
   check(LiveSession.readout==null && LiveSession.moveInFlight)
   check(LiveSession.pollTimeout(t)==null)
  }
  check(LiveSession.acceptObservation(observation(board(true),112_550),112_550).result==LiveSession.Result.UNCHANGED)
  check(LiveSession.moveInFlight)
  check(LiveSession.acceptObservation(observation(board(true),112_600),112_600).result==LiveSession.Result.CONFIRMED)
  pass("loading spinner forbids all touches, pauses no-op timer, and needs two unbusy frames to confirm")
 }
 run {
  val f=fresh()
  emit(f,board(),100_500,false)
  check(Handler.scheduled.size==1)
  check(!f.pipeline.wantsFrame(100_700))
  emit(f,board(),100_700,false)
  check(Handler.scheduled.size==1 && LiveSession.readout==null)
  // Cancel/restart does not clear the old worker's lease while it still owns a
  // publication. It releases safely even though its epoch is now stale.
  f.pipeline.stopAuto("lease test")
  check(f.pipeline.startAuto(1080,2316).accepted)
  check(!f.pipeline.wantsFrame(101_000))
  Handler.runReady()
  check(LiveSession.readout==null && AccessibilityService.deliveries.isEmpty())
  check(f.pipeline.wantsFrame(101_000))
  emit(f,board(),101_000)
  check(AccessibilityService.deliveries.isEmpty())
  emit(f,board(),101_050)
  check(AccessibilityService.deliveries.size==1)
  pass("one outstanding immutable readout applies backpressure and stale epoch always releases its lease")
 }
 run {
  fresh(auto=false)
  val action=armSession(board(),Move.TableauToFoundation(0,Card(1,Suit.SPADES)),102_000)
  LiveSession.gestureResult(action.id,true,nowMs=102_145)
  for (t in 102_500L..131_500L step 500L) {
   LiveSession.acceptObservation(observation(board(),t).copy(stockBusy=true),t)
   check(LiveSession.pollTimeout(t)==null)
  }
  check(LiveSession.pollTimeout(132_145L)?.result==LiveSession.Result.EXPIRED)
  check(!LiveSession.moveInFlight)
  LiveSession.acceptObservation(observation(board(),132_200).copy(stockBusy=true),132_200)
  check(LiveSession.readout==null)
  pass("positive loading extension has a 30-second hard bound and still cannot authorize a touch")
 }
 run {
  val f=fresh()
  CardVision.analysisDelayMs=2_000L
  emit(f,board(),100_500L)
  check(SystemClock.now==102_500L)
  check(LiveSession.readout==null && AccessibilityService.deliveries.isEmpty())
  check(f.pipeline.autoPlaying && f.monitor.aiStatus.contains("متأخرة"))
  check(RoundDiagnostics.report().contains("STALE_FRAME"))
  CardVision.analysisDelayMs=0L
  emit(f,board(),102_600L)
  check(AccessibilityService.deliveries.isEmpty()) // The stale frame cannot supply the first proof.
  emit(f,board(),102_650L)
  check(AccessibilityService.deliveries.size==1)
  pass("two-second OCR result is diagnosed and rejected; only two new timely observations resume actions")
 }
 run {
  fresh(auto=false)
  val resetBoard=board().copy(stockStatus=StockStatus.EMPTY,recycleAvailable=true,waste=Card(5,Suit.HEARTS))
  fun resetFrame(at:Long,signature:String):VisionReadout {
   val base=observation(resetBoard,at)
   return base.copy(observationEvidence=base.evidence().copy(stockSignature=signature,wasteSignature="same-5H"))
  }
  val a=resetFrame(100_500,"arrow-bright")
  val b=resetFrame(100_550,"arrow-dim")
  LiveSession.acceptObservation(a,100_500)
  LiveSession.acceptObservation(b,100_550)
  check(LiveSession.readout===b)
  check(MoveExecutor.canLocate(Move.RecycleStock,b,com.retq.solitairebot.capture.NormalizedBoardLayout.PORTRAIT_STARTER))
  fun failsToStabilize(first:VisionReadout,second:VisionReadout) {
   LiveSession.beginSession(7)
   LiveSession.acceptObservation(first,first.capturedAtMs)
   LiveSession.acceptObservation(second,second.capturedAtMs)
   check(LiveSession.readout==null)
  }
  failsToStabilize(a,b.copy(observationEvidence=b.evidence().copy(regionConfidences=b.evidence().regionConfidences+("S" to .89f))))
  failsToStabilize(a.copy(observationEvidence=a.evidence().copy(unknownRegions=setOf("S"))),
   b.copy(observationEvidence=b.evidence().copy(unknownRegions=setOf("S"))))
  failsToStabilize(a.copy(board=resetBoard.copy(stockStatus=StockStatus.UNKNOWN)),
   b.copy(board=resetBoard.copy(stockStatus=StockStatus.UNKNOWN)))
  failsToStabilize(a.copy(board=resetBoard.copy(stockStatus=StockStatus.AVAILABLE,recycleAvailable=false)),
   b.copy(board=resetBoard.copy(stockStatus=StockStatus.AVAILABLE,recycleAvailable=false)))
  failsToStabilize(a.copy(stockBusy=true),b.copy(stockBusy=true))
  failsToStabilize(a,b.copy(observationEvidence=b.evidence().copy(wasteSignature="different-fan")))
  pass("only a positively recognized pulsing reset arrow may waive stock RGB equality; other evidence remains strict")
 }
 run {
  val f=fresh()
  val noCount=BoardState(listOf(TableauColumn(null,listOf(Card(7,Suit.CLUBS))),
   TableauColumn(0,listOf(Card(8,Suit.HEARTS))))+List(5){TableauColumn(0,emptyList())},
   Suit.entries.associateWith{0},null,StockStatus.UNKNOWN)
  check(SolitaireSolver.candidates(noCount).isEmpty())
  emit(f,noCount,100_500L);emit(f,noCount,100_550L)
  check(AccessibilityService.deliveries.isEmpty())
  // Identity/count data is unchanged. Newly positive visible back evidence must
  // invalidate the cached ranking and unlock the real uncovering opportunity.
  SystemClock.now=100_600L
  val read=observation(noCount,100_600L).copy(coveredTableauBacks=setOf(0))
  f.pipeline.observe(Bitmap(read),100_600L,100_600L,7);Handler.runReady()
  check(AccessibilityService.deliveries.size==1)
  val action=checkNotNull(LiveSession.currentAction())
  check(action.move==Move.TableauToTableau(from=0,startIndex=0,to=1,cards=listOf(Card(7,Suit.CLUBS))))
  check(action.before.board.columns[0].hiddenCount==null)
  pass("positive covered-back evidence updates cached strategy without inventing a hidden card count")
 }
 run {
  fresh(auto=false)
  val move=Move.TableauToFoundation(0,Card(1,Suit.SPADES))
  LiveSession.acceptObservation(observation(board(),100_500),100_500)
  val onlyFresh=observation(board(),102_000)
  LiveSession.acceptObservation(onlyFresh,102_000)
  check(LiveSession.readout==null)
  LiveSession.publishSuggestion(Suggestion(move,100,"stale pair"))
  check(LiveSession.beginAction(onlyFresh,move,102_000)==null)
  val pair=observation(board(),102_050)
  LiveSession.acceptObservation(pair,102_050)
  LiveSession.publishSuggestion(Suggestion(move,100,"two current observations"))
  val action=checkNotNull(LiveSession.beginAction(pair,move,102_050))
  LiveSession.gestureResult(action.id,true,nowMs=102_200)
  LiveSession.acceptObservation(observation(board(true),103_000),103_000)
  check(LiveSession.acceptObservation(observation(board(true),104_100),104_100).result==LiveSession.Result.UNCHANGED)
  check(LiveSession.readout==null && LiveSession.moveInFlight)
  check(LiveSession.acceptObservation(observation(board(true),104_150),104_150).result==LiveSession.Result.CONFIRMED)
  pass("both matching observations must be current for dispatch and post-action confirmation")
 }
 println("ALL $checks production-control regression scenarios passed")
}
