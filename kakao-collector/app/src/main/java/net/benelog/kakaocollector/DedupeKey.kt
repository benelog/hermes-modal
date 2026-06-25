package net.benelog.kakaocollector

/**
 * 메시지 중복제거 키. 인메모리 seen 집합과 dedupe 의미를 한 곳에서 정의한다.
 * 서버 message_key 와 동일하게 room 을 포함하고 U+0001 로 필드를 구분한다.
 *
 * sender 는 키에서 **제외**한다: 보낸이 추정은 휴리스틱이라 스크랩마다 흔들릴 수 있어
 * (닉네임이 화면 밖으로 스크롤되거나, 내/남 말풍선 경계가 애매한 경우) 키에 넣으면
 * 한 메시지가 추정된 보낸이마다 따로 저장돼 중복이 된다. 빼면 오귀속된 재수집이
 * 원본과 충돌해 한 건으로 합쳐진다. client_time(일 단위 날짜)으로 같은 본문·다른 날을 구분.
 */
object DedupeKey {
    private const val SEP = "\u0001"

    fun of(room: String, text: String, clientTime: String): String =
        room + SEP + text + SEP + clientTime
}
