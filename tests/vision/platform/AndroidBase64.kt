package android.util
object Base64 {
    const val NO_WRAP=2
    fun decode(v:String,flags:Int)=java.util.Base64.getDecoder().decode(v)
    fun encodeToString(v:ByteArray,flags:Int)=java.util.Base64.getEncoder().encodeToString(v)
}
