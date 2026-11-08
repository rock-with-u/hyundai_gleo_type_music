package com.example.gleo_type.event

import com.example.gleo_type.event.core.EventDomain
import com.example.gleo_type.event.core.MusicLabel
import com.example.gleo_type.event.core.MusicPayload
import com.example.gleo_type.event.json.EventJsonSerializer
import com.example.gleo_type.event.music.MusicEventMapper
import com.example.gleo_type.event.music.MusicSnapshot
import com.example.gleo_type.event.music.MusicStateHolder
import com.example.gleo_type.event.music.MusicSummaryDto
import com.example.gleo_type.event.music.RepeatModeValue
import org.junit.Assert.assertEquals
import org.junit.Test

/** 스펙 MUSIC 예시 12종(문법 오류 수정본)과 직렬화 결과 비교 */
class MusicEventSerializationTest {

    private val fixtures = Fixtures.load("music.json")

    private fun song1(position: Long, buffered: Long, playing: Boolean) = MusicSnapshot(
        musicId = "music_001",
        durationMs = 152_823,
        currentPositionMs = position,
        bufferedPositionMs = buffered,
        isPlaying = playing,
        repeatMode = RepeatModeValue.OFF,
        shuffleEnabled = false
    )

    private fun song2(position: Long, buffered: Long, playing: Boolean) = MusicSnapshot(
        musicId = "music_002",
        durationMs = 126_842,
        currentPositionMs = position,
        bufferedPositionMs = buffered,
        isPlaying = playing,
        repeatMode = RepeatModeValue.OFF,
        shuffleEnabled = false
    )

    private fun check(label: MusicLabel, payload: MusicPayload) {
        val fixture = Fixtures.byLabel(fixtures, label.name)
        val event = createEvent(fixture, payload)
        assertEquals(EventDomain.MUSIC, event.domain)
        assertMatchesFixture(event, fixture)
    }

    @Test
    fun started() = check(
        MusicLabel.STARTED,
        MusicEventMapper.state(MusicLabel.STARTED, song1(0, 30_000, true))
    )

    @Test
    fun resumed() = check(
        MusicLabel.RESUMED,
        MusicEventMapper.state(MusicLabel.RESUMED, song1(60_000, 90_000, true))
    )

    @Test
    fun paused() = check(
        MusicLabel.PAUSED,
        MusicEventMapper.state(MusicLabel.PAUSED, song1(80_000, 110_000, false))
    )

    @Test
    fun completed_normalizesToEndOfTrack() = check(
        MusicLabel.COMPLETED,
        // 실제 콜백 시점 값(끝 직전, 재생 중)이어도 끝까지 재생한 상태로 정규화
        MusicEventMapper.state(MusicLabel.COMPLETED, song1(152_500, 152_000, true))
    )

    @Test
    fun skipped() = check(
        MusicLabel.SKIPPED,
        MusicEventMapper.state(MusicLabel.SKIPPED, song1(80_000, 110_000, true))
    )

    @Test
    fun autoTransition_normalizesToEndOfTrack() = check(
        MusicLabel.AUTO_TRANSITION,
        MusicEventMapper.state(MusicLabel.AUTO_TRANSITION, song1(152_700, 152_823, true))
    )

    @Test
    fun unknownTransition() = check(
        MusicLabel.UNKNOWN_TRANSITION,
        MusicEventMapper.state(MusicLabel.UNKNOWN_TRANSITION, song2(50_000, 80_000, true))
    )

    @Test
    fun seeked() = check(
        MusicLabel.SEEKED,
        MusicEventMapper.seeked(song2(90_000, 110_000, true), fromPositionMs = 80_000, toPositionMs = 90_000)
    )

    @Test
    fun buffering() = check(
        MusicLabel.BUFFERING,
        MusicEventMapper.state(MusicLabel.BUFFERING, song2(90_000, 95_000, false))
    )

    @Test
    fun error() = check(
        MusicLabel.ERROR,
        MusicEventMapper.error(song2(90_000, 95_000, false), errorCode = 2000, errorMessage = "Playback failed")
    )

    @Test
    fun repeatModeChanged() = check(
        MusicLabel.REPEAT_MODE_CHANGED,
        MusicEventMapper.state(
            MusicLabel.REPEAT_MODE_CHANGED,
            song1(706, 52_401, true).copy(repeatMode = RepeatModeValue.ONE)
        )
    )

    @Test
    fun shuffleModeChanged() = check(
        MusicLabel.SHUFFLE_MODE_CHANGED,
        MusicEventMapper.state(
            MusicLabel.SHUFFLE_MODE_CHANGED,
            song1(62_333, 104_855, true).copy(shuffleEnabled = true)
        )
    )

    @Test
    fun everyLabelHasFixture() {
        assertEquals(
            MusicLabel.entries.map { it.name }.toSet(),
            fixtures.map { it["label"] }.toSet()
        )
    }

    @Test
    fun labelSpecificFields_areExplicitNull_onOtherEvents() {
        val fixture = Fixtures.byLabel(fixtures, "PAUSED")
        val event = createEvent(fixture, MusicEventMapper.state(MusicLabel.PAUSED, song1(1, 1, false)))
        val json = EventJsonSerializer().serialize(event)

        assertExplicitNull(json, "music", "fromPositionMs")
        assertExplicitNull(json, "music", "toPositionMs")
        assertExplicitNull(json, "music", "errorCode")
        assertExplicitNull(json, "music", "errorMessage")
        assertExplicitNull(json, "driveSessionId")
    }

    @Test
    fun unknownDuration_givesNullRemainingAndRate() {
        val payload = MusicEventMapper.state(
            MusicLabel.STARTED,
            song1(1_000, 0, true).copy(durationMs = null)
        )
        assertEquals(null, payload.music.durationMs)
        assertEquals(null, payload.music.remainingTimeMs)
        assertEquals(null, payload.music.completionRate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun seekedRequiresDedicatedFactory() {
        MusicEventMapper.state(MusicLabel.SEEKED, song1(0, 0, true))
    }

    @Test
    fun stateHolder_extrapolatesPositionWhilePlaying() {
        var now = 10_000L
        val holder = MusicStateHolder(elapsedRealtime = { now })

        assertEquals(MusicSummaryDto.EMPTY, holder.currentSummary())

        holder.update(song1(60_000, 90_000, true))
        now += 5_000
        assertEquals(MusicSummaryDto("music_001", "65000", "true"), holder.currentSummary())

        holder.update(song1(70_000, 90_000, false))
        now += 5_000
        assertEquals(MusicSummaryDto("music_001", "70000", "false"), holder.currentSummary())
    }
}
