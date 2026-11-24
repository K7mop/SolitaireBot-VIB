package android.content.res
import java.io.File
class AssetManager {
    fun open(name:String) = File(System.getProperty("solitaire.assets", "app/src/main/assets"), name).inputStream()
}
