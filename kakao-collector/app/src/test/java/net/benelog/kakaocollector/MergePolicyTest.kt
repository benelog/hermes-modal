package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Test

class MergePolicyTest {
    private val now = 1_000_000_000L

    private fun existing(
        text: String,
        clientTime: String = "2026-07-01",
        sentTime: String = "",
        collectedAt: Long = now,
        id: Long = 7L,
    ) = MergePolicy.ExistingRow(id, text, clientTime, sentTime, collectedAt)

    private fun incoming(
        text: String,
        clientTime: String = "2026-07-01",
        sentTime: String = "",
    ) = MessageRecord("방", "보낸이", text, clientTime, sentTime)

    @Test fun identicalCompleteRowIsSkipped() {
        assertEquals(
            MergePolicy.Decision.Skip,
            MergePolicy.decide(existing("같은 본문"), incoming("같은 본문"), now, fromScroll = false),
        )
    }

    @Test fun missingDateIsFilledInPlace() {
        assertEquals(
            MergePolicy.Decision.Update(7L, "같은 본문", "2026-07-01", ""),
            MergePolicy.decide(
                existing("같은 본문", clientTime = ""),
                incoming("같은 본문", clientTime = "2026-07-01"),
                now, fromScroll = false,
            ),
        )
    }

    @Test fun earlierSentTimeWins() {
        assertEquals(
            MergePolicy.Decision.Update(7L, "같은 본문", "2026-07-01", "09:10"),
            MergePolicy.decide(
                existing("같은 본문", sentTime = "10:30"),
                incoming("같은 본문", sentTime = "09:10"),
                now, fromScroll = false,
            ),
        )
    }

    @Test fun truncatedExistingRowIsUpgradedToFullText() {
        assertEquals(
            MergePolicy.Decision.Update(7L, "충분히 긴 시작 문장인데 계속 이어집니다", "2026-07-01", ""),
            MergePolicy.decide(
                existing("충분히 긴 시작 문장인데…"),
                incoming("충분히 긴 시작 문장인데 계속 이어집니다"),
                now, fromScroll = false,
            ),
        )
    }

    @Test fun truncatedIncomingKeepsExistingTextButFillsDate() {
        assertEquals(
            MergePolicy.Decision.Update(7L, "충분히 긴 시작 문장인데 계속 이어집니다", "2026-07-01", ""),
            MergePolicy.decide(
                existing("충분히 긴 시작 문장인데 계속 이어집니다", clientTime = ""),
                incoming("충분히 긴 시작 문장인데…", clientTime = "2026-07-01"),
                now, fromScroll = false,
            ),
        )
    }

    // 날짜 경계 가드: 스크롤 재수집 + 방금 수집된 같은 본문 + 하루 인접 날짜 = 오부여 사본 → 버림.
    @Test fun scrollRescrapeAtAdjacentDayIsDropped() {
        assertEquals(
            MergePolicy.Decision.Skip,
            MergePolicy.decide(
                existing("같은 본문", clientTime = "2026-07-01", collectedAt = now - 60_000),
                incoming("같은 본문", clientTime = "2026-07-02"),
                now, fromScroll = true,
            ),
        )
    }

    // 실시간 수집은 가드 제외 — 자정 전후로 정말 두 번 보낸 메시지는 각각 남는다.
    @Test fun liveCollectionAtAdjacentDayIsNotDropped() {
        assertEquals(
            MergePolicy.Decision.NoMatch,
            MergePolicy.decide(
                existing("같은 본문", clientTime = "2026-07-01", collectedAt = now - 60_000),
                incoming("같은 본문", clientTime = "2026-07-02"),
                now, fromScroll = false,
            ),
        )
    }

    // 시간창(6h)을 벗어난 옛 행이면 스크롤 수집이라도 다른 메시지로 본다.
    @Test fun oldRowOutsideRescrapeWindowIsNotDropped() {
        assertEquals(
            MergePolicy.Decision.NoMatch,
            MergePolicy.decide(
                existing("같은 본문", clientTime = "2026-07-01", collectedAt = now - 7L * 60 * 60 * 1000),
                incoming("같은 본문", clientTime = "2026-07-02"),
                now, fromScroll = true,
            ),
        )
    }

    @Test fun knownDifferentDaysNeverMerge() {
        assertEquals(
            MergePolicy.Decision.NoMatch,
            MergePolicy.decide(
                existing("충분히 긴 시작 문장인데…", clientTime = "2026-07-01"),
                incoming("충분히 긴 시작 문장인데 계속 이어집니다", clientTime = "2026-07-03"),
                now, fromScroll = false,
            ),
        )
    }

    @Test fun unrelatedTextIsNoMatch() {
        assertEquals(
            MergePolicy.Decision.NoMatch,
            MergePolicy.decide(existing("완전히 다른 본문입니다"), incoming("이것은 새 메시지"), now, fromScroll = false),
        )
    }
}
