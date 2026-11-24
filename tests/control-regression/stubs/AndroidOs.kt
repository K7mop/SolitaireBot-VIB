package android.os
object SystemClock { var now = 100_000L; fun elapsedRealtime(): Long = now }
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    fun post(action: () -> Unit): Boolean { scheduled.add(SystemClock.now to action); return true }
    fun postDelayed(action: () -> Unit, delay: Long): Boolean { scheduled.add(SystemClock.now + delay to action); return true }
    companion object {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        fun runOne(): Boolean {
            val index = scheduled.indexOfFirst { it.first <= SystemClock.now }
            if (index < 0) return false
            scheduled.removeAt(index).second.invoke(); return true
        }
        fun runReady() { var n=0; while(runOne()) { check(++n < 1000) } }
    }
}
