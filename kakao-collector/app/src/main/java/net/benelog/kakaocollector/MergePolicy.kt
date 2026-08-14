package net.benelog.kakaocollector

/**
 * [MessageStore.recordOrMerge]의 병합 판단(순수 로직) — 들어온 메시지와 기존 행 하나를
 * 비교해 어떻게 처리할지 정한다. DB 접근이 없어 단위 테스트 대상이다.
 *
 *  - 같은 본문 + 호환 날짜(같거나 한쪽이 빈값) → 같은 메시지. 누락 필드만 제자리 승급
 *    (빈 날짜→날짜, 시각은 더 이른 값). 바뀐 게 없으면 [Decision.Skip].
 *  - 기존 본문이 이 메시지의 '더 짧은 앞부분'([KakaoText.isExtendedBy]) → 완전한 본문으로
 *    in-place 갱신(원래 행 유지 = 대화 순서 보존).
 *  - 반대로 들어온 게 잘린 것 → 본문은 버리되 날짜/시각은 채울 수 있으면 채운다.
 *  - 어느 경우도 아니면 [Decision.NoMatch] — 다음 행과 비교.
 */
object MergePolicy {

    // 스크롤(백필) 재수집에서 같은 본문이 '하루 인접한 다른 날짜'로 다시 들어오는 것은
    // 날짜 경계 오부여(스티키 뱃지 지연/구분선 미포착)다 — 단, 그 재수집은 같은 스크롤
    // 세션(수 분) 안에서 일어나므로, 기존 행이 이 시간창 안에서 수집된 경우로 제한한다.
    // 진짜로 며칠 뒤 같은 말을 반복한 메시지까지 합쳐버리지 않기 위한 안전핀.
    private const val CROSS_DAY_RESCRAPE_WINDOW_MS = 6L * 60 * 60 * 1000

    data class ExistingRow(
        val id: Long,
        val text: String,
        val clientTime: String,
        val sentTime: String,
        val collectedAt: Long,
    )

    sealed interface Decision {
        /** 기존 행을 이 값으로 in-place 갱신(병합). */
        data class Update(
            val id: Long,
            val text: String,
            val clientTime: String,
            val sentTime: String,
        ) : Decision

        /** 이미 완전한 같은 메시지(또는 날짜 오부여 사본) — 버린다. */
        object Skip : Decision

        /** 이 행과는 무관 — 다음 행과 비교. */
        object NoMatch : Decision
    }

    /** 같은 날(또는 한쪽이 빈값)로 볼 수 있으면 true — 알려진 다른 날끼리는 합치지 않는다. */
    private fun compatibleDates(a: String, b: String): Boolean = a == b || a.isEmpty() || b.isEmpty()

    /**
     * [fromScroll]=true(스크롤 settle 수집)면 날짜 경계 가드가 추가로 작동한다: 같은 본문이
     * 방금(시간창 내) '하루 인접한 다른 날짜'로 저장돼 있으면 재수집 오부여로 보고 버린다.
     * 실시간 수집(false)엔 적용하지 않는다 — 자정 전후로 같은 말을 정말로 두 번 보낸
     * 메시지를 지우면 안 되기 때문(2026-07-05 오수집 재발 방지).
     */
    fun decide(
        existing: ExistingRow,
        incoming: MessageRecord,
        nowMillis: Long,
        fromScroll: Boolean,
    ): Decision {
        if (existing.text == incoming.text) {
            if (compatibleDates(existing.clientTime, incoming.clientTime)) {
                return fillMissingFields(existing, incoming)
            }
            if (fromScroll && nowMillis - existing.collectedAt <= CROSS_DAY_RESCRAPE_WINDOW_MS &&
                KakaoDate.isAdjacentDay(existing.clientTime, incoming.clientTime)
            ) {
                return Decision.Skip
            }
            return Decision.NoMatch // 같은 본문, 다른 '아는' 날 → 다른 메시지
        }
        if (!compatibleDates(existing.clientTime, incoming.clientTime)) return Decision.NoMatch
        if (KakaoText.isExtendedBy(existing.text, incoming.text)) { // 기존이 짧음 → 완전한 본문으로 갱신
            return Decision.Update(
                existing.id,
                incoming.text,
                existing.clientTime.ifEmpty { incoming.clientTime },
                KakaoTime.earliest(existing.sentTime, incoming.sentTime),
            )
        }
        if (KakaoText.isExtendedBy(incoming.text, existing.text)) { // 들어온 게 잘린 것 → 본문은 버림
            return fillMissingFields(existing, incoming)
        }
        return Decision.NoMatch
    }

    /** 본문은 기존 행을 유지하고 누락 날짜/시각만 승급. 바뀐 게 없으면 Skip. */
    private fun fillMissingFields(existing: ExistingRow, incoming: MessageRecord): Decision {
        val mergedCt = existing.clientTime.ifEmpty { incoming.clientTime }
        val mergedSt = KakaoTime.earliest(existing.sentTime, incoming.sentTime)
        return if (mergedCt != existing.clientTime || mergedSt != existing.sentTime) {
            Decision.Update(existing.id, existing.text, mergedCt, mergedSt)
        } else {
            Decision.Skip
        }
    }
}
