package net.benelog.kakaocollector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferCheckTest {
    // 날짜별 건수가 모두 일치하고 미전송이 없으면 통과.
    @Test fun equalBucketsPass() {
        val counts = mapOf("2026-07-10" to 12, "2026-07-11" to 30)
        val r = TransferCheck.compare("2026-07-10", "2026-07-11", counts, counts, unsent = 0)
        assertTrue(r.ok)
        assertTrue(r.summary.contains("✅"))
        assertTrue(r.summary.contains("42건"))
    }

    // 로컬 > 서버인 날짜가 하나라도 있으면 누락 의심 — 날짜와 차이를 명시.
    @Test fun localGreaterThanServerFails() {
        val local = mapOf("2026-07-10" to 12, "2026-07-11" to 30)
        val server = mapOf("2026-07-10" to 12, "2026-07-11" to 28)
        val r = TransferCheck.compare("2026-07-10", "2026-07-11", local, server, unsent = 0)
        assertFalse(r.ok)
        assertTrue(r.detail.contains("2026-07-11"))
        assertTrue(r.detail.contains("2건 누락 의심"))
    }

    // 서버 > 로컬은 정상일 수 있으므로(폰 30일 정리 등) 통과 — 총계로만 드러난다.
    @Test fun serverGreaterThanLocalIsOk() {
        val local = mapOf("2026-07-10" to 10)
        val server = mapOf("2026-07-10" to 11)
        assertTrue(TransferCheck.compare("2026-07-10", "2026-07-10", local, server, unsent = 0).ok)
    }

    // 미전송(sent_ok=0)이 남아 있으면 건수가 맞아도 실패 — 아직 안 간 것이 있다는 직접 증거.
    @Test fun pendingUnsentFails() {
        val counts = mapOf("2026-07-10" to 5)
        val r = TransferCheck.compare("2026-07-10", "2026-07-10", counts, counts, unsent = 3)
        assertFalse(r.ok)
        assertTrue(r.detail.contains("미전송"))
    }

    // 한쪽에만 있는 날짜도 비교에 포함(서버 0으로 취급).
    @Test fun dateMissingOnServerCounts() {
        val local = mapOf("2026-07-10" to 5, "2026-07-11" to 4)
        val server = mapOf("2026-07-10" to 5)
        val r = TransferCheck.compare("2026-07-10", "2026-07-11", local, server, unsent = 0)
        assertFalse(r.ok)
        assertTrue(r.detail.contains("2026-07-11: 로컬 4 > 서버 0"))
    }
}
