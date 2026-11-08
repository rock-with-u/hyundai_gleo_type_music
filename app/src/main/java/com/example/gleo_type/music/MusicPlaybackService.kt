
package com.example.gleo_type.music

import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.example.gleo_type.GleoApplication
import com.example.gleo_type.R
import com.example.gleo_type.event.core.EventPayload
import com.example.gleo_type.event.core.MusicLabel
import com.example.gleo_type.event.music.MusicEventMapper
import com.example.gleo_type.event.music.MusicSnapshot
import com.example.gleo_type.event.music.RepeatModeValue
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class MusicPlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "GleoMusic"
        private const val EVENT_TAG = "GleoMusicEvent"
        private const val ROOT_ID = "gleo_root"
    }

    private lateinit var player: ExoPlayer
    private lateinit var librarySession: MediaLibrarySession
    private lateinit var songs: List<MediaItem>

    private val container by lazy {
        (application as GleoApplication).container
    }

    private var currentSongId: String? = null
    private var hasStartedCurrentSong = false
    private var pausedByUser = false
    private var bufferingReported = false
    private var completedCurrentSong = false
    private var previousIsPlaying = false

    // 마지막으로 확인한 음악 상태 (갱신 시 MusicStateHolder에도 반영)
    private var lastSnapshot: MusicSnapshot? = null
        set(value) {
            field = value
            container.musicState.update(value)
        }

    // 곡 이동 직전 위치 보존
    private var transitionOldPosition: Long? = null

    // ==========================================
    // 서비스 생성
    // ==========================================

    override fun onCreate() {
        super.onCreate()

        songs = listOf(
            createSong(
                "music_001",
                "Gleo Test Music",
                "Local Audio",
                R.raw.sample_music
            ),
            createSong(
                "music_002",
                "Sample 2",
                "Burgundy",
                R.raw.sample_2
            ),
            createSong(
                "music_003",
                "Sample 3",
                "Walen",
                R.raw.sample_3
            ),
            createSong(
                "music_004",
                "Sample 4",
                "AgusAlvarez, Waesto",
                R.raw.sample_4
            ),
            createSong(
                "music_005",
                "Sample 5",
                "Dagored",
                R.raw.sample_5
            ),
            createSong(
                "music_006",
                "Sample 6",
                "Dagored",
                R.raw.sample_6
            ),
            createSong(
                "music_007",
                "Sample 7",
                "Walen",
                R.raw.sample_7
            ),
            createSong(
                "music_008",
                "Sample 8",
                "Dagored",
                R.raw.sample_8
            )
        )

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(
                        C.AUDIO_CONTENT_TYPE_MUSIC
                    )
                    .build(),
                true
            )
            .build()

        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false

        player.addListener(createPlayerListener())

        librarySession = MediaLibrarySession.Builder(
            this,
            player,
            MusicLibraryCallback()
        ).build()

        Log.i(
            TAG,
            "[SERVICE_CREATED] songs=${songs.size}"
        )
    }

    // ==========================================
    // Media3 이벤트 감지
    // ==========================================

    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val oldIndex = oldPosition.mediaItemIndex
                val newIndex = newPosition.mediaItemIndex

                // 다른 곡으로 이동하기 전 위치 보관
                if (oldIndex != newIndex) {
                    transitionOldPosition =
                        oldPosition.positionMs.coerceAtLeast(0L)
                }

                // 동일 곡 안에서 재생 위치 이동
                if (
                    reason == Player.DISCONTINUITY_REASON_SEEK &&
                    oldIndex == newIndex
                ) {
                    val from = oldPosition.positionMs
                        .coerceAtLeast(0L)

                    val to = newPosition.positionMs
                        .coerceAtLeast(0L)

                    if (from != to) {
                        emitEvent(
                            label = MusicLabel.SEEKED,
                            snapshot = captureSnapshot(),
                            fromPositionMs = from,
                            toPositionMs = to
                        )
                    }
                }
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                val old = lastSnapshot
                val newId = mediaItem?.mediaId

                // 이전 곡을 기준으로 전환 이벤트 생성
                if (old != null && newId != null) {

                    val oldPosition =
                        transitionOldPosition
                            ?: old.currentPositionMs

                    val oldSnapshot = old.copy(
                        currentPositionMs = oldPosition
                    )

                    when (reason) {

                        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                            emitCompleted(oldSnapshot)

                            emitEvent(
                                MusicLabel.AUTO_TRANSITION,
                                oldSnapshot
                            )
                        }

                        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                            // 한 곡 반복도 정상적으로
                            // 끝까지 재생된 뒤 다시 시작
                            emitCompleted(oldSnapshot)

                            emitEvent(
                                MusicLabel.AUTO_TRANSITION,
                                oldSnapshot
                            )
                        }

                        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                            emitEvent(
                                MusicLabel.SKIPPED,
                                oldSnapshot
                            )
                        }

                        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> {
                            emitEvent(
                                MusicLabel.UNKNOWN_TRANSITION,
                                oldSnapshot
                            )
                        }
                    }
                }

                transitionOldPosition = null

                // 새 곡 재생 상태 초기화
                currentSongId = newId
                hasStartedCurrentSong = false
                pausedByUser = false
                bufferingReported = false
                completedCurrentSong = false
                previousIsPlaying = false

                lastSnapshot = captureSnapshot()

                Log.d(
                    TAG,
                    "[MUSIC_CHANGED] $newId reason=$reason"
                )
            }

            override fun onEvents(
                player: Player,
                events: Player.Events
            ) {
                handlePlaybackState(events)

                // 반복 모드 변경
                if (
                    events.contains(
                        Player.EVENT_REPEAT_MODE_CHANGED
                    )
                ) {
                    emitEvent(
                        MusicLabel.REPEAT_MODE_CHANGED,
                        captureSnapshot()
                    )
                }

                // 셔플 모드 변경
                if (
                    events.contains(
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
                    )
                ) {
                    emitEvent(
                        MusicLabel.SHUFFLE_MODE_CHANGED,
                        captureSnapshot()
                    )
                }

                lastSnapshot = captureSnapshot()
            }

            override fun onPlayerError(
                error: PlaybackException
            ) {
                emitEvent(
                    label = MusicLabel.ERROR,
                    snapshot = captureSnapshot(),
                    errorCode = error.errorCode,
                    errorMessage = error.message
                )

                Log.e(TAG, "[PLAYER_ERROR]", error)
            }
        }
    }

    // ==========================================
    // STARTED / RESUMED / PAUSED
    // BUFFERING / COMPLETED 판별
    // ==========================================

    private fun handlePlaybackState(events: Player.Events) {

        val snapshot = captureSnapshot() ?: return

        // 새로운 곡이 확인되면 상태 초기화
        if (currentSongId != snapshot.musicId) {
            currentSongId = snapshot.musicId
            hasStartedCurrentSong = false
            pausedByUser = false
            bufferingReported = false
            completedCurrentSong = false
            previousIsPlaying = false
        }

        val state = player.playbackState
        val isPlaying = player.isPlaying

        // 재생 중 버퍼링
        if (
            state == Player.STATE_BUFFERING &&
            player.playWhenReady &&
            hasStartedCurrentSong &&
            !bufferingReported
        ) {
            emitEvent(
                MusicLabel.BUFFERING,
                snapshot
            )

            bufferingReported = true
        }

        if (state != Player.STATE_BUFFERING) {
            bufferingReported = false
        }

        // 새로운 곡 첫 재생
        if (isPlaying && !hasStartedCurrentSong) {
            hasStartedCurrentSong = true
            pausedByUser = false
            completedCurrentSong = false

            emitEvent(
                MusicLabel.STARTED,
                snapshot
            )
        }

        // 일시정지 후 재개
        else if (isPlaying && pausedByUser) {
            pausedByUser = false

            emitEvent(
                MusicLabel.RESUMED,
                snapshot
            )
        }

        // 명시적인 일시정지
        if (
            previousIsPlaying &&
            !isPlaying &&
            !player.playWhenReady &&
            state != Player.STATE_ENDED
        ) {
            pausedByUser = true

            emitEvent(
                MusicLabel.PAUSED,
                snapshot
            )
        }

        // 마지막 곡의 정상 종료
        if (
            state == Player.STATE_ENDED &&
            hasStartedCurrentSong &&
            !completedCurrentSong
        ) {
            emitCompleted(snapshot)
        }

        previousIsPlaying = isPlaying
    }

    // ==========================================
    // 곡 정상 완료 (중복 방지)
    // ==========================================

    private fun emitCompleted(snapshot: MusicSnapshot) {
        if (completedCurrentSong) {
            return
        }

        // 위치=duration, isPlaying=false 정규화는 MusicEventMapper가 처리
        emitEvent(
            MusicLabel.COMPLETED,
            snapshot
        )

        completedCurrentSong = true
    }

    // ==========================================
    // 음악 상태 스냅샷
    // ==========================================

    private fun captureSnapshot(): MusicSnapshot? {
        val item = player.currentMediaItem ?: return null

        val rawDuration = player.duration

        val duration = if (
            rawDuration > 0L &&
            rawDuration != C.TIME_UNSET
        ) {
            rawDuration
        } else {
            null
        }

        return MusicSnapshot(
            musicId = item.mediaId,
            durationMs = duration,
            currentPositionMs =
                player.currentPosition.coerceAtLeast(0L),
            bufferedPositionMs =
                player.bufferedPosition.coerceAtLeast(0L),
            isPlaying = player.isPlaying,
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatModeValue.ONE
                Player.REPEAT_MODE_ALL -> RepeatModeValue.ALL
                else -> RepeatModeValue.OFF
            },
            shuffleEnabled = player.shuffleModeEnabled,
            playbackSpeed = player.playbackParameters.speed
        )
    }

    // ==========================================
    // Mapper → 이벤트 DTO → EventSender
    // ==========================================

    private fun emitEvent(
        label: MusicLabel,
        snapshot: MusicSnapshot?,
        fromPositionMs: Long? = null,
        toPositionMs: Long? = null,
        errorCode: Int? = null,
        errorMessage: String? = null
    ) {
        if (snapshot == null) {
            Log.w(
                TAG,
                "[EVENT_IGNORED] $label 음악 정보 없음"
            )
            return
        }

        val payload: EventPayload = when (label) {
            MusicLabel.SEEKED -> MusicEventMapper.seeked(
                snapshot,
                fromPositionMs ?: snapshot.currentPositionMs,
                toPositionMs ?: snapshot.currentPositionMs
            )

            MusicLabel.ERROR -> MusicEventMapper.error(
                snapshot,
                errorCode ?: 0,
                errorMessage
            )

            else -> MusicEventMapper.state(label, snapshot)
        }

        val event = container.eventFactory.create(payload)

        Log.d(EVENT_TAG, "[MUSIC_EVENT] ${event.label.code} ${event.eventId}")

        container.eventPublisher.publish(event)
    }

    // ==========================================
    // 음악 목록 구성
    // ==========================================

    private fun createSong(
        id: String,
        title: String,
        artist: String,
        rawId: Int
    ): MediaItem {

        val uri = Uri.parse(
            "android.resource://$packageName/$rawId"
        )

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun rootItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Gleo Music")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun searchSongs(query: String): List<MediaItem> {
        return songs.filter { item ->

            val title = item.mediaMetadata.title
                ?.toString().orEmpty()

            val artist = item.mediaMetadata.artist
                ?.toString().orEmpty()

            title.contains(query, ignoreCase = true) ||
                    artist.contains(query, ignoreCase = true)
        }
    }

    // ==========================================
    // MediaLibrarySession Callback
    // ==========================================

    private inner class MusicLibraryCallback :
        MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {

            return Futures.immediateFuture(
                LibraryResult.ofItem(rootItem(), params)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<
                LibraryResult<ImmutableList<MediaItem>>
                > {

            if (parentId != ROOT_ID) {
                return Futures.immediateFuture(
                    LibraryResult.ofError(
                        LibraryResult.RESULT_ERROR_BAD_VALUE
                    )
                )
            }

            val start = (page.toLong() * pageSize)
                .coerceAtMost(songs.size.toLong())
                .toInt()

            val end = (start.toLong() + pageSize)
                .coerceAtMost(songs.size.toLong())
                .toInt()

            return Futures.immediateFuture(
                LibraryResult.ofItemList(
                    songs.subList(start, end),
                    params
                )
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {

            val item = if (mediaId == ROOT_ID) {
                rootItem()
            } else {
                songs.find {
                    it.mediaId == mediaId
                }
            }

            if (item == null) {
                return Futures.immediateFuture(
                    LibraryResult.ofError(
                        LibraryResult.RESULT_ERROR_BAD_VALUE
                    )
                )
            }

            return Futures.immediateFuture(
                LibraryResult.ofItem(item, null)
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {

            val count = searchSongs(query).size

            session.notifySearchResultChanged(
                browser,
                query,
                count,
                params
            )

            return Futures.immediateFuture(
                LibraryResult.ofVoid()
            )
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<
                LibraryResult<ImmutableList<MediaItem>>
                > {

            val results = searchSongs(query)

            val start = (page.toLong() * pageSize)
                .coerceAtMost(results.size.toLong())
                .toInt()

            val end = (start.toLong() + pageSize)
                .coerceAtMost(results.size.toLong())
                .toInt()

            return Futures.immediateFuture(
                LibraryResult.ofItemList(
                    results.subList(start, end),
                    params
                )
            )
        }

        // ID만 전달받은 음악을 실제 재생 URI로 변환
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {

            val resolved = mediaItems.mapNotNull { requested ->
                songs.find {
                    it.mediaId == requested.mediaId
                }
            }

            return Futures.immediateFuture(resolved)
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession {
        return librarySession
    }

    override fun onDestroy() {

        librarySession.release()
        player.release()
        container.musicState.clear()

        Log.i(TAG, "[SERVICE_DESTROYED]")

        super.onDestroy()
    }
}
