package net.benelog.kakaocollector

/**
 * 삽입 순서를 기억하는 상한 있는 문자열 키 집합. 상한을 넘으면 가장 오래된 키부터 버린다.
 * 서비스의 '이미 처리한 키' 캐시(수집 dedupe, 알림 dedupe)가 무한히 자라지 않게 하는 용도.
 */
class BoundedKeySet(private val cap: Int) {
    private val keys = LinkedHashSet<String>()

    operator fun contains(key: String): Boolean = key in keys

    /** 처음 보는 키면 기억하고 true, 이미 있으면 false. */
    fun add(key: String): Boolean {
        if (!keys.add(key)) return false
        if (keys.size > cap) {
            val it = keys.iterator()
            it.next()
            it.remove()
        }
        return true
    }

    fun addAll(newKeys: Collection<String>) = newKeys.forEach { add(it) }
}
