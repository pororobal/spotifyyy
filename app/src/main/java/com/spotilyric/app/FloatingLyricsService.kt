package com.spotilyric.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object OverlayStateHolder {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    internal fun update(running: Boolean) {
        _isRunning.value = running
    }
}

object SyncOffsetStore {
    private const val PREFS_NAME = "playback_preferences"
    private const val KEY_OFFSET_MS = "lyrics_offset_ms"
    private const val MAX_OFFSET_MS = 10_000L
    private val _offsetMs = MutableStateFlow(0L)
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        _offsetMs.value = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_OFFSET_MS, 0L)
            .coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        initialized = true
    }

    fun adjust(context: Context, deltaMs: Long) {
        set(context, _offsetMs.value + deltaMs)
    }

    fun reset(context: Context) {
        set(context, 0L)
    }

    private fun set(context: Context, valueMs: Long) {
        initialize(context)
        val value = valueMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        _offsetMs.value = value
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_OFFSET_MS, value)
            .apply()
    }
}

class FloatingLyricsService : Service() {
    companion object {
        private const val CHANNEL_ID = "floating_lyrics"
        private const val NOTIFICATION_ID = 1204
        private const val ACTION_STOP = "com.spotilyric.app.STOP_FLOATING_LYRICS"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingLyricsService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingLyricsService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var windowParams: WindowManager.LayoutParams
    private var overlayView: View? = null
    private lateinit var titleView: TextView
    private lateinit var currentLineView: TextView
    private lateinit var nextLineView: TextView
    private lateinit var playPauseButton: Button
    private lateinit var offsetView: TextView
    private var lyrics: List<LyricLine> = emptyList()
    private var lyricJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        SyncOffsetStore.initialize(this)
        LyricsRepository.initialize(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createOverlay()
        OverlayStateHolder.update(true)
        observePlayback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observePlayback() {
        scope.launch {
            MediaStateHolder.state.collectLatest { state ->
                titleView.text = if (state.title.isBlank()) {
                    "Spotify에서 음악을 재생해 주세요"
                } else {
                    listOf(state.title, state.artist).filter { it.isNotBlank() }.joinToString(" · ")
                }
                playPauseButton.text = if (state.isPlaying) "❚❚" else "▶"
                lyricJob?.cancel()
                lyrics = emptyList()
                if (state.title.isNotBlank()) {
                    lyricJob = scope.launch {
                        lyrics = (LyricsRepository.fetchSyncedLyrics(
                            trackName = state.title,
                            artistName = state.artist,
                            albumName = state.album,
                            durationMs = state.durationMs,
                        ) as? LyricsResult.Synced)?.lines.orEmpty()
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                renderLyrics()
                delay(100L)
            }
        }
    }

    private fun renderLyrics() {
        val state = MediaStateHolder.state.value
        val positionMs = (state.interpolatedPositionMs() + SyncOffsetStore.offsetMs.value)
            .coerceAtLeast(0L)
        val activeIndex = lyrics.indexOfLast { positionMs >= it.startTimeMs }
        currentLineView.text = when {
            state.title.isBlank() -> "재생 중인 곡이 없습니다"
            lyrics.isEmpty() -> "동기화 가사를 찾는 중…"
            activeIndex < 0 -> lyrics.first().text
            else -> lyrics[activeIndex].text
        }
        nextLineView.text = lyrics.getOrNull(activeIndex + 1)?.text.orEmpty()
        offsetView.text = formatOffset(SyncOffsetStore.offsetMs.value)
        playPauseButton.text = if (state.isPlaying) "❚❚" else "▶"
    }

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowParams = WindowManager.LayoutParams(
            dp(340),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(110)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            background = roundedBackground(0xEB111218.toInt(), 20f)
            elevation = dp(10).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = textView("Spotify에서 음악을 재생해 주세요", 12f, Color.LTGRAY).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, dp(38), 1f))
        header.addView(button("앱") {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        })
        header.addView(button("×") { stopSelf() })
        root.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, dp(38))

        currentLineView = textView("가사를 준비하고 있습니다", 20f, Color.WHITE).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }
        nextLineView = textView("", 14f, 0x99FFFFFF.toInt()).apply {
            setPadding(0, dp(2), 0, dp(10))
        }
        root.addView(currentLineView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(nextLineView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(button("−0.5") { SyncOffsetStore.adjust(this, -500L) })
        offsetView = textView("0.0s", 12f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { SyncOffsetStore.reset(this@FloatingLyricsService) }
        }
        controls.addView(offsetView, LinearLayout.LayoutParams(0, dp(38), 1f))
        controls.addView(button("+0.5") { SyncOffsetStore.adjust(this, 500L) })
        playPauseButton = button("▶") {
            val state = MediaStateHolder.state.value
            val transport = MediaStateHolder.activeController?.transportControls
            if (state.isPlaying) transport?.pause() else transport?.play()
        }
        controls.addView(playPauseButton)
        root.addView(controls, LinearLayout.LayoutParams.MATCH_PARENT, dp(40))

        makeDraggable(header)
        currentLineView.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        overlayView = root
        windowManager.addView(root, windowParams)
    }

    private fun makeDraggable(handle: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    windowParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    windowParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    overlayView?.let { windowManager.updateViewLayout(it, windowParams) }
                    true
                }
                else -> false
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), 0, dp(12), 0)
        background = roundedBackground(0x2FFFFFFF, 12f)
        setOnClickListener { onClick() }
    }

    private fun textView(label: String, sizeSp: Float, color: Int): TextView = TextView(this).apply {
        text = label
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "플로팅 가사",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "다른 앱 위에 동기화 가사를 표시합니다." },
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("SpotiLyric 플로팅 가사")
        .setContentText("Spotify 위에 가사를 표시하고 있습니다.")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            "닫기",
            PendingIntent.getService(
                this,
                1,
                Intent(this, FloatingLyricsService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    override fun onDestroy() {
        lyricJob?.cancel()
        scope.cancel()
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        OverlayStateHolder.update(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

fun formatOffset(offsetMs: Long): String = String.format(
    Locale.US,
    "%+.1fs",
    offsetMs / 1_000f,
)
