package com.example.gleo_type.event.send

import com.example.gleo_type.event.core.EventDto

/**
 * 서버 전송 추상화.
 *
 * 현재 구현체: [MockEventSender] (네트워크 호출 없음).
 *
 * RemoteEventSender를 붙일 때:
 * 1. 이 패키지에 `class RemoteEventSender(endpoint, serializer) : EventSender` 추가
 * 2. send()에서 withContext(Dispatchers.IO)로 serializer.serialize(event)를 POST
 *    (기존 vehicle/VehicleEventSender.post()의 HttpURLConnection 코드를 그대로 옮겨도 됨)
 *    2xx → Result.success, 그 외/예외 → Result.failure
 * 3. 오프라인 대비가 필요하면 실패 시 기존 EventStore(SQLite)에 적재하고 재전송
 * 4. AppContainer.eventSender 한 줄만 RemoteEventSender로 교체
 */
interface EventSender {
    suspend fun send(event: EventDto): Result<Unit>
}
