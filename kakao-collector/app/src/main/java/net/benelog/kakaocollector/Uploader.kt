package net.benelog.kakaocollector

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * 수집 메시지를 로컬 저장(중복제거) 후 '새 것만' Modal로 전송한다. 모든 DB/네트워크 작업은 단일 백그라운드 스레드.
 *
 * 전송 실패(sent_ok=0) 행은 버리지 않고 재전송한다: 인메모리 seen이 같은 키의 재수집을 막기
 * 때문에 한 번 실패한 행은 저절로 다시 전송될 기회가 없다(과거 245건 누적의 원인). 그래서
 * (1) 서비스 연결 시, (2) 대상 방 입장 시, (3) 다른 메시지 전송이 성공한 직후(네트워크 살아있음이
 * 확인된 시점)에 최근 시간창의 미전송분을 오래된 것부터 다시 보낸다. 서버 키(room,text,client_time)가
 * 중복 수신을 걸러주므로 드물게 이중 전송돼도 무해하다.
 */
object Uploader {
    // flush 재시도 정책: 최소 간격(활발한 수집 중 과도한 재시도 방지), 대상 시간창(오래된 행을
    // 지금 올리면 received_at=now로 들어가 요약에 옛 대화가 새 것처럼 섞인다 → 48시간로 제한), 회차당 상한.
    private const val FLUSH_MIN_INTERVAL_MS = 60_000L
    private const val FLUSH_WINDOW_MS = 48L * 60 * 60 * 1000
    private const val FLUSH_BATCH = 50

    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var store: MessageStore
    private var lastFlushAt = 0L // exec 단일 스레드에서만 접근

    /** 시작 시 1회. store 준비 + 오래된 행 정리(retentionMillis 초과) + 밀린 미전송분 재시도. */
    fun init(context: Context, retentionMillis: Long) {
        if (!::store.isInitialized) store = MessageStore(context.applicationContext)
        val cutoff = System.currentTimeMillis() - retentionMillis
        exec.execute {
            try {
                store.prune(cutoff)
                flushPending(force = true)
            } catch (e: Exception) {
                Log.w(KakaoCollectorService.TAG, "uploader init flush failed: ${e.message}")
            }
        }
    }

    /** 인메모리 seen 시드용 — 최근 키. init 이후 호출. */
    fun recentKeys(limit: Int): Set<String> =
        if (::store.isInitialized) store.recentKeys(limit) else emptySet()

    /**
     * 수집 메시지 제출: DB 기록/병합 → 새 행이거나 누락 필드(본문/날짜/시각) 갱신이면 POST → 성공 시 sent_ok.
     * 잘린 본문이 이미 더 완전한 행으로 있으면(SKIPPED) 아무것도 안 한다. 갱신 시에도 완전한 본문을 보내
     * 서버가 같은 레코드를 제자리(received_at 유지=순서 보존)에서 합치게 한다.
     * [fromScroll]은 스크롤 settle 수집 여부 — [MergePolicy]의 날짜 경계 가드용.
     * [onOutcome]은 백필 진행 집계용(선택).
     *
     * [repostOnSkip]=true(백필 COLLECT): 로컬에 이미 완전하게 저장된 메시지(SKIPPED)라도 서버로
     * 멱등 재전송한다 — 서버 보관(수신 후 14일)이 폰(30일)보다 짧아 "로컬엔 있는데 서버에서 정리된"
     * 레코드가 있을 수 있고, 백필이 그 구간을 다시 채우는 유일한 경로다. 서버 키/병합이 중복은 걸러준다.
     */
    fun submit(
        record: MessageRecord,
        fromScroll: Boolean = false,
        repostOnSkip: Boolean = false,
        onOutcome: ((MessageStore.Outcome) -> Unit)? = null,
    ) {
        exec.execute {
            try {
                val r = store.recordOrMerge(record, System.currentTimeMillis(), fromScroll)
                onOutcome?.invoke(r.outcome)
                if (r.outcome == MessageStore.Outcome.SKIPPED) {
                    // 어느 행에 합쳐졌는지 모르므로 markSent 없이 들어온 값 그대로 재전송만 한다.
                    if (repostOnSkip) ModalApi.ingest(record)
                    return@execute
                }
                // 병합 후 DB에 남은 값(r.*)을 보낸다 — 서버 저장소가 폰 DB와 같은 상태로 수렴.
                val ok = ModalApi.ingest(
                    record.copy(text = r.text, clientTime = r.clientTime, sentTime = r.sentTime),
                )
                if (ok) {
                    store.markSent(r.rowId)
                    flushPending() // 방금 성공 = 네트워크 정상 → 밀린 미전송분도 이 기회에 재시도
                }
            } catch (e: Exception) {
                // 예외로 executor 스레드가 조용히 죽지 않게(다음 제출은 계속). 실패분은 flush가 재시도.
                Log.w(KakaoCollectorService.TAG, "uploader submit failed: ${e.message}")
            }
        }
    }

    /** 미전송분 재시도 요청(비동기). 대상 방 입장 등 '활동 재개' 시점에 호출. */
    fun flushUnsent() {
        if (!::store.isInitialized) return
        exec.execute {
            try {
                flushPending()
            } catch (e: Exception) {
                Log.w(KakaoCollectorService.TAG, "uploader flush failed: ${e.message}")
            }
        }
    }

    /** 최근 시간창의 sent_ok=0 행을 오래된 것부터 재전송. 하나라도 실패하면(네트워크 다운) 이번 회차 중단. */
    private fun flushPending(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlushAt < FLUSH_MIN_INTERVAL_MS) return
        lastFlushAt = now
        val rows = store.unsentRows(now - FLUSH_WINDOW_MS, FLUSH_BATCH)
        var sent = 0
        for (row in rows) {
            if (!ModalApi.ingest(row.record)) break
            store.markSent(row.id)
            sent++
        }
        if (sent > 0) Log.i(KakaoCollectorService.TAG, "flushed $sent unsent message(s)")
    }

    /** 연결 테스트용: 저장하지 않고 즉시 POST(백그라운드). */
    fun testPost(room: String, sender: String, text: String) {
        exec.execute { ModalApi.ingest(MessageRecord(room, sender, text)) }
    }

    /**
     * 백필 후 전송 무결성 검증: 미전송분을 먼저 밀어낸 뒤(flush) 로컬 SQLite의 발신일별
     * 건수(중복제거 키 기준)와 서버 kakao-stats 의 발신일별 건수를 비교한다([TransferCheck]).
     * exec 는 FIFO 단일 스레드라, 백필 중 큐에 쌓인 제출이 모두 처리된 '뒤에' 실행된다 —
     * 즉 비교 시점의 로컬/서버 상태가 최종본이다. [onResult]는 백그라운드 스레드에서 불린다.
     */
    fun verifyTransfer(room: String, start: String, end: String, onResult: (TransferCheck.Report) -> Unit) {
        exec.execute {
            try {
                flushPending(force = true)
                val local = store.distinctCountsByDate(room, start, end)
                val unsent = store.unsentCountInRange(room, start, end, System.currentTimeMillis())
                val server = ModalApi.fetchDailyCounts(room, start, end)
                if (server == null) {
                    val msg = "전송 검증 ✖ 서버 통계 조회 실패 — 네트워크/Stats URL 확인"
                    onResult(TransferCheck.Report(false, msg, msg))
                    return@execute
                }
                onResult(TransferCheck.compare(start, end, local, server, unsent))
            } catch (e: Exception) {
                Log.w(KakaoCollectorService.TAG, "verifyTransfer failed: ${e.message}")
                val msg = "전송 검증 ✖ 오류: ${e.message}"
                onResult(TransferCheck.Report(false, msg, msg))
            }
        }
    }
}
