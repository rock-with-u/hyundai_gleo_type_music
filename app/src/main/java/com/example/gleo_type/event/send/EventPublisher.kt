package com.example.gleo_type.event.send

import com.example.gleo_type.event.core.EventDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * SDK 콜백(일반 함수)에서 suspend send()를 호출하기 위한 연결부.
 * 콜백 스레드를 막지 않도록 scope에서 비동기로 전송한다.
 */
class EventPublisher(
    private val sender: EventSender,
    private val scope: CoroutineScope,
    private val onFailure: (EventDto, Throwable) -> Unit = { _, _ -> }
) {
    fun publish(event: EventDto) {
        scope.launch {
            sender.send(event).onFailure { error -> onFailure(event, error) }
        }
    }
}
