package com.spotilyric.app

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NowPlayingState(
    val sourcePackage: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArt: Bitmap? = null,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val positionAtUpdateMs: Long = 0L,
    val updateElapsedRealtimeMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
) {
    fun interpolatedPositionMs(nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()): Long {
        val base = positionAtUpdateMs.coerceAtLeast(0L)
        val candidate = if (isPlaying) {
            val elapsed = (nowElapsedRealtimeMs - updateElapsedRealtimeMs).coerceAtLeast(0L)
            base + (elapsed * playbackSpeed.coerceAtLeast(0f)).toLong()
        } else {
            base
        }
        return if (durationMs > 0L) candidate.coerceIn(0L, durationMs) else candidate.coerceAtLeast(0L)
    }
}

object MediaStateHolder {
    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state.asStateFlow()

    @Volatile
    var activeController: MediaController? = null
        private set

    fun updateFromController(controller: MediaController?) {
        activeController = controller
        if (controller == null) {
            _state.value = NowPlayingState()
            return
        }

        val metadata = controller.metadata
        val playback = controller.playbackState
        val updateTime = playback?.lastPositionUpdateTime
            ?.takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()

        _state.value = NowPlayingState(
            sourcePackage = controller.packageName.orEmpty(),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: "",
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            albumArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                ?.coerceAtLeast(0L)
                ?: 0L,
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            positionAtUpdateMs = playback?.position?.coerceAtLeast(0L) ?: 0L,
            updateElapsedRealtimeMs = updateTime,
            playbackSpeed = playback?.playbackSpeed?.takeIf { it > 0f } ?: 1.0f,
        )
    }

    fun seekTo(positionMs: Long) {
        activeController?.transportControls?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun togglePlayback() {
        val controller = activeController ?: return
        if (_state.value.isPlaying) controller.transportControls.pause()
        else controller.transportControls.play()
    }

    fun skipPrevious() {
        activeController?.transportControls?.skipToPrevious()
    }

    fun skipNext() {
        activeController?.transportControls?.skipToNext()
    }
}

class SpotifyMediaListenerService : NotificationListenerService() {
    private var sessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshState()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshState()

        override fun onSessionDestroyed() {
            currentController?.unregisterCallback(this)
            currentController = null
            refreshControllers()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindBestController(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, SpotifyMediaListenerService::class.java)
        runCatching {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component, mainHandler)
        }
        refreshControllers()
    }

    override fun onListenerDisconnected() {
        MediaStateHolder.updateFromController(null)
        requestRebind(ComponentName(this, SpotifyMediaListenerService::class.java))
        super.onListenerDisconnected()
    }

    private fun refreshControllers() {
        val component = ComponentName(this, SpotifyMediaListenerService::class.java)
        val controllers = runCatching { sessionManager?.getActiveSessions(component).orEmpty() }
            .getOrDefault(emptyList())
        bindBestController(controllers)
    }

    private fun bindBestController(controllers: List<MediaController>) {
        // 다른 음악 앱의 세션을 잡아 재생 명령을 잘못 보내지 않도록 Spotify만 연결한다.
        val target = controllers
            .filter { it.packageName.contains("spotify", ignoreCase = true) }
            .maxByOrNull { controller ->
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0
            }

        if (target?.sessionToken != currentController?.sessionToken) {
            currentController?.unregisterCallback(controllerCallback)
            currentController = target
            currentController?.registerCallback(controllerCallback, mainHandler)
        }
        refreshState()
    }

    private fun refreshState() {
        MediaStateHolder.updateFromController(currentController)
    }

    override fun onDestroy() {
        currentController?.unregisterCallback(controllerCallback)
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        currentController = null
        MediaStateHolder.updateFromController(null)
        super.onDestroy()
    }
}
