package com.retq.solitairebot.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.retq.solitairebot.capture.BattleArenaPortraitProfile
import com.retq.solitairebot.capture.NormalizedBoardLayout
import com.retq.solitairebot.capture.NormalizedBox
import com.retq.solitairebot.storage.AppStore

class CalibrationActivity : AsyncActivity() {
    private val store by lazy { AppStore(this) }
    private val names = listOf("الأساس ♣", "الأساس ♠", "الأساس ♥", "الأساس ♦",
        "ورق السحب المكشوف Waste", "المخزون Stock") + (1..7).map { "منطقة العمود $it" }
    private var view: ScreenshotCanvas? = null
    private lateinit var caption: TextView
    private var boxes = mutableListOf<NormalizedBox>()
    private var selected = 0
    private var snapshotId: String? = null
    private var restoredState: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredState = savedInstanceState
        selected = (savedInstanceState?.getInt("region", 0) ?: 0).coerceIn(0, names.lastIndex)
        val root = UiKit.column(this)
        root.addView(UiKit.text(this, "معايرة لوحة اللعبة", true))
        root.addView(UiKit.text(this, "حدد المستطيل من الزاوية العلوية اليسرى إلى السفلية اليمنى. الأعمدة مناطق بحث كاملة، والأساسات بحجم ورقة واحدة. استخدم لقطة حديثة من المشاركة الحالية دون تعليمات تغطي الأوراق."))
        caption = UiKit.text(this, "جار تحميل لقطة اللعبة…")
        root.addView(caption)
        UiKit.show(this, root)
        background(work = { store.loadSnapshotWithIdentity() },
            discard = { it?.bitmap?.recycle() }, onError = { caption.text = "تعذر تحميل اللقطة: ${it.message}" }) { data ->
            if (data == null) { caption.text = "احفظ لقطة جديدة من اللعبة أثناء المشاركة الحالية ثم افتح المعايرة."; return@background }
            snapshotId = data.identity.id; ownBitmap(data.bitmap)
            val draft = restoredState?.takeIf { it.getString("snapshot") == snapshotId }?.getFloatArray("boxes")
            boxes = if (draft != null && draft.size == 52) try {
                (0 until 13).map { i -> NormalizedBox(draft[i*4], draft[i*4+1], draft[i*4+2], draft[i*4+3]) }.toMutableList()
            } catch (_: Exception) { extract(store.loadLayout()) } else extract(store.loadLayout())
            val canvas = ScreenshotCanvas(this, data.bitmap).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * .58f).toInt())
                boxes = this@CalibrationActivity.boxes.toList(); selectedIndex = selected; dragEnabled = true
                onDrag = { box ->
                    this@CalibrationActivity.boxes[selected] = box
                    boxes = this@CalibrationActivity.boxes.toList()
                }
            }
            view = canvas
            root.addView(canvas)
            root.addView(UiKit.button(this,"المنطقة السابقة") { selected = (selected + names.size - 1) % names.size; showSelected() })
            root.addView(UiKit.button(this,"المنطقة التالية") { selected = (selected + 1) % names.size; showSelected() })
            root.addView(UiKit.button(this,"حفظ المعايرة من هذه اللقطة") {
                try {
                    store.saveLayout(NormalizedBoardLayout(boxes[5], boxes[4], boxes.take(4), boxes.drop(6)),
                        data.bitmap.width, data.bitmap.height, data.identity.id)
                    AlertDialog.Builder(this).setMessage("حُفظت المعايرة للمشاركة الحالية. " +
                        if (data.identity.targetPackage == null) "فعّل إمكانية الوصول واحفظ لقطة أخرى لتحديد تطبيق اللعبة قبل التحريك." else "راجع التعرف داخل اللعبة.")
                        .setPositiveButton("موافق") { _, _ -> finish() }.show()
                } catch (error: Exception) {
                    caption.text = error.message ?: "تعذر اعتماد المعايرة"
                }
            })
            root.addView(UiKit.button(this,"إعادة المناطق الافتراضية دون حفظ") {
                boxes = extract(BattleArenaPortraitProfile.boardLayout); view?.boxes = boxes.toList()
            })
            showSelected()
        }
    }
    private fun showSelected() {
        caption.text = "المنطقة ${selected+1}/13: ${names[selected]}" +
            if (snapshotId?.let { store.isSnapshotCurrent(it, AppStore.CALIBRATION_MAX_AGE_MS) } == true) "" else "\nاللقطة قديمة أو من مشاركة أخرى؛ يلزم لقطة جديدة للاعتماد."
        view?.selectedIndex = selected
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("region",selected);outState.putString("snapshot",snapshotId ?: restoredState?.getString("snapshot"))
        if (boxes.size == 13) outState.putFloatArray("boxes",boxes.flatMap { listOf(it.left,it.top,it.right,it.bottom) }.toFloatArray())
        else restoredState?.getFloatArray("boxes")?.let { outState.putFloatArray("boxes",it) }
        super.onSaveInstanceState(outState)
    }
    private fun extract(layout: NormalizedBoardLayout) =
        (layout.foundations + listOf(layout.waste,layout.stock) + layout.tableauColumns).toMutableList()
}
