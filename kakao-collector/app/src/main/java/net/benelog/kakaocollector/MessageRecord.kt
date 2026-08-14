package net.benelog.kakaocollector

import org.json.JSONObject

/**
 * 수집 메시지 1건 — 로컬 저장([MessageStore])과 서버 전송([ModalApi.ingest])의 공용 단위.
 *
 * [clientTime]은 발신 '날짜'(YYYY-MM-DD, 미상이면 "") — 카톡이 분 단위 시각을 접근성에
 * 노출하지 않아 날짜 구분선으로만 잡는다. [sentTime]은 발신 시각(HH:MM, 미상이면 "").
 */
data class MessageRecord(
    val room: String,
    val sender: String,
    val text: String,
    val clientTime: String = "",
    val sentTime: String = "",
) {
    /** /ingest 요청 본문. 서버 필드명 ts == client_time(발신 날짜). */
    fun toJson(): JSONObject = JSONObject()
        .put("room", room)
        .put("sender", sender)
        .put("text", text)
        .put("ts", clientTime)
        .put("sent_time", sentTime)
}
