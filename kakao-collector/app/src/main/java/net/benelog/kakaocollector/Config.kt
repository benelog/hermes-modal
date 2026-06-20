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

    /** 방 제목 노드 id(방 식별 보조). (실측: 방 상단 제목은 id/name 노드에 들어감) */
    const val TITLE_ID = "com.kakao.talk:id/name"

    /** true면 수집 대신 현재 화면 노드 트리를 Logcat에 덤프(캘리브레이션). */
    const val CALIBRATE = false
}
