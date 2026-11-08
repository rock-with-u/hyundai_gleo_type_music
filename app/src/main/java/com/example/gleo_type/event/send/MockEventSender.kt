package com.example.gleo_type.event.send

import android.util.Log
import com.example.gleo_type.event.core.EventDto
import com.example.gleo_type.event.json.EventJsonSerializer
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 네트워크 호출 없이 전송을 흉내 내는 구현체.
 * - 직렬화된 JSON을 Logcat(tag = "EventSender")에 pretty print
 * - 성공한 이벤트를 메모리에 보관 (테스트 검증용)
 * - [forceFailure] = true 면 Result.failure 반환 (실패 흐름 확인용)
 */
class MockEventSender(
    private val serializer: EventJsonSerializer,
    @Volatile var forceFailure: Boolean = false,
    private val logger: (String) -> Unit = ::logToLogcat
) : EventSender {

    companion object {
        const val TAG = "EventSender"
        private const val LOG_CHUNK = 3500

        private fun logToLogcat(message: String) {
            // Logcat 한 줄 길이 제한(~4KB) 대비 분할 출력
            message.chunked(LOG_CHUNK).forEach { Log.i(TAG, it) }
        }
    }

    class ForcedFailureException(eventId: String) :
        IOException("MockEventSender forced failure: $eventId")

    private val sent = CopyOnWriteArrayList<EventDto>()
    private val sentJson = CopyOnWriteArrayList<String>()
    private val attempts = AtomicInteger(0)

    /** 전송에 성공한 이벤트 (전송 순서) */
    val sentEvents: List<EventDto> get() = sent.toList()

    /** 전송에 성공한 이벤트의 JSON (pretty) */
    val sentPayloads: List<String> get() = sentJson.toList()

    /** 성공·실패를 포함한 send() 호출 횟수 */
    val attemptCount: Int get() = attempts.get()

    override suspend fun send(event: EventDto): Result<Unit> {
        attempts.incrementAndGet()
        val json = serializer.serialize(event, pretty = true)

        if (forceFailure) {
            logger("[SEND_FAILED] ${event.domain}.${event.label.code} id=${event.eventId}")
            return Result.failure(ForcedFailureException(event.eventId))
        }

        sent.add(event)
        sentJson.add(json)
        logger("[SENT] ${event.domain}.${event.label.code}\n$json")
        return Result.success(Unit)
    }

    fun clear() {
        sent.clear()
        sentJson.clear()
        attempts.set(0)
    }
}
