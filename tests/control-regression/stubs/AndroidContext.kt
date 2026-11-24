package android.content
open class Context {
    val assets: Any = Any()
    val packageName = "com.retq.solitairebot"
    fun getSystemService(name: String): Any = android.view.WindowManager()
    companion object { const val WINDOW_SERVICE = "window" }
}
