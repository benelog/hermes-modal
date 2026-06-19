package net.benelog.kakaocollector

/**
 * 전용 수집 앱 설정. 빌드 전에 아래 값을 채운다.
 *
 * 보안: 이 파일은 git에 커밋될 수 있으니 TOKEN 실값을 커밋하지 말 것.
 *       개인 빌드에서만 채우고, 공유 시에는 placeholder로 되돌린다.
 */
object Config {
    const val KAKAO_PACKAGE = "com.kakao.talk"

    // ── 필수 설정 ────────────────────────────────────────────────
    /** 카카오톡 채팅방 상단에 보이는 정확한 표시명. */
    const val ROOM_NAME = "아카라카북클럽"

    /** Modal 수집 엔드포인트(고정). */
    const val INGEST_URL = "https://benelog--kakao-ingest.modal.run"

    /** Modal 시크릿 KAKAO_COLLECTOR_TOKEN과 동일한 값. (~/.hermes/.env 참고) */
    const val TOKEN = "PUT_YOUR_KAKAO_COLLECTOR_TOKEN_HERE"

    // ── Calibration으로 채울 resource-id ────────────────────────
    // CALIBRATE=true로 빌드 → 접근성 켜고 대상 방 열기 →
    //   adb logcat -s KakaoCollector  로 "id=... text=..." 를 보고 채운 뒤 false로.
    /** 말풍선 본문 노드 id. */
    const val MSG_ID = "com.kakao.talk:id/message"

    /** 보낸이 이름 노드 id. */
    const val NAME_ID = "com.kakao.talk:id/name"

    /** 시각(예: 오후 3:25) 노드 id. */
    const val TIME_ID = "com.kakao.talk:id/created_at"

    /** 방 제목 노드 id(선택, 방 식별 보조용). */
    const val TITLE_ID = "com.kakao.talk:id/title"

    /** true면 수집 대신 현재 화면 노드 트리를 Logcat에 덤프(캘리브레이션). */
    const val CALIBRATE = false
}
