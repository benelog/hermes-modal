package net.benelog.kakaocollector

/**
 * 백필(명시 수집) 후 전송 무결성 판정(순수 함수). 로컬 SQLite와 Modal 서버의
 * '발신일(client_time)별 저장 건수'를 비교해 누락 없이 전송됐는지 확인한다.
 *
 * 비교 단위는 양쪽 다 **중복제거 키(방+본문+발신일, sender 제외) 기준 건수**다:
 * 서버는 키당 레코드 1개라 레코드 수 = 키 수이고, 로컬은 sender 흔들림으로 같은 키가
 * 여러 행일 수 있어 COUNT(DISTINCT text)로 센다 — 그래야 같은 것끼리 비교가 된다.
 *
 * 판정: 어떤 날짜든 로컬 > 서버면 누락 의심(NOT ok). 서버 > 로컬은 정상일 수 있어
 * (폰 30일 보관 정리 후에도 서버 14일 창에 남은 레코드 등) 정보로만 표시한다.
 */
object TransferCheck {
    data class Report(val ok: Boolean, val summary: String, val detail: String)

    fun compare(
        start: String,
        end: String,
        local: Map<String, Int>,
        server: Map<String, Int>,
        unsent: Int,
    ): Report {
        val dates = (local.keys + server.keys).toSortedSet()
        val missingLines = ArrayList<String>()
        var localTotal = 0
        var serverTotal = 0
        for (d in dates) {
            val l = local[d] ?: 0
            val s = server[d] ?: 0
            localTotal += l
            serverTotal += s
            if (l > s) missingLines.add("$d: 로컬 $l > 서버 $s (${l - s}건 누락 의심)")
        }
        val ok = missingLines.isEmpty() && unsent == 0
        val range = if (start == end) start else "$start~$end"
        val summary = if (ok) {
            "전송 검증 ✅ $range 로컬 ${localTotal}건 ↔ 서버 ${serverTotal}건 · 미전송 0"
        } else {
            "전송 검증 ⚠ $range 로컬 ${localTotal}건 / 서버 ${serverTotal}건 · 미전송 ${unsent}건"
        }
        val detail = buildString {
            append(summary)
            for (line in missingLines) append('\n').append(line)
            if (unsent > 0) append('\n').append("미전송(sent_ok=0) ${unsent}건 — 네트워크 복구 후 자동 재전송됩니다")
            if (!ok) append('\n').append("잠시 후 같은 기간으로 백필을 다시 실행하면 재전송·재검증됩니다")
        }
        return Report(ok, summary, detail)
    }
}
