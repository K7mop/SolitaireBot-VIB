package android.view.accessibility
class AccessibilityEvent(val eventType:Int=TYPE_WINDOW_STATE_CHANGED,
    val packageName:CharSequence?="practice.solitaire",val windowId:Int=7) {
    companion object { const val TYPE_WINDOW_STATE_CHANGED=32 }
}
