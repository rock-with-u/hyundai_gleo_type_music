package com.example.gleo_type.event

import com.example.gleo_type.event.core.DestinationPayload
import com.example.gleo_type.event.core.EventDto
import com.example.gleo_type.event.core.EventFactory
import com.example.gleo_type.event.core.EventPayload
import com.example.gleo_type.event.json.EventJsonSerializer
import com.example.gleo_type.event.music.MusicSnapshot
import com.example.gleo_type.event.music.MusicStateHolder
import com.example.gleo_type.event.music.RepeatModeValue
import com.example.gleo_type.event.send.EventPublisher
import com.example.gleo_type.event.send.MockEventSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 이벤트 소스 테스트용 조립:
 * MusicStateHolder(가짜 시계) → EventFactory(고정 시각) → EventPublisher(Unconfined, 동기) → MockEventSender
 */
class SourceTestHarness {

    /** 가짜 elapsedRealtime (ms). 스로틀·재생 위치 추정에 공통으로 사용 */
    var now = 100_000L

    val clock: () -> Long = { now }

    val musicState = MusicStateHolder(elapsedRealtime = clock)

    val factory = EventFactory(
        clock = Clock.fixed(Instant.parse("2026-11-03T13:45:30.123Z"), ZoneOffset.UTC),
        sessionProvider = { "11" }
    )

    val sender = MockEventSender(EventJsonSerializer(), logger = {})

    val publisher = EventPublisher(sender, CoroutineScope(Dispatchers.Unconfined))

    val events: List<EventDto> get() = sender.sentEvents

    val payloads: List<EventPayload> get() = events.map { it.payload }

    inline fun <reified T : EventPayload> payloadsOf(): List<T> = payloads.filterIsInstance<T>()

    fun destinations(): List<DestinationPayload> = payloadsOf()

    fun playMusic(musicId: String = "101", positionMs: Long = 65_000, playing: Boolean = true) {
        musicState.update(
            MusicSnapshot(
                musicId = musicId,
                durationMs = 200_000,
                currentPositionMs = positionMs,
                bufferedPositionMs = positionMs,
                isPlaying = playing,
                repeatMode = RepeatModeValue.OFF,
                shuffleEnabled = false
            )
        )
    }

    /** 모든 이벤트가 JSON으로 직렬화되고 스칼라가 전부 문자열/null인지 */
    fun assertAllSerializeAsStrings() {
        sender.sentPayloads.forEach { assertAllScalarsAreStringOrNull(TestJson.parse(it), "$") }
    }
}
