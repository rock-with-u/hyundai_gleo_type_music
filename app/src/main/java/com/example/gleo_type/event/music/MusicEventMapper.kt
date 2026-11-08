package com.example.gleo_type.event.music

import com.example.gleo_type.event.core.MusicLabel
import com.example.gleo_type.event.core.MusicPayload
import com.example.gleo_type.event.format.DecimalPlaces
import com.example.gleo_type.event.format.toEventString

/**
 * MusicSnapshot → MUSIC payload.
 *
 * 계산 필드
 * - remainingTimeMs = max(0, duration - position)
 * - completionRate  = clamp(position / duration, 0, 1)
 * duration을 모르면 두 값 모두 null.
 */
object MusicEventMapper {

    /** SEEKED / ERROR 이외의 label */
    fun state(label: MusicLabel, snapshot: MusicSnapshot): MusicPayload {
        require(label != MusicLabel.SEEKED && label != MusicLabel.ERROR) {
            "Use seeked() / error() for $label"
        }
        val normalized = when (label) {
            MusicLabel.COMPLETED, MusicLabel.AUTO_TRANSITION -> snapshot.asFinished()
            else -> snapshot
        }
        return MusicPayload(label, toDto(normalized))
    }

    fun seeked(snapshot: MusicSnapshot, fromPositionMs: Long, toPositionMs: Long): MusicPayload =
        MusicPayload(
            MusicLabel.SEEKED,
            toDto(snapshot).copy(
                fromPositionMs = fromPositionMs.toEventString(),
                toPositionMs = toPositionMs.toEventString()
            )
        )

    fun error(snapshot: MusicSnapshot, errorCode: Int, errorMessage: String?): MusicPayload =
        MusicPayload(
            MusicLabel.ERROR,
            toDto(snapshot).copy(
                errorCode = errorCode.toEventString(),
                errorMessage = errorMessage
            )
        )

    fun toSummary(snapshot: MusicSnapshot?): MusicSummaryDto {
        if (snapshot == null) return MusicSummaryDto.EMPTY
        return MusicSummaryDto(
            musicId = snapshot.musicId,
            currentPositionMs = snapshot.currentPositionMs.toEventString(),
            isPlaying = snapshot.isPlaying.toEventString()
        )
    }

    private fun toDto(snapshot: MusicSnapshot): MusicDto {
        val duration = snapshot.durationMs?.takeIf { it > 0L }
        val position = snapshot.currentPositionMs.coerceAtLeast(0L)

        val remaining = duration?.let { (it - position).coerceAtLeast(0L) }
        val rate = duration?.let { (position.toDouble() / it).coerceIn(0.0, 1.0) }

        return MusicDto(
            musicId = snapshot.musicId,
            durationMs = duration.toEventString(),
            currentPositionMs = position.toEventString(),
            remainingTimeMs = remaining.toEventString(),
            isPlaying = snapshot.isPlaying.toEventString(),
            bufferedPositionMs = snapshot.bufferedPositionMs.coerceAtLeast(0L).toEventString(),
            completionRate = rate.toEventString(DecimalPlaces.COMPLETION_RATE),
            repeatMode = snapshot.repeatMode.name,
            shuffleEnabled = snapshot.shuffleEnabled.toEventString(),
            fromPositionMs = null,
            toPositionMs = null,
            errorCode = null,
            errorMessage = null
        )
    }
}
