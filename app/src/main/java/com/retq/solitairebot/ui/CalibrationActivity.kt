package com.retq.solitairebot.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.retq.solitairebot.capture.BattleArenaPortraitProfile
import com.retq.solitairebot.capture.NormalizedBoardLayout
import com.retq.solitairebot.capture.NormalizedBox
import com.retq.solitairebot.storage.AppStore

/**
 * Calibrates the complete game viewport first, then the 13 card/search regions.
 * All geometry is stored in full-frame normalized coordinates, so it scales with
 * different pixel resolutions while retaining the actual game-area offset.
 */
class CalibrationActivity : AsyncActivity() {
    private val store by lazy { AppStore(this) }
    private val names = listOf("حدود شاشة اللعبة كاملة") +
        listOf("الأساس ♣", "الأساس ♠", "الأساس ♥", "الأساس ♦",
            "ورق السحب المكشوف Waste", "المخزون Stock") +
        (1..7).map { "منطقة العمود $it" }

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
        root.addView(UiKit.text(this, "ضبط مقاسات الشاشة", true))
        root.addView(UiKit.text(this,
            "أولاً حدد المستطيل الذي يحتوي شاشة لعبة Solitaire Battle Arena كاملة، من الزاوية العلوية اليسرى إلى السفلية اليمنى. يمكنك استبعاد الحواف السوداء أو أشرطة النظام إذا لم تكن جزءًا من مساحة اللعبة. بعد ذلك راجع مناطق الأساسات والسحب والأعمدة واضبطها عند الحاجة."))
        caption = UiKit.text(this, "جار تحميل لقطة اللعبة…")
        root.addView(caption)
        UiKit.show(this, root)

        background(work = { store.loadSnapshotWithIdentity() },
            discard = { it?.bitmap?.recycle() },
            onError = { caption.text = "تعذر تحميل اللقطة: ${it.message}" }) { data ->
            if (data == null) {
                caption.text = "احفظ لقطة جديدة من شاشة اللعبة أثناء مشاركة الشاشة ثم افتح ضبط المقاسات."
                return@background
            }

            snapshotId = data.identity.id
            ownBitmap(data.bitmap)

            val draft = restoredState?.takeIf { it.getString("snapshot") == snapshotId }
                ?.getFloatArray("boxes")
            boxes = if (draft != null && draft.size == 56) {
                try {
                    (0 until 14).map { i ->
                        NormalizedBox(draft[i * 4], draft[i * 4 + 1], draft[i * 4 + 2], draft[i * 4 + 3])
                    }.toMutableList()
                } catch (_: Exception) {
                    initialBoxes(data.bitmap.width, data.bitmap.height)
                }
            } else {
                initialBoxes(data.bitmap.width, data.bitmap.height)
            }

            val canvas = ScreenshotCanvas(this, data.bitmap).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * .58f).toInt()
                )
                boxes = this@CalibrationActivity.boxes.toList()
                selectedIndex = selected
                dragEnabled = true
                onDrag = { box ->
                    if (selected == 0) {
                        val previousBounds = this@CalibrationActivity.boxes[0]
                        val remapped = this@CalibrationActivity.boxes.drop(1)
                            .map { remap(it, previousBounds, box) }
                        this@CalibrationActivity.boxes = (listOf(box) + remapped).toMutableList()
                    } else {
                        this@CalibrationActivity.boxes[selected] = box
                    }
                    boxes = this@CalibrationActivity.boxes.toList()
                }
            }
            view = canvas

            root.addView(canvas)
            root.addView(UiKit.button(this, "المنطقة السابقة") {
                selected = (selected + names.size - 1) % names.size
                showSelected()
            })
            root.addView(UiKit.button(this, "المنطقة التالية") {
                selected = (selected + 1) % names.size
                showSelected()
            })
            root.addView(UiKit.button(this, "حفظ مقاسات الشاشة والمعايرة") {
                try {
                    val screen = boxes[0]
                    val regions = boxes.drop(1)
                    require(regions.all { inside(it, screen) }) {
                        "توجد منطقة خارج حدود شاشة اللعبة. عدّل حدود الشاشة أو المنطقة المحددة."
                    }
                    store.saveLayout(
                        NormalizedBoardLayout(
                            stock = boxes[6],
                            waste = boxes[5],
                            foundations = boxes.subList(1, 5),
                            tableauColumns = boxes.drop(7),
                            screenBounds = screen
                        ),
                        data.bitmap.width,
                        data.bitmap.height,
                        data.identity.id
                    )
                    AlertDialog.Builder(this)
                        .setMessage("تم حفظ حدود شاشة اللعبة ومناطق الأوراق لهذه المشاركة. أبعاد اللقطة: ${data.bitmap.width} × ${data.bitmap.height}.")
                        .setPositiveButton("موافق") { _, _ -> finish() }
                        .show()
                } catch (error: Exception) {
                    caption.text = error.message ?: "تعذر اعتماد المعايرة"
                }
            })
            root.addView(UiKit.button(this, "إعادة المناطق الافتراضية داخل حدود الشاشة") {
                val screen = boxes[0]
                boxes = (listOf(screen) + extract(mapLayout(BattleArenaPortraitProfile.boardLayout, screen)))
                    .toMutableList()
                view?.boxes = boxes.toList()
                showSelected()
            })
            showSelected()
        }
    }

    private fun initialBoxes(width: Int, height: Int): MutableList<NormalizedBox> {
        val sameAspect = store.calibrated && store.matchesReferenceAspect(width, height)
        val saved = if (sameAspect) store.loadLayout() else BattleArenaPortraitProfile.boardLayout
        val screen = saved.screenBounds
        return (listOf(screen) + extract(saved)).toMutableList()
    }

    private fun mapLayout(layout: NormalizedBoardLayout, bounds: NormalizedBox): NormalizedBoardLayout {
        val old = layout.screenBounds
        return NormalizedBoardLayout(
            stock = remap(layout.stock, old, bounds),
            waste = remap(layout.waste, old, bounds),
            foundations = layout.foundations.map { remap(it, old, bounds) },
            tableauColumns = layout.tableauColumns.map { remap(it, old, bounds) },
            screenBounds = bounds
        )
    }

    /** Convert a full-frame normalized region from one game viewport to another. */
    private fun remap(box: NormalizedBox, from: NormalizedBox, to: NormalizedBox): NormalizedBox {
        fun x(value: Float) = to.left + ((value - from.left) / (from.right - from.left)) * (to.right - to.left)
        fun y(value: Float) = to.top + ((value - from.top) / (from.bottom - from.top)) * (to.bottom - to.top)
        val left = x(box.left).coerceIn(to.left, to.right)
        val right = x(box.right).coerceIn(to.left, to.right)
        val top = y(box.top).coerceIn(to.top, to.bottom)
        val bottom = y(box.bottom).coerceIn(to.top, to.bottom)
        return NormalizedBox(left, top, right, bottom)
    }

    private fun inside(box: NormalizedBox, bounds: NormalizedBox): Boolean =
        box.left >= bounds.left && box.top >= bounds.top &&
            box.right <= bounds.right && box.bottom <= bounds.bottom

    private fun showSelected() {
        val extra = if (selected == 0) {
            "\nاسحب لتحديد كامل مساحة اللعبة. ستتحرك المناطق الداخلية تلقائيًا مع حدود الشاشة."
        } else {
            "\nاسحب لتحديد منطقة العنصر. تأكد أن المستطيل يقع داخل حدود شاشة اللعبة."
        }
        val freshness = if (snapshotId?.let {
                store.isSnapshotCurrent(it, AppStore.CALIBRATION_MAX_AGE_MS)
            } == true) "" else "\nاللقطة قديمة أو من مشاركة أخرى؛ يلزم لقطة جديدة للاعتماد."
        caption.text = "المنطقة ${selected + 1}/${names.size}: ${names[selected]}$extra$freshness"
        view?.selectedIndex = selected
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("region", selected)
        outState.putString("snapshot", snapshotId ?: restoredState?.getString("snapshot"))
        if (boxes.size == 14) {
            outState.putFloatArray("boxes", boxes.flatMap {
                listOf(it.left, it.top, it.right, it.bottom)
            }.toFloatArray())
        } else {
            restoredState?.getFloatArray("boxes")?.let { outState.putFloatArray("boxes", it) }
        }
        super.onSaveInstanceState(outState)
    }

    private fun extract(layout: NormalizedBoardLayout) =
        (layout.foundations + listOf(layout.waste, layout.stock) + layout.tableauColumns)
}
