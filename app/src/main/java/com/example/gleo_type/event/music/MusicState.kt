package com.example.gleo_type.event.music

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RepeatModeValue { OFF, ONE, ALL }

/**
 * Media3 Player 상태의 앱 내부 표현 (Media3 타입을 DTO까지 끌고 가지 않기 위함).
 * MusicPlaybackService에서 Player 값을 읽어 만든다.
 */
data class MusicSnapshot(
    val musicId: String,
    val durationMs: Long?,
    val currentPositionMs: Long,
    val bufferedPositionMs: Long,
    val isPlaying: Boolean,
    val repeatMode: RepeatModeValue,
    val shuffleEnabled: Boolean,
    val playbackSpeed: Float = 1f
) {
    /** 곡을 끝까지 재생한 상태 (COMPLETED / AUTO_TRANSITION) */
    fun asFinished(): MusicSnapshot {
        val duration = durationMs ?: return copy(isPlaying = false)
        return copy(
            currentPositionMs = duration,
            bufferedPositionMs = duration,
            isPlaying = false
        )
    }
}

/**
 * 현재 재생 상태 저장소 (프로세스 전역, AppContainer가 보관).
 *
 * MusicPlaybackService(메인 스레드)가 update()로 갱신하고,
 * 차량·내비 이벤트 Mapper(임의 스레드)가 currentSummary()로 읽는다.
 * Player는 메인 스레드에서만 접근 가능하므로, 재생 중이면 마지막 갱신 이후 경과 시간으로
 * 현재 위치를 추정한다.
 */
class MusicStateHolder(
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private data class Entry(val snapshot: MusicSnapshot, val capturedAtMs: Long)

    private val state = MutableStateFlow<Entry?>(null)
    private val snapshotFlow = MutableStateFlow<MusicSnapshot?>(null)

    /** 화면 등에서 구독할 수 있는 최신 스냅샷 */
    val snapshots: StateFlow<MusicSnapshot?> = snapshotFlow.asStateFlow()

    fun update(snapshot: MusicSnapshot?) {
        state.value = snapshot?.let { Entry(it, elapsedRealtime()) }
        snapshotFlow.value = snapshot
    }

    fun clear() = update(null)

    fun currentSnapshot(): MusicSnapshot? {
        val entry = state.value ?: return null
        val snapshot = entry.snapshot
        if (!snapshot.isPlaying) return snapshot

        val elapsed = (elapsedRealtime() - entry.capturedAtMs).coerceAtLeast(0L)
        var position = snapshot.currentPositionMs + (elapsed * snapshot.playbackSpeed).toLong()
        snapshot.durationMs?.let { position = position.coerceAtMost(it) }
        return snapshot.copy(currentPositionMs = position)
    }

    fun currentSummary(): MusicSummaryDto = MusicEventMapper.toSummary(currentSnapshot())
}
