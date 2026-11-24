package com.retq.solitairebot.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.retq.solitairebot.core.BoardState
import com.retq.solitairebot.core.BoardText
import com.retq.solitairebot.core.Card
import com.retq.solitairebot.core.SolitaireSolver
import com.retq.solitairebot.core.StockStatus
import com.retq.solitairebot.core.Suit
import com.retq.solitairebot.storage.AppStore
import com.retq.solitairebot.vision.CardVision
import com.retq.solitairebot.vision.GlyphTemplates

/** Human review is tied to the exact image displayed, not merely to a recent wall-clock timestamp. */
class BoardEditorActivity : AsyncActivity() {
    private val store by lazy { AppStore(this) }
    private lateinit var columnInputs: List<EditText>
    private lateinit var foundations: EditText
    private lateinit var waste: EditText
    private lateinit var stock: EditText
    private lateinit var redeals: EditText
    private lateinit var output: TextView
    private var snapshot: AppStore.SnapshotData? = null
    private var restoredSnapshotId: String? = null
    private var approvalReady = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredSnapshotId=savedInstanceState?.getString("snapshot")
        val root=UiKit.column(this)
        root.addView(UiKit.text(this,"حالة اللوحة من الصورة",true))
        root.addView(UiKit.text(this,"اكتب للعمود: عدد المخفية;الأوراق من أعلى لأسفل، مثل 3;8H 7S 6D. المجهول ?;AS والفارغ المؤكد 0;. لا تعتمد أعدادًا لا تظهر في الصورة."))
        val imageContainer=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val imageStatus=UiKit.text(this,"جار تحميل اللقطة التي سترتبط بهذه المراجعة…")
        imageContainer.addView(imageStatus);root.addView(imageContainer)
        val automatic=store.automaticObservation()
        approvalReady = automatic == null
        val current=automatic?.board?:store.loadBoard()?:BoardState.empty()
        val drafts=savedInstanceState?.getStringArrayList("columns")
        columnInputs=(0..6).map {i->
            root.addView(UiKit.text(this,"العمود ${i+1}"))
            EditText(this).apply {
                id=0x534F200+i;setSingleLine(true);textDirection=View.TEXT_DIRECTION_LTR
                hint="T${i+1}: ?;AS";setText(drafts?.getOrNull(i)?:BoardText.formatColumn(current.columns[i]))
            }.also {root.addView(it)}
        }
        fun field(title:String,key:String,initial:String,idValue:Int):EditText {
            root.addView(UiKit.text(this,title))
            return EditText(this).apply {
                id=idValue;setSingleLine(true);textDirection=View.TEXT_DIRECTION_LTR
                setText(savedInstanceState?.getString(key)?:initial)
            }.also {root.addView(it)}
        }
        val foundationText = if (automatic == null) BoardText.formatFoundations(current.foundations)
            else Suit.entries.joinToString(",") { suit -> "${suit.symbol}=" +
                if ("F${suit.symbol}" in automatic.unknownRegions) "?" else current.foundations.getValue(suit).toString() }
        foundations=field("أساس كل نوع: ؟ مجهول، 0 فارغ، 1 A، 11 J، 12 Q، 13 K","foundations",foundationText,0x534F210)
        waste=field("أعلى ورقة متاحة في السحب؛ اترك المجهول فارغًا","waste",current.waste?.code?:"",0x534F211)
        stock=field("المخزون: AVAILABLE أو EMPTY أو UNKNOWN","stock",current.stockStatus.name,0x534F212)
        redeals=field("مرات إعادة التدوير المتبقية: فارغ إذا مجهولة","redeals",current.redealsRemaining?.toString()?:"",0x534F213)
        output=UiKit.text(this,savedInstanceState?.getString("output")?:automatic?.let { "مُلئت الخانات من آخر قراءة تلقائية. المجهول يبقى ؟. هذه معاينة وليست إذنًا لتحريك صورة قديمة.\n${it.summary}" }?:"القيم المحفوظة سابقًا مسودة للمراجعة، وليست دليلًا على اللوحة الحالية.")
        root.addView(output)
        root.addView(UiKit.button(this,"اعتماد هذه القيم من الصورة المعروضة") {
            if (!approvalReady) { output.text = "انتظر قراءة الصورة المعروضة أو اضغط ملء الأوراق من هذه الصورة أولًا"; return@button }
            val board=parse()?:return@button
            val data=snapshot
            if(data==null) output.text="يلزم حفظ لقطة حالية للعبة قبل الاعتماد."
            else try {
                store.saveBoard(board,data.identity.id)
                output.text="اعتمدت القيم لهذه اللقطة والجلسة فقط. سيظل التنفيذ محتاجًا إلى قراءة حية مطابقة."
            } catch(error:Exception) {output.text=error.message?:"تعذر اعتماد المراجعة"}
        })
        root.addView(UiKit.button(this,"اقتراحات القواعد لهذه المسودة") {
            val board=parse()?:return@button
            background(work={SolitaireSolver.candidates(board)},onError={output.text=it.message}) {choices->
                output.text=if(choices.isEmpty()) "لا توجد حركة قانونية من المعطيات المدخلة." else
                    choices.take(5).joinToString("\n") {"${it.score}: ${it.reason}"}
            }
        })
        root.addView(UiKit.button(this,"ملء الأوراق المقروءة من هذه الصورة") {
            val data=snapshot
            if(data==null) {output.text="لا توجد صورة موثقة معروضة";return@button}
            output.text="جار قراءة الصورة…"
            background(work={CardVision(GlyphTemplates(this)).inspect(data.bitmap,store.loadLayout(),null,
                store.calibrated,store.matchesReferenceAspect(data.bitmap.width,data.bitmap.height),
                automatic=store.autoSetupReady, drawCount=store.drawModePreference.takeIf { it==1||it==3 }?:3,
                automaticSnapshot=if(store.autoSetupReady && store.calibrationSnapshotId==data.identity.id) data.bitmap else null)},
                onError={output.text=it.message?:"تعذرت القراءة"}) {reading->
                reading.board.columns.forEachIndexed {i,col->columnInputs[i].setText(BoardText.formatColumn(col))}
                foundations.setText(Suit.entries.joinToString(",") { suit -> "${suit.symbol}=" +
                    if ("F${suit.symbol}" in reading.unknownRegions) "?" else reading.board.foundations.getValue(suit).toString() })
                waste.setText(reading.board.waste?.code?:"")
                stock.setText(reading.board.stockStatus.name)
                approvalReady = true
                output.text=reading.summary()+"\nراجع القيم المجهولة، ثم اعتمدها فقط إن كانت مطابقة للصورة."
            }
        })
        UiKit.show(this,root)
        background(work={store.loadSnapshotWithIdentity()},discard={it?.bitmap?.recycle()},onError={imageStatus.text="تعذر تحميل الصورة: ${it.message}"}) {data->
            if(data==null) {imageStatus.text="لا توجد لقطة موثقة؛ احفظ لقطة جديدة من اللعبة.";return@background}
            snapshot=data;ownBitmap(data.bitmap)
            imageStatus.text=if(restoredSnapshotId!=null&&restoredSnapshotId!=data.identity.id)
                "تغيرت الصورة منذ المسودة؛ راجع جميع القيم قبل الاعتماد." else "الصورة المرجعية لهذه المراجعة:"
            imageContainer.addView(ScreenshotCanvas(this,data.bitmap).apply {
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,(resources.displayMetrics.heightPixels*.4f).toInt())
            })
            // A live board after several moves must not be approved against the initial PNG.
            // Populate this snapshot review automatically from the exact displayed pixels.
            if (automatic != null && savedInstanceState == null && store.autoSetupReady &&
                store.calibrationSnapshotId == data.identity.id) {
                output.text = "جار ملء خانات الصورة المعروضة تلقائيًا…"
                background(work = { CardVision(GlyphTemplates(this)).inspect(data.bitmap, store.loadLayout(), null,
                    store.calibrated, store.matchesReferenceAspect(data.bitmap.width, data.bitmap.height),
                    automatic = true, drawCount = store.drawModePreference.takeIf { it == 1 || it == 3 } ?: 3,
                    automaticSnapshot = data.bitmap) }, onError = { output.text = "تعذرت قراءة الصورة: ${it.message}" }) { read ->
                    read.board.columns.forEachIndexed { index, column -> columnInputs[index].setText(BoardText.formatColumn(column)) }
                    foundations.setText(Suit.entries.joinToString(",") { suit -> "${suit.symbol}=" +
                        if ("F${suit.symbol}" in read.unknownRegions) "?" else read.board.foundations.getValue(suit).toString() })
                    waste.setText(read.board.waste?.code ?: "")
                    stock.setText(read.board.stockStatus.name)
                    redeals.setText(read.board.redealsRemaining?.toString() ?: "")
                    approvalReady = true
                    output.text = "مُلئت خانات اللقطة المعروضة تلقائيًا. هذه مراجعة للصورة الأولى؛ القراءة الحية تتجدد أثناء التشغيل.\n" + read.summary()
                }
            }
        }
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("snapshot",snapshot?.identity?.id?:restoredSnapshotId)
        outState.putStringArrayList("columns",ArrayList(columnInputs.map {it.text.toString()}))
        outState.putString("foundations",foundations.text.toString());outState.putString("waste",waste.text.toString())
        outState.putString("stock",stock.text.toString());outState.putString("redeals",redeals.text.toString())
        outState.putString("output",output.text.toString())
        super.onSaveInstanceState(outState)
    }
    private fun parse():BoardState?=try {
        val columns=columnInputs.map {BoardText.parseColumn(it.text.toString())}
        val foundationMap=BoardText.parseFoundations(foundations.text.toString())
        val wasteText=waste.text.toString().trim()
        val wasteCard=if(wasteText.isEmpty()) null else Card.parse(wasteText)?:error("رمز ورقة السحب غير صحيح")
        val status=StockStatus.valueOf(stock.text.toString().trim().uppercase())
        val number=redeals.text.toString().trim()
        BoardState(columns,foundationMap,wasteCard,status,if(number.isEmpty()) null else number.toInt(),
            store.drawModePreference.takeIf { it==1||it==3 }?:3).also {
            require(it.problems().isEmpty()) {it.problems().joinToString("\n")}
        }
    } catch(error:Exception) {
        AlertDialog.Builder(this).setTitle("راجع بيانات اللوحة").setMessage(error.message?:"تأكد من الخانات")
            .setPositiveButton("موافق",null).show();null
    }
}
