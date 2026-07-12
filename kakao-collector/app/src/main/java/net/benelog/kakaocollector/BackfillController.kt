package net.benelog.kakaocollector

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 명시적 백필 수집 세션(한 번에 하나). MainActivity에서 기간(from–to)을 지정해 시작하면
 * 카카오톡의 대상 방으로 이동한 뒤(자동으로 못 열면 사용자가 직접 열 때까지 대기) 두 단계로 돈다:
 *
 *  SEEK    위로(과거로) 크게 스크롤 — 수집하지 않고 날짜만 읽으며 from '이전 날'이 보일
 *          때까지 거슬러 올라간다. 이 단계에서 수집하면 received_at 이 역순으로 쌓여
 *          서버의 대화 순서가 뒤집히므로 읽기만 한다.
 *  COLLECT 아래로(현재로) 반 화면씩 천천히 스크롤 — settle 프레임마다 scrape가 돌고,
 *          seen 캐시를 우회해 이미 수집된 메시지도 누락된 날짜/시각을 제자리 갱신한다.
 *          to 를 지나거나 맨 아래(진행 없음)에 닿으면 종료.
 *
 * 스크롤은 접근성 제스처(느린 드래그)로만 넘긴다 — 플링(빠른 스크롤)은 settle 프레임 사이에
 * 화면을 건너뛰어 수집 누락을 만든다(사용자 수동 스크롤 누락의 원인). 진행 상태는
 * [statusNow]로 노출하고 MainActivity가 폴링해 보여준다. 모든 상태 전이는 main 스레드.
 */
object BackfillController {

    enum class Phase { IDLE, NAVIGATE, ARMED, SEEK, COLLECT, DONE, FAILED }

    data class Request(val room: String, val fromMillis: Long, val toMillis: Long)

    data class Status(
        val phase: Phase,
        val room: String,
        val message: String,
        val steps: Int,
        val inserted: Int,
        val updated: Int,
    )

    /** scrape 한 프레임의 관찰값: 화면에서 확인된 가장 과거 날짜 + 진행 감지용 서명. */
    data class Frame(val minDate: String, val signature: String)

    private const val TAG = KakaoCollectorService.TAG

    // 방 자동 진입: 1.2초마다 시도, 25초 지나면 수동 열기 대기(ARMED)로 전환, 5분 내 안 열면 포기.
    private const val NAV_TICK_MS = 1200L
    private const val NAV_TIMEOUT_MS = 25_000L
    private const val ARMED_TIMEOUT_MS = 5 * 60_000L

    // SEEK: 0.6화면씩 280ms 드래그(다소 빠름 — 수집 안 하므로 프레임 누락 무해).
    // COLLECT: 0.4화면씩 500ms 드래그(느림 — 프레임 겹침 보장, 모든 메시지가 settle 프레임에 등장).
    private const val SEEK_SWIPE_RATIO = 0.6f
    private const val SEEK_SWIPE_MS = 280L
    private const val SEEK_PACE_MS = 350L
    private const val COLLECT_SWIPE_RATIO = 0.4f
    private const val COLLECT_SWIPE_MS = 500L
    private const val COLLECT_PACE_MS = 550L

    // 스와이프 후 settle 프레임이 안 오면 강제 scrape 1회, 그래도 없으면 진행 없음으로 계산.
    private const val FRAME_TIMEOUT_MS = 2500L
    private const val FORCED_FRAME_TIMEOUT_MS = 1500L
    private const val NO_PROGRESS_LIMIT = 3
    private const val MAX_SEEK_STEPS = 800
    private const val MAX_COLLECT_STEPS = 2000
    private const val MAX_SESSION_MS = 40 * 60_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var status = Status(Phase.IDLE, "", "아직 실행한 적 없음", 0, 0, 0)

    // 아래 상태는 모두 main 스레드에서만 접근.
    private var gen = 0 // 세션 세대 — 이전 세션의 지연 콜백 무효화
    private var request: Request? = null
    private var phase = Phase.IDLE
    private var fromDate = ""
    private var toDate = ""
    private var steps = 0
    private var inserted = 0
    private var updated = 0
    private var lastSignature = ""
    private var noProgress = 0
    private var awaitingFrame = false
    private var swipeSeq = 0
    private var peekMinDate = ""
    private var startedAt = 0L
    private var inRoom = false

    fun statusNow(): Status = status

    fun isRunning(): Boolean =
        phase == Phase.NAVIGATE || phase == Phase.ARMED || phase == Phase.SEEK || phase == Phase.COLLECT

    /** scrape가 이 방에서 '읽기만'(SEEK) 해야 하는가. */
    fun seekActive(room: String): Boolean = phase == Phase.SEEK && request?.room == room

    /** scrape가 이 방에서 seen 우회 수집(COLLECT) 해야 하는가. */
    fun collectActive(room: String): Boolean = phase == Phase.COLLECT && request?.room == room

    /** 수집 시작. 실패 사유 문자열을 반환하면 시작 안 된 것(null = 시작됨). main 스레드에서 호출. */
    fun start(req: Request): String? {
        if (isRunning()) return "이미 수집이 진행 중입니다 — 먼저 중지하세요"
        if (KakaoCollectorService.instance == null) return "접근성 서비스가 연결돼 있지 않습니다"
        if (req.room.isBlank()) return "대상 방이 없습니다"
        gen++
        request = req
        fromDate = KakaoDate.isoOf(req.fromMillis)
        toDate = KakaoDate.isoOf(req.toMillis)
        phase = Phase.NAVIGATE
        steps = 0; inserted = 0; updated = 0
        lastSignature = ""; noProgress = 0; awaitingFrame = false; peekMinDate = ""
        inRoom = false
        startedAt = System.currentTimeMillis()
        Log.i(TAG, "backfill start room=${req.room} from=$fromDate to=$toDate")
        publish("'${req.room}' 방을 여는 중…")
        scheduleNavTick(gen)
        return null
    }

    /** 사용자 중지(또는 화면의 중지 버튼). 지금까지의 집계를 남기고 세션을 끝낸다. */
    fun stop() {
        if (!isRunning()) return
        finish(Phase.DONE, "중지됨")
    }

    /** 서비스가 죽으면(꺼짐/재바인딩) 세션도 중단 — 유령 제스처 방지. */
    fun onServiceDisconnected() {
        if (isRunning()) finish(Phase.FAILED, "접근성 서비스 연결이 끊겼습니다")
    }

    // ── 서비스 훅(모두 main 스레드) ─────────────────────────────

    /** 방 판별 결과(제목을 읽은 이벤트마다). matched=""는 '대상 방 아님이 확실'. */
    fun onRoomState(matchedRoom: String) {
        val req = request ?: return
        if (!isRunning()) return
        val isTarget = matchedRoom == req.room
        if (isTarget && !inRoom) {
            inRoom = true
            when (phase) {
                Phase.NAVIGATE, Phase.ARMED -> beginSeek()
                Phase.SEEK, Phase.COLLECT -> {
                    publish("대상 방으로 복귀 — 이어서 진행")
                    scheduleSwipe(gen, pace())
                }
                else -> {}
            }
        } else if (!isTarget && inRoom) {
            inRoom = false
            if (phase == Phase.SEEK || phase == Phase.COLLECT) {
                publish("대상 방을 벗어났습니다 — '${req.room}' 방을 다시 열면 이어서 진행")
            }
        }
    }

    /** 스크롤 중 스티키 날짜 뱃지 관찰(설정 프레임 밖에서도 seek 종료 판정에 쓴다). */
    fun onDatePeek(date: String) {
        if (date.isEmpty() || !isRunning()) return
        peekMinDate = BackfillPlanner.minDate(peekMinDate, date)
    }

    /** settle scrape 한 프레임 완료. 스와이프에 대한 응답 프레임만 걸음 수로 계산. */
    fun onFrame(room: String, frame: Frame) {
        val req = request ?: return
        if (room != req.room) return
        if (phase != Phase.SEEK && phase != Phase.COLLECT) return
        if (!awaitingFrame) return // 스와이프와 무관한 프레임(새 메시지 도착 등)
        awaitingFrame = false
        steps++
        val minDate = BackfillPlanner.minDate(frame.minDate, peekMinDate)
        if (frame.signature == lastSignature) noProgress++ else { noProgress = 0; lastSignature = frame.signature }

        if (phase == Phase.SEEK) {
            when {
                BackfillPlanner.seekDone(minDate, fromDate) -> beginCollect("$fromDate 이전 날짜 도달")
                noProgress >= NO_PROGRESS_LIMIT -> beginCollect("대화 처음에 도달")
                steps >= MAX_SEEK_STEPS -> beginCollect("탐색 걸음 상한 도달")
                else -> {
                    publish("과거로 이동 중… ${steps}걸음 · 화면 날짜 ${minDate.ifEmpty { "?" }}")
                    scheduleSwipe(gen, SEEK_PACE_MS)
                }
            }
        } else {
            when {
                BackfillPlanner.collectDone(minDate, toDate) -> finish(Phase.DONE, "$toDate 이후 날짜 도달 — 수집 완료")
                noProgress >= NO_PROGRESS_LIMIT -> finish(Phase.DONE, "맨 아래 도달 — 수집 완료")
                steps >= MAX_COLLECT_STEPS -> finish(Phase.DONE, "수집 걸음 상한 도달")
                else -> {
                    publish("수집 중… ${steps}걸음 · 신규 $inserted · 갱신 $updated")
                    scheduleSwipe(gen, COLLECT_PACE_MS)
                }
            }
        }
    }

    /** Uploader 스레드에서 저장 결과 통지(COLLECT 집계용). */
    fun onSubmitOutcome(outcome: MessageStore.Outcome) {
        handler.post {
            if (!isRunning()) return@post
            when (outcome) {
                MessageStore.Outcome.INSERTED -> inserted++
                MessageStore.Outcome.UPDATED -> updated++
                else -> {}
            }
        }
    }

    // ── 내부 진행 ────────────────────────────────────────────────

    private fun scheduleNavTick(g: Int) {
        handler.postDelayed({ navTick(g) }, NAV_TICK_MS)
    }

    private fun navTick(g: Int) {
        if (g != gen || phase != Phase.NAVIGATE) return
        val svc = KakaoCollectorService.instance ?: run { finish(Phase.FAILED, "접근성 서비스 연결이 끊겼습니다"); return }
        if (svc.inRoomNow(request?.room ?: "")) { onRoomState(request?.room ?: ""); return }
        if (System.currentTimeMillis() - startedAt > NAV_TIMEOUT_MS) {
            phase = Phase.ARMED
            publish("방을 자동으로 열지 못했습니다 — 카카오톡에서 '${request?.room}' 방을 직접 열면 이어서 진행됩니다")
            handler.postDelayed({ if (g == gen && phase == Phase.ARMED) finish(Phase.FAILED, "방이 열리지 않아 종료(5분 초과)") }, ARMED_TIMEOUT_MS)
            return
        }
        svc.tryOpenRoom(request?.room ?: "")
        scheduleNavTick(g)
    }

    private fun beginSeek() {
        phase = Phase.SEEK
        steps = 0; noProgress = 0; lastSignature = ""; peekMinDate = ""; awaitingFrame = false
        publish("과거로 이동 중… ($fromDate 이전 날짜가 보일 때까지)")
        scheduleSwipe(gen, SEEK_PACE_MS)
    }

    private fun beginCollect(reason: String) {
        phase = Phase.COLLECT
        steps = 0; noProgress = 0; lastSignature = ""; awaitingFrame = false
        Log.i(TAG, "backfill collect begins ($reason)")
        publish("수집 시작($reason) — 아래로 스크롤하며 저장")
        scheduleSwipe(gen, COLLECT_PACE_MS)
    }

    private fun pace(): Long = if (phase == Phase.SEEK) SEEK_PACE_MS else COLLECT_PACE_MS

    private fun scheduleSwipe(g: Int, delayMs: Long) {
        handler.postDelayed({ swipeTick(g) }, delayMs)
    }

    private fun swipeTick(g: Int) {
        if (g != gen || (phase != Phase.SEEK && phase != Phase.COLLECT)) return
        if (System.currentTimeMillis() - startedAt > MAX_SESSION_MS) {
            finish(Phase.DONE, "시간 상한(40분) 도달 — 여기까지 수집")
            return
        }
        val svc = KakaoCollectorService.instance ?: run { finish(Phase.FAILED, "접근성 서비스 연결이 끊겼습니다"); return }
        // 제스처는 화면 최상위에 그대로 꽂힌다 — 대상 방이 아닌 화면(다른 앱/다른 방)에는
        // 절대 스와이프하지 않는다. 방을 벗어났으면 일시정지(재진입 시 onRoomState가 재개).
        if (!svc.inRoomNow(request?.room ?: "")) {
            inRoom = false
            publish("대상 방 화면이 아닙니다 — '${request?.room}' 방을 열면 이어서 진행")
            return
        }
        peekMinDate = ""
        awaitingFrame = true
        val seq = ++swipeSeq
        val down = phase == Phase.SEEK // 손가락 아래로 드래그 = 과거(위) 내용 보기
        val ratio = if (down) SEEK_SWIPE_RATIO else COLLECT_SWIPE_RATIO
        val durMs = if (down) SEEK_SWIPE_MS else COLLECT_SWIPE_MS
        if (!svc.performSwipe(downward = down, distanceRatio = ratio, durationMs = durMs)) {
            noProgress++
            if (noProgress >= NO_PROGRESS_LIMIT) {
                if (phase == Phase.SEEK) beginCollect("제스처 실패 반복") else finish(Phase.DONE, "제스처 실패 반복 — 여기까지 수집")
            } else {
                awaitingFrame = false
                scheduleSwipe(g, pace())
            }
            return
        }
        handler.postDelayed({ frameWatchdog(g, seq, forced = false) }, durMs + FRAME_TIMEOUT_MS)
    }

    /** 스와이프 후 settle 프레임이 안 오면 강제 scrape 1회 → 그래도 없으면 진행 없음으로 취급. */
    private fun frameWatchdog(g: Int, seq: Int, forced: Boolean) {
        if (g != gen || seq != swipeSeq || !awaitingFrame) return
        if (phase != Phase.SEEK && phase != Phase.COLLECT) return
        val svc = KakaoCollectorService.instance ?: return
        if (!forced) {
            svc.forceScrape()
            handler.postDelayed({ frameWatchdog(g, seq, forced = true) }, FORCED_FRAME_TIMEOUT_MS)
            return
        }
        awaitingFrame = false
        noProgress++
        if (noProgress >= NO_PROGRESS_LIMIT) {
            if (phase == Phase.SEEK) beginCollect("프레임 없음 반복") else finish(Phase.DONE, "프레임 없음 반복 — 여기까지 수집")
        } else {
            scheduleSwipe(g, pace())
        }
    }

    private fun finish(endPhase: Phase, message: String) {
        gen++ // 남은 지연 콜백 전부 무효화
        phase = endPhase
        val summary = "$message · 신규 ${inserted}건 · 갱신 ${updated}건"
        Log.i(TAG, "backfill finished: $summary")
        publish(summary)
        Uploader.flushUnsent() // 수집 중 전송 실패분을 이 기회에 재시도
        KakaoCollectorService.instance?.showToast("백필 수집: $summary")
    }

    private fun publish(message: String) {
        status = Status(phase, request?.room ?: "", message, steps, inserted, updated)
    }
}
