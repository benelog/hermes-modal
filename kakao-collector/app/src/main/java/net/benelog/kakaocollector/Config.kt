package net.benelog.kakaocollector

/**
 * 설정 "기본값". 실제 런타임 값은 [Settings](SharedPreferences)에서 읽으며, 앱 설정 화면에서
 * 편집한다. 즉 토큰/URL 등은 앱에서 입력하면 되고, 여기 실값을 커밋할 필요가 없다.
 *
 * 여기 값들은 사용자가 설정 화면에서 한 번도 저장하지 않았을 때의 폴백일 뿐이다.
 * TOKEN은 placeholder로 두어 git에 실값이 남지 않게 한다.
 */
object Config {
    const val KAKAO_PACKAGE = "com.kakao.talk"

    // ── 필수 설정 ────────────────────────────────────────────────
    /** 카카오톡 채팅방 상단에 보이는 정확한 표시명. */
    const val ROOM_NAME = "아카라카북클럽"

    /** Modal 수집 엔드포인트(고정). */
    const val INGEST_URL = "https://benelog--kakao-ingest.modal.run"

    /** Modal 요약 엔드포인트(고정). 방 멘션 요약 트리거가 호출한다. */
    const val SUMMARIZE_URL = "https://benelog--kakao-summarize.modal.run"

    /** Modal 시크릿 KAKAO_COLLECTOR_TOKEN과 동일한 값. (~/.hermes/.env 참고) */
    const val TOKEN = "PUT_YOUR_KAKAO_COLLECTOR_TOKEN_HERE"

    /** 내 카톡 닉네임. 내가 보낸 메시지엔 닉네임이 안 떠서, 보낸이로 채울 값(앱 설정에서 입력). */
    const val OWN_NAME = ""

    // ── Calibration으로 채울 resource-id ────────────────────────
    // CALIBRATE=true로 빌드 → 접근성 켜고 대상 방 열기 →
    //   adb logcat -s KakaoCollector  로 "id=... text=..." 를 보고 채운 뒤 false로.
    // 아래 기본값은 2026-06 카카오톡(Android)에서 실측한 id다. UI 업데이트로 바뀌면 재캘리브레이션.
    /** 말풍선 본문 노드 id. */
    const val MSG_ID = "com.kakao.talk:id/message"

    /** 보낸이 이름 노드 id. (실측: name이 아니라 nickname) */
    const val NAME_ID = "com.kakao.talk:id/nickname"

    /** 시각 노드 id. (실측: created_at이 아니라 time. 단, 시각은 접근성에 거의 노출 안 됨) */
    const val TIME_ID = "com.kakao.talk:id/time"

    /** 방 제목 노드 id(방 열 때만 text로 뜸, 폴백용). (실측: 방 상단 제목은 id/name 노드에 들어감) */
    const val TITLE_ID = "com.kakao.talk:id/name"

    /**
     * 툴바 제목 노드 id. text는 비어도 **contentDescription에 방이름(+인원수)이 스크롤 중에도 안정적으로**
     * 있어, 현재 방을 매 이벤트마다 확실히 식별하는 1차 수단으로 쓴다. (실측 2026-06 카톡)
     */
    const val TOOLBAR_TITLE_ID = "com.kakao.talk:id/toolbar_default_title_text"

    // ── 방 멘션 요약(발신) ───────────────────────────────────────
    // 방에서 "@정상혁 …요약" 멘션을 감지하면 Modal로 요약을 받아 그 방으로 발신한다.
    // 발신은 읽기 전용 원칙을 깨므로, 입력창/전송버튼 id 캘리브레이션 후 AUTO_REPLY를 켠다.
    /** 멘션 키워드. 비우면 OWN_NAME(내 닉네임)으로 폴백. 메시지에 이 문자열이 있으면 '나를 부른 것'. */
    const val MENTION_KEYWORD = ""

    /** 요약 명령 키워드. 멘션 키워드와 함께 있으면 요약 트리거. */
    const val SUMMARY_KEYWORD = "요약"

    /** 봇이 보낸 요약 앞에 붙이는 마커. 이 마커가 든 메시지는 트리거에서 제외(루프 방지). */
    const val BOT_MARKER = "🤖 "

    /** 카톡 메시지 입력창 EditText id(캘리브레이션). 비면 발신 불가. */
    const val INPUT_ID = "com.kakao.talk:id/message_edit_text"

    /** 카톡 전송 버튼 id(캘리브레이션). 비면 발신 불가. */
    const val SEND_ID = "com.kakao.talk:id/send"

    /** true면 방에서 멘션 요약 감지 시 그 방으로 자동 발신. 기본 false(캘리브레이션 후 수동 ON). */
    const val AUTO_REPLY = false

    /** true면 수집 대신 현재 화면 노드 트리를 Logcat에 덤프(캘리브레이션). */
    const val CALIBRATE = false
}
