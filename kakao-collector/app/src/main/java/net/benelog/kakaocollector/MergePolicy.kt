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

    // 재수집에서 같은 본문이 '하루 인접한 다른 날짜'로 다시 들어오는 것은 날짜 경계 오부여
    // (스티키 뱃지 지연/구분선 미포착)다 — 단, 그 재수집은 기존 행을 수집한 직후에 일어나므로
    // 기존 행이 이 시간창 안에서 수집된 경우로 제한한다(며칠 뒤 같은 말을 반복한 메시지 보호).
    //  - 스크롤(백필) 수집: 같은 스크롤 세션 → 6시간.
    //  - 실시간 수집: 2026-09-26 폰 DB에서 비스크롤 경로(스크롤 중 CONTENT_CHANGED 프레임)로도
    //    5초~9분 간격 분할이 7쌍 나왔다 → 10분. 자정 전후 10분 안에 똑같은 말을 두 번 보낸
    //    경우만 하나로 합쳐지는 대가(같은 날 같은 본문을 합치는 기존 sender-free 키와 같은 부류).
    private const val CROSS_DAY_RESCRAPE_WINDOW_MS = 6L * 60 * 60 * 1000
    private const val CROSS_DAY_LIVE_WINDOW_MS = 10L * 60 * 1000

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

    /**
     * 날짜 경계 가드: 같은 본문이 방금(시간창 내) '하루 인접한 다른 날짜'로 저장돼 있으면
     * 재수집 오부여로 보고 버린다. 시간창은 [fromScroll]=true(스크롤 settle 수집)면 6시간,
     * 실시간 수집이면 10분 — 자정 전후로 같은 말을 정말로 두 번 보낸 메시지를 지우지 않도록
     * 실시간 쪽은 좁게 둔다.
     */
    fun decide(
        existing: ExistingRow,
        incoming: MessageRecord,
        nowMillis: Long,
        fromScroll: Boolean,
    ): Decision {
        if (existing.text == incoming.text) {
            if (KnownValue.compatible(existing.clientTime, incoming.clientTime)) {
                return merge(existing, incoming, existing.text)
            }
            val window = if (fromScroll) CROSS_DAY_RESCRAPE_WINDOW_MS else CROSS_DAY_LIVE_WINDOW_MS
            if (nowMillis - existing.collectedAt <= window &&
                KakaoDate.isAdjacentDay(existing.clientTime, incoming.clientTime)
            ) {
                return Decision.Skip
            }
            return Decision.NoMatch // 같은 본문, 다른 '아는' 날 → 다른 메시지
        }
        if (!KnownValue.compatible(existing.clientTime, incoming.clientTime)) return Decision.NoMatch
        if (KakaoText.isExtendedBy(existing.text, incoming.text)) { // 기존이 짧음 → 완전한 본문으로 갱신
            return merge(existing, incoming, incoming.text)
        }
        if (KakaoText.isExtendedBy(incoming.text, existing.text)) { // 들어온 게 잘린 것 → 본문은 버림
            return merge(existing, incoming, existing.text)
        }
        return Decision.NoMatch
    }

    /**
     * 같은 메시지로 판정된 두 관찰을 합친다(서버 collector_core._merge_fields 와 같은 규칙):
     * 본문은 [text], 빈 날짜는 채우고, 시각은 더 이른 값 — 시각 오귀속은 항상 '늦은' 값으로만
     * 튀므로(자기 라벨이 트리에 없으면 아래=더 나중 묶음의 라벨을 집는다) 이른 값이 진실에 가깝다.
     * 기존 행에서 바뀐 게 없으면 Skip.
     */
    private fun merge(existing: ExistingRow, incoming: MessageRecord, text: String): Decision {
        val mergedCt = existing.clientTime.ifEmpty { incoming.clientTime }
        val mergedSt = KnownValue.earliest(existing.sentTime, incoming.sentTime)
        return if (text != existing.text || mergedCt != existing.clientTime || mergedSt != existing.sentTime) {
            Decision.Update(existing.id, text, mergedCt, mergedSt)
        } else {
            Decision.Skip
        }
    }
}
