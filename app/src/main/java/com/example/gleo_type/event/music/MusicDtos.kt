package com.example.gleo_type.event.music

/**
 * MUSIC domain 전체 음악 상태.
 * 모든 스칼라는 String?, 특정 label 전용 필드(from/to, error*)는 해당 이벤트가 아니면 null.
 */
data class MusicDto(
    val musicId: String?,
    val durationMs: String?,
    val currentPositionMs: String?,
    val remainingTimeMs: String?,
    val isPlaying: String?,
    val bufferedPositionMs: String?,
    val completionRate: String?,
    val repeatMode: String?,
    val shuffleEnabled: String?,
    val fromPositionMs: String?,
    val toPositionMs: String?,
    val errorCode: String?,
    val errorMessage: String?
)

/** MUSIC 외 domain에 포함되는 현재 재생 요약 */
data class MusicSummaryDto(
    val musicId: String?,
    val currentPositionMs: String?,
    val isPlaying: String?
) {
    companion object {
        /** 재생 중인 곡이 없을 때: 객체는 유지하고 값은 모두 null */
        val EMPTY = MusicSummaryDto(null, null, null)
    }
}
