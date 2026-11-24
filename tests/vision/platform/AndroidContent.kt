package android.content
import java.util.concurrent.ConcurrentHashMap
class Context(private val key:String="vision-regression") {
    val applicationContext:Context get()=this
    val assets = android.content.res.AssetManager()
    fun getSharedPreferences(name:String,mode:Int)=stores.computeIfAbsent("$key/$name"){SharedPreferences()}
    companion object {const val MODE_PRIVATE=0;private val stores=ConcurrentHashMap<String,SharedPreferences>()}
}
class SharedPreferences {
    @Volatile private var values:Map<String,Any> = emptyMap()
    fun getString(key:String,default:String?)=values[key] as? String ?: default
    fun getInt(key:String,default:Int)=values[key] as? Int ?: default
    fun getLong(key:String,default:Long)=values[key] as? Long ?: default
    fun edit()=Editor(this)
    @Synchronized fun applyChanges(v:Map<String,Any>){values=values+v}
    class Editor(private val owner:SharedPreferences){
        private val changes=mutableMapOf<String,Any>()
        fun putString(k:String,v:String)=apply{changes[k]=v}
        fun putInt(k:String,v:Int)=apply{changes[k]=v}
        fun putLong(k:String,v:Long)=apply{changes[k]=v}
        fun apply(){owner.applyChanges(changes.toMap())}
    }
}
