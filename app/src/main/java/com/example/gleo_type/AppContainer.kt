package com.example.gleo_type

import android.app.Application
import android.util.Log
import com.example.gleo_type.event.core.DriveSessionProvider
import com.example.gleo_type.event.core.EventFactory
import com.example.gleo_type.event.json.EventJsonSerializer
import com.example.gleo_type.event.music.MusicStateHolder
import com.example.gleo_type.event.send.EventPublisher
import com.example.gleo_type.event.send.EventSender
import com.example.gleo_type.event.send.MockEventSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 수동 DI 컨테이너. 앱 전체에서 하나만 존재한다.
 * EventSender 구현체 교체는 이 파일의 [eventSender] 한 줄에서만 한다.
 */
class AppContainer {

    val serializer = EventJsonSerializer()

    // TODO: 주행 세션 관리 기능이 생기면 여기서 연결
    private val driveSessionProvider = DriveSessionProvider { null }

    val eventFactory = EventFactory(sessionProvider = driveSessionProvider)

    /** 현재 음악 재생 상태 (MUSIC 외 domain의 music 요약에 사용) */
    val musicState = MusicStateHolder()

    // ★ 구현체 교체 지점: 서버가 정해지면 RemoteEventSender(endpoint, serializer)로 교체
    val eventSender: EventSender = MockEventSender(serializer)

    private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val eventPublisher = EventPublisher(eventSender, sendScope) { event, error ->
        Log.w(
            MockEventSender.TAG,
            "[PUBLISH_FAILED] ${event.domain}.${event.label.code} id=${event.eventId}: ${error.message}"
        )
    }
}

class GleoApplication : Application() {
    val container: AppContainer by lazy { AppContainer() }
}
