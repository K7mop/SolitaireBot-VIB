package regression

import com.retq.solitairebot.control.RoundDiagnostics

fun main() {
    RoundDiagnostics.reset("speed-regression")
    repeat(1500) { RoundDiagnostics.record("CONFIRMED", "move=$it; cycle=297ms; verify=150ms; ocr=4ms") }
    val report = RoundDiagnostics.report()
    check("CONFIRMED=1500" in report) { "Whole-round totals lost after the log tail filled" }
    check("CONFIRMED.cycle: n=1500, mean=297ms, min=297ms, max=297ms" in report)
    check("Events (800 most recent)" in report)
    check("move=1499;" in report && "move=0;" !in report)
    check(report.length * 2 < 700_000) { "Text share exceeds conservative Binder payload budget" }
    RoundDiagnostics.reset("next-round")
    val next = RoundDiagnostics.report()
    check("CONFIRMED=1500" !in next && "CONFIRMED.cycle" !in next) { "Prior round totals leaked" }
    println("PASS: whole-round counters and timings survive bounded tail, export stays small, next round resets")
}
