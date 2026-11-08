package com.example.gleo_type.event

import com.example.gleo_type.event.core.EventFactory
import com.example.gleo_type.event.core.MusicLabel
import com.example.gleo_type.event.core.VehicleLabel
import com.example.gleo_type.event.core.VehiclePayload
import com.example.gleo_type.event.json.EventJsonSerializer
import com.example.gleo_type.event.music.MusicEventMapper
import com.example.gleo_type.event.music.MusicSnapshot
import com.example.gleo_type.event.music.MusicSummaryDto
import com.example.gleo_type.event.music.RepeatModeValue
import com.example.gleo_type.event.send.EventPublisher
import com.example.gleo_type.event.send.MockEventSender
import com.example.gleo_type.event.vehicle.VehicleEventMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class MockEventSenderTest {

    private val logs = mutableListOf<String>()
    private val sender = MockEventSender(EventJsonSerializer(), logger = { logs += it })

    private val snapshot = MusicSnapshot(
        musicId = "music_001",
        durationMs = 152_823,
        currentPositionMs = 0,
        bufferedPositionMs = 30_000,
        isPlaying = true,
        repeatMode = RepeatModeValue.OFF,
        shuffleEnabled = false
    )

    private val factory = EventFactory(
        clock = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC)
    )

    @Test
    fun storesSentEventsInOrder_andLogsPrettyJson() = runBlocking {
        val events = listOf(
            factory.create(MusicEventMapper.state(MusicLabel.STARTED, snapshot)),
            factory.create(MusicEventMapper.state(MusicLabel.PAUSED, snapshot.copy(isPlaying = false))),
            factory.create(VehiclePayload(MusicSummaryDto.EMPTY, VehicleEventMapper.brake(true)!!))
        )

        events.forEach { assertTrue(sender.send(it).isSuccess) }

        assertEquals(events, sender.sentEvents)
        assertEquals(
            listOf(MusicLabel.STARTED, MusicLabel.PAUSED, VehicleLabel.BRAKE_CHANGED),
            sender.sentEvents.map { it.label }
        )
        assertEquals(3, sender.attemptCount)

        // pretty print + 문자열 타입 확인
        assertEquals(3, logs.size)
        assertTrue(logs[0].contains("\n  \"label\": \"STARTED\""))
        sender.sentPayloads.forEach { assertAllScalarsAreStringOrNull(TestJson.parse(it), "$") }
    }

    @Test
    fun eventIdIsUuid_andTimestampHasSeoulOffset() = runBlocking {
        sender.send(factory.create(MusicEventMapper.state(MusicLabel.STARTED, snapshot)))

        val event = sender.sentEvents.single()
        UUID.fromString(event.eventId)
        assertEquals("2026-11-05T19:00:00.000+09:00", event.timestamp)
    }

    @Test
    fun forceFailure_returnsFailure_andDoesNotStore() = runBlocking {
        sender.forceFailure = true

        val result = sender.send(factory.create(MusicEventMapper.state(MusicLabel.STARTED, snapshot)))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MockEventSender.ForcedFailureException)
        assertTrue(sender.sentEvents.isEmpty())
        assertEquals(1, sender.attemptCount)
        assertTrue(logs.single().startsWith("[SEND_FAILED] MUSIC.STARTED"))

        // 실패 해제 후 재전송은 성공
        sender.forceFailure = false
        assertTrue(sender.send(factory.create(MusicEventMapper.state(MusicLabel.STARTED, snapshot))).isSuccess)
        assertEquals(1, sender.sentEvents.size)
    }

    @Test
    fun publisher_reportsFailuresToCallback() = runBlocking {
        sender.forceFailure = true
        val failures = mutableListOf<String>()
        val job = Job()
        val publisher = EventPublisher(sender, CoroutineScope(job + Dispatchers.Unconfined)) { event, _ ->
            failures += event.label.code
        }

        publisher.publish(factory.create(MusicEventMapper.state(MusicLabel.STARTED, snapshot)))
        job.children.toList().joinAll()

        assertEquals(listOf("STARTED"), failures)
    }

    @Test
    fun clear_resetsState() = runBlocking {
        sender.send(factory.create(MusicEventMapper.state(MusicLabel.STARTED, snapshot)))
        sender.clear()
        assertTrue(sender.sentEvents.isEmpty())
        assertEquals(0, sender.attemptCount)
    }
}
