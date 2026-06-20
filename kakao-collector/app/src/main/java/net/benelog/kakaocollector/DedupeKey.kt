package net.benelog.kakaocollector

/**
 * 메시지 중복제거 키. 인메모리 seen 집합과 DB UNIQUE 의미를 한 곳에서 정의한다.
 * 서버 message_key와 동일하게 room을 포함하고 U+0001로 필드를 구분한다.
 */
object DedupeKey {
    private const val SEP = "\u0001"

    fun of(room: String, sender: String, text: String, clientTime: String): String =
        room + SEP + sender + SEP + text + SEP + clientTime
}
