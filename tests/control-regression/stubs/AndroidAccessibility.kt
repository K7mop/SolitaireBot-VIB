package android.accessibilityservice
import android.graphics.Path
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
open class AccessibilityService: android.content.Context() {
    open fun onServiceConnected(){}
    open fun onAccessibilityEvent(event: AccessibilityEvent?){}
    open fun onInterrupt(){}
    open fun onDestroy(){}
    open class GestureResultCallback {
        open fun onCompleted(gestureDescription: GestureDescription?){}
        open fun onCancelled(gestureDescription: GestureDescription?){}
    }
    fun dispatchGesture(description:GestureDescription, callback:GestureResultCallback, handler:Handler):Boolean {
        deliveries.add(description to callback); return true
    }
    companion object { val deliveries=mutableListOf<Pair<GestureDescription, GestureResultCallback>>() }
}
class GestureDescription(val strokes:List<StrokeDescription>) {
    class StrokeDescription(val path:Path,val start:Long,val duration:Long)
    class Builder {
        private val strokes=mutableListOf<StrokeDescription>()
        fun addStroke(stroke:StrokeDescription)=apply{strokes.add(stroke)}
        fun build()=GestureDescription(strokes.toList())
    }
}
