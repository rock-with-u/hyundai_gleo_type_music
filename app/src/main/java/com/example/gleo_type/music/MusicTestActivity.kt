
package com.example.gleo_type.music

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

class MusicTestActivity : Activity() {

    companion object {
        private const val TAG = "GleoMusic"
    }

    private var controllerFuture:
            ListenableFuture<MediaController>? = null

    private var controller: MediaController? = null

    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var stateText: TextView
    private lateinit var positionText: TextView

    private lateinit var playButton: Button
    private lateinit var repeatButton: Button
    private lateinit var shuffleButton: Button

    // ==========================================
    // 테스트 음악 목록
    // ==========================================

    private val musicIds = listOf(
        "music_001",
        "music_002",
        "music_003",
        "music_004",
        "music_005",
        "music_006",
        "music_007",
        "music_008"
    )

    private val handler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateState()
            handler.postDelayed(this, 500L)
        }
    }

    private val playerListener = object : Player.Listener {

        override fun onEvents(
            player: Player,
            events: Player.Events
        ) {
            updateState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createScreen()
    }

    // ==========================================
    // UI 생성
    // ==========================================

    private fun createScreen() {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 32, 32, 32)
        }

        titleText = TextView(this).apply {
            text = "Gleo Music"
            textSize = 25f
            gravity = Gravity.CENTER
        }

        artistText = TextView(this).apply {
            text = "아티스트"
            textSize = 17f
            gravity = Gravity.CENTER
        }

        stateText = TextView(this).apply {
            text = "서비스 연결 중..."
            textSize = 15f
            gravity = Gravity.CENTER
        }

        positionText = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 18f
            gravity = Gravity.CENTER
        }

        // ======================================
        // 재생 / 일시정지
        // ======================================

        playButton = createButton("재생") {
            controller?.let { music ->

                if (music.isPlaying) {
                    music.pause()
                } else {

                    if (
                        music.playbackState ==
                        Player.STATE_IDLE
                    ) {
                        music.prepare()
                    }

                    if (
                        music.playbackState ==
                        Player.STATE_ENDED
                    ) {
                        music.seekToDefaultPosition(0)
                    }

                    music.play()
                }
            }
        }

        // ======================================
        // 이전 곡
        // ======================================

        val previousButton = createButton("이전 곡") {
            controller?.let { music ->

                if (music.hasPreviousMediaItem()) {
                    music.seekToPreviousMediaItem()
                }
            }
        }

        // ======================================
        // 다음 곡
        // ======================================

        val nextButton = createButton("다음 곡") {
            controller?.let { music ->

                if (music.hasNextMediaItem()) {
                    music.seekToNextMediaItem()
                }
            }
        }

        // ======================================
        // 10초 뒤로 이동
        // ======================================

        val rewindButton = createButton("-10초") {
            controller?.let { music ->

                val position =
                    (music.currentPosition - 10_000L)
                        .coerceAtLeast(0L)

                music.seekTo(position)
            }
        }

        // ======================================
        // 10초 앞으로 이동
        // ======================================

        val forwardButton = createButton("+10초") {
            controller?.let { music ->

                var position =
                    music.currentPosition + 10_000L

                val duration = music.duration

                if (
                    duration > 0L &&
                    duration != C.TIME_UNSET
                ) {
                    position =
                        position.coerceAtMost(duration)
                }

                music.seekTo(position)
            }
        }

        // ======================================
        // 정지
        // ======================================

        val stopButton = createButton("정지") {
            controller?.stop()
        }

        // ======================================
        // 반복 재생
        //
        // OFF -> ONE -> ALL -> OFF
        // ======================================

        repeatButton = createButton("반복: OFF") {
            controller?.let { music ->

                if (
                    !music.isCommandAvailable(
                        Player.COMMAND_SET_REPEAT_MODE
                    )
                ) {
                    Log.w(
                        TAG,
                        "[REPEAT] 명령 사용 불가"
                    )
                    return@let
                }

                val nextMode = when (music.repeatMode) {

                    Player.REPEAT_MODE_OFF -> {
                        Player.REPEAT_MODE_ONE
                    }

                    Player.REPEAT_MODE_ONE -> {
                        Player.REPEAT_MODE_ALL
                    }

                    else -> {
                        Player.REPEAT_MODE_OFF
                    }
                }

                music.repeatMode = nextMode

                Log.i(
                    TAG,
                    "[REPEAT_MODE_CHANGED] " +
                            repeatModeName(nextMode)
                )

                updateState()
            }
        }

        // ======================================
        // 셔플 재생
        //
        // OFF <-> ON
        // ======================================

        shuffleButton = createButton("셔플: OFF") {
            controller?.let { music ->

                if (
                    !music.isCommandAvailable(
                        Player.COMMAND_SET_SHUFFLE_MODE
                    )
                ) {
                    Log.w(
                        TAG,
                        "[SHUFFLE] 명령 사용 불가"
                    )
                    return@let
                }

                val nextShuffle =
                    !music.shuffleModeEnabled

                music.shuffleModeEnabled = nextShuffle

                Log.i(
                    TAG,
                    "[SHUFFLE_MODE_CHANGED] $nextShuffle"
                )

                updateState()
            }
        }

        // ======================================
        // UI 배치
        // ======================================

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            addView(previousButton)
            addView(playButton)
            addView(nextButton)
        }

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            addView(rewindButton)
            addView(stopButton)
            addView(forwardButton)
        }

        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            addView(repeatButton)
            addView(shuffleButton)
        }

        layout.addView(titleText)
        layout.addView(artistText)
        layout.addView(stateText)
        layout.addView(positionText)
        layout.addView(row1)
        layout.addView(row2)
        layout.addView(row3)

        val scrollView = ScrollView(this)
        scrollView.addView(layout)

        setContentView(scrollView)
    }

    // ==========================================
    // 버튼 생성
    // ==========================================

    private fun createButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            setOnClickListener {
                action()
            }
        }
    }

    // ==========================================
    // MediaController 연결
    // ==========================================

    override fun onStart() {
        super.onStart()

        val token = SessionToken(
            this,
            ComponentName(
                this,
                MusicPlaybackService::class.java
            )
        )

        val future = MediaController.Builder(
            this,
            token
        ).buildAsync()

        controllerFuture = future

        future.addListener(
            {
                try {
                    val music = future.get()

                    if (controllerFuture !== future) {
                        return@addListener
                    }

                    controller = music
                    music.addListener(playerListener)

                    // 재생 목록이 없는 경우 초기화
                    // 기존 재생 목록이 있으면 유지
                    if (music.mediaItemCount == 0) {

                        val items = musicIds.map { id ->
                            MediaItem.Builder()
                                .setMediaId(id)
                                .build()
                        }

                        music.setMediaItems(items)
                        music.prepare()
                    }

                    Log.i(
                        TAG,
                        "[CONTROLLER_CONNECTED] " +
                                "count=${music.mediaItemCount}"
                    )

                    updateState()

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "[CONTROLLER_ERROR]",
                        e
                    )

                    stateText.text = "연결 실패"
                }
            },
            mainExecutor
        )

        handler.post(progressRunnable)
    }

    // ==========================================
    // 현재 상태 UI 갱신
    // ==========================================

    private fun updateState() {

        val music = controller ?: return

        val item = music.currentMediaItem

        titleText.text =
            item?.mediaMetadata?.title
                ?.toString()
                ?: "선택된 음악 없음"

        artistText.text =
            item?.mediaMetadata?.artist
                ?.toString()
                ?: "아티스트 정보 없음"

        val stateName = when (music.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }

        val trackNumber = if (
            music.mediaItemCount > 0
        ) {
            music.currentMediaItemIndex + 1
        } else {
            0
        }

        stateText.text =
            "상태: $stateName\n" +
                    "재생 중: ${music.isPlaying}\n" +
                    "곡: $trackNumber / ${music.mediaItemCount}"

        val current = music.currentPosition

        val duration = if (
            music.duration > 0L &&
            music.duration != C.TIME_UNSET
        ) {
            music.duration
        } else {
            0L
        }

        positionText.text =
            "${formatTime(current)} / ${formatTime(duration)}"

        playButton.text = if (music.isPlaying) {
            "일시정지"
        } else {
            "재생"
        }

        // 반복 버튼 상태 표시
        repeatButton.text =
            "반복: ${repeatModeName(music.repeatMode)}"

        // 셔플 버튼 상태 표시
        shuffleButton.text =
            if (music.shuffleModeEnabled) {
                "셔플: ON"
            } else {
                "셔플: OFF"
            }
    }

    // ==========================================
    // 반복 모드 문자열
    // ==========================================

    private fun repeatModeName(mode: Int): String {
        return when (mode) {

            Player.REPEAT_MODE_OFF -> "OFF"

            Player.REPEAT_MODE_ONE -> "ONE"

            Player.REPEAT_MODE_ALL -> "ALL"

            else -> "UNKNOWN"
        }
    }

    // ==========================================
    // 시간 포맷
    // ==========================================

    private fun formatTime(ms: Long): String {

        val seconds =
            ms.coerceAtLeast(0L) / 1000L

        return String.format(
            Locale.US,
            "%02d:%02d",
            seconds / 60L,
            seconds % 60L
        )
    }

    // ==========================================
    // Lifecycle
    // ==========================================

    override fun onStop() {

        handler.removeCallbacks(progressRunnable)

        controller?.removeListener(playerListener)
        controller = null

        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }

        controllerFuture = null

        super.onStop()
    }
}
