package com.retq.solitairebot.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.retq.solitairebot.core.Card
import com.retq.solitairebot.storage.AppStore
import com.retq.solitairebot.vision.CardVision
import com.retq.solitairebot.vision.GlyphTemplates

class TrainingActivity : AsyncActivity() {
    private val store by lazy { AppStore(this) }
    private val templates by lazy { GlyphTemplates(this) }
    private lateinit var label: TextView
    private lateinit var statistics: TextView
    private var code: EditText? = null
    private var canvas: ScreenshotCanvas? = null
    private var snapshotId: String? = null
    private var selectedRegion = 0
    private var selectedRegionState = 0
    private var initialState: Bundle? = null
    private val actions = mutableListOf<Button>()
    private val regionNames = listOf("أساس ♣", "أساس ♠", "أساس ♥", "أساس ♦", "ورقة السحب Waste", "المخزون Stock") +
        (1..7).map { "العمود $it" }
    private fun statesFor(index: Int): List<Pair<String,String>> = when (index) {
        in 0..3 -> listOf("EMPTY_FOUNDATION" to "خانة أساس فارغة مؤكدة")
        4 -> listOf("EMPTY_WASTE" to "منطقة السحب فارغة مؤكدة")
        5 -> listOf("STOCK_BACK" to "مخزون متاح: ظهر ورقة", "EMPTY_STOCK" to "مخزون فارغ")
        else -> listOf("EMPTY_COLUMN" to "عمود فارغ بالكامل: لا أوراق فيه")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialState = savedInstanceState
        selectedRegion = (savedInstanceState?.getInt("region",0) ?: 0).coerceIn(0,12)
        selectedRegionState = (savedInstanceState?.getInt("region_state",0) ?: 0).coerceAtLeast(0)
        val root = UiKit.column(this)
        root.addView(UiKit.text(this,"تعليم أشكال الأوراق والخانات",true))
        root.addView(UiKit.text(this,"اضغط على الزاوية العلوية اليسرى لورقة مكشوفة، ثم اكتب رمزها مثل AS أو 10H. علّم جميع الرتب والأنواع التي تريد قراءتها؛ الخانات الفارغة وظهر المخزون لها أمثلة مستقلة أدناه."))
        label = UiKit.text(this,"جار تحميل الصورة…");statistics = UiKit.text(this,"")
        root.addView(label);root.addView(statistics)
        UiKit.show(this,root)
        background(work = {
            templates.reloadIfChanged()
            store.loadSnapshotWithIdentity()
        }, discard = { it?.bitmap?.recycle() }, onError = { label.text = "تعذر تحميل الصورة: ${it.message}" }) { data ->
            if (data == null) { label.text = "احفظ لقطة جديدة من شاشة اللعبة، ثم افتح تعليم الأشكال."; return@background }
            snapshotId = data.identity.id;ownBitmap(data.bitmap)
            val matchingDraft = initialState?.takeIf { it.getString("snapshot") == snapshotId }
            val picture = ScreenshotCanvas(this,data.bitmap).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels*.52f).toInt())
                if (matchingDraft?.containsKey("x") == true) crosshair = matchingDraft.getInt("x") to matchingDraft.getInt("y")
                onTap = { x,y -> label.text = "موقع الورقة x=$x و y=$y. اكتب الرمز ثم اضغط تعليم." }
            }
            canvas = picture;root.addView(picture)
            code = EditText(this).apply {
                id = 0x534F101;hint = "AS أو 10H أو QD";setSingleLine(true);textDirection=View.TEXT_DIRECTION_LTR
                setText(matchingDraft?.getString("card_code") ?: "")
            }.also { root.addView(it) }
            fun addAction(text: String, callback: () -> Unit) {
                val b=UiKit.button(this,text,callback);actions += b;root.addView(b)
            }
            addAction("تعليم الورقة عند العلامة") {
                val point=picture.crosshair;val card=Card.parse(code?.text.toString())
                if(point==null) label.text="حدد الزاوية العلوية اليسرى للورقة أولًا"
                else if(card==null) label.text="رمز غير صحيح؛ مثال AS أو 10H"
                else {
                    setBusy(true,"جار استخراج رقم الورقة ونوعها…")
                    background(work={
                        val layout=store.loadLayout()
                        val col=layout.tableauColumns.first().toPixels(data.bitmap.width,data.bitmap.height)
                        val base=layout.foundations.first().toPixels(data.bitmap.width,data.bitmap.height)
                        templates.train(data.bitmap,point.first,point.second,col.right-col.left,base.bottom-base.top,card)
                    },onError={setBusy(false,"تعذر التعليم: ${it.message}")}) { ok ->
                        setBusy(false,if(ok) "تم تعليم ${card.code}. راجع تغطية الرتب والأنواع أدناه." else "لم يمكن استخراج الرمز؛ راجع الزاوية وحدود المعايرة.")
                        updateStatistics()
                    }
                }
            }
            root.addView(UiKit.text(this,"تعليم حالة منطقة كاملة من الصورة: اختر المنطقة والحالة التي تراها بالفعل."))
            val regions=Spinner(this).apply {
                adapter=ArrayAdapter(this@TrainingActivity,android.R.layout.simple_spinner_dropdown_item,regionNames)
            }
            val stateChoice=Spinner(this)
            fun showStateChoices() {
                val entries=statesFor(selectedRegion)
                stateChoice.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,entries.map { it.second })
                selectedRegionState=selectedRegionState.coerceIn(0,entries.lastIndex)
                stateChoice.setSelection(selectedRegionState)
                val layout=store.loadLayout()
                picture.boxes=layout.foundations+listOf(layout.waste,layout.stock)+layout.tableauColumns
                picture.selectedIndex=selectedRegion
            }
            regions.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent:AdapterView<*>?,v:View?,position:Int,id:Long) {
                    if(selectedRegion!=position) selectedRegionState=0
                    selectedRegion=position;showStateChoices()
                }
                override fun onNothingSelected(parent:AdapterView<*>?)=Unit
            }
            stateChoice.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent:AdapterView<*>?,v:View?,position:Int,id:Long) {selectedRegionState=position}
                override fun onNothingSelected(parent:AdapterView<*>?)=Unit
            }
            root.addView(regions);root.addView(stateChoice);regions.setSelection(selectedRegion);showStateChoices()
            addAction("تعليم حالة المنطقة المحددة") {
                val labelCode=statesFor(selectedRegion)[selectedRegionState].first
                val region=selectedRegion
                setBusy(true,"جار تعليم شكل المنطقة…")
                background(work={
                    val layout=store.loadLayout();val boxes=layout.foundations+listOf(layout.waste,layout.stock)+layout.tableauColumns
                    templates.trainRegion(data.bitmap,boxes[region].toPixels(data.bitmap.width,data.bitmap.height),labelCode)
                },onError={setBusy(false,"تعذر تعليم المنطقة: ${it.message}")}) { ok ->
                    setBusy(false,if(ok) "حُفظ شكل المنطقة. لا تُعلّم عمودًا مغطى أو مجهولًا بوصفه فارغًا." else "لم تُقبل المنطقة؛ راجع حدود المعايرة ووضوح الصورة.")
                    updateStatistics()
                }
            }
            addAction("اختبار التعرف على هذه الصورة فقط") {
                setBusy(true,"جار التعرف على الأوراق والخانات…")
                background(work={CardVision(templates).inspect(data.bitmap,store.loadLayout(),
                    null,store.calibrated,store.matchesReferenceAspect(data.bitmap.width,data.bitmap.height))},
                    onError={setBusy(false,"تعذر الاختبار: ${it.message}")}) { result ->
                    setBusy(false,result.summary()+"\n"+result.warnings.take(5).joinToString("؛ "))
                }
            }
            addAction("حذف القوالب المتعلمة") {
                AlertDialog.Builder(this).setTitle("حذف القوالب المحلية").setMessage("حذف قوالب الأوراق والخانات المتعلمة؟")
                    .setPositiveButton("حذف") { _,_->
                        setBusy(true,"جار حذف القوالب…")
                        background(work={templates.clear()},onError={setBusy(false,it.message?:"تعذر الحذف")}) {
                            setBusy(false,"حُذفت القوالب");updateStatistics()
                        }
                    }.setNegativeButton("إلغاء",null).show()
            }
            label.text="حدد ورقة أو منطقة من الصورة. نتائج الاختبار هنا لا تنفذ أي حركة."
            updateStatistics()
        }
    }
    private fun setBusy(value:Boolean,message:String) { actions.forEach { it.isEnabled=!value };label.text=message }
    private fun updateStatistics() { statistics.text=templates.coverageSummary }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("snapshot",snapshotId?:initialState?.getString("snapshot"))
        outState.putString("card_code",code?.text?.toString()?:initialState?.getString("card_code"))
        val point=canvas?.crosshair ?: initialState?.takeIf { it.containsKey("x") }?.let { it.getInt("x") to it.getInt("y") }
        point?.let {outState.putInt("x",it.first);outState.putInt("y",it.second)}
        outState.putInt("region",selectedRegion);outState.putInt("region_state",selectedRegionState)
        super.onSaveInstanceState(outState)
    }
}
