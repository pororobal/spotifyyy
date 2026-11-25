package com.spotilyric.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { SpotiLyricTheme { SpotiLyricApp() } }
    }
}

@Composable
private fun SpotiLyricTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0A0A0D),
            surface = Color(0xFF111218),
            primary = Color.White,
            onPrimary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}

@Composable
fun SpotiLyricApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasNotificationAccess by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var startOverlayWhenAllowed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        SyncOffsetStore.initialize(context)
        LyricsRepository.initialize(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotificationAccess = isNotificationServiceEnabled(context)
                if (hasNotificationAccess) {
                    NotificationListenerService.requestRebind(
                        ComponentName(context, SpotifyMediaListenerService::class.java),
                    )
                }
                if (startOverlayWhenAllowed && Settings.canDrawOverlays(context)) {
                    FloatingLyricsService.start(context)
                    startOverlayWhenAllowed = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0D)) {
        if (hasNotificationAccess) {
            LyricsScreen(
                onRequestOverlay = {
                    if (Settings.canDrawOverlays(context)) {
                        FloatingLyricsService.start(context)
                    } else {
                        startOverlayWhenAllowed = true
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
                onStopOverlay = { FloatingLyricsService.stop(context) },
            )
        } else {
            PermissionScreen(
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
            )
        }
    }
}

@Composable
private fun PermissionScreen(onOpenSettings: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1B2338), Color(0xFF0A0A0D), Color(0xFF0A0A0D)),
                ),
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("♪", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = "SpotiLyric",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Spotify의 현재 곡과 재생 위치를 읽으려면\n알림 접근 권한이 필요합니다.",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 15.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                Text("알림 접근 허용하기", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "스위치가 비활성화되어 있다면 앱 정보의 우측 상단 메뉴에서\n‘제한된 설정 허용’을 먼저 켜 주세요.",
                color = Color.White.copy(alpha = 0.38f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LyricsScreen(
    onRequestOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val mediaState by MediaStateHolder.state.collectAsStateWithLifecycle()
    val isOverlayRunning by OverlayStateHolder.isRunning.collectAsStateWithLifecycle()
    val syncOffsetMs by SyncOffsetStore.offsetMs.collectAsStateWithLifecycle()
    var lyricsResult by remember { mutableStateOf<LyricsResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var retryKey by remember { mutableIntStateOf(0) }

    val paletteColors = remember(mediaState.albumArt) { extractPaletteColors(mediaState.albumArt) }

    LaunchedEffect(
        mediaState.title,
        mediaState.artist,
        mediaState.album,
        mediaState.durationMs,
        retryKey,
    ) {
        if (mediaState.title.isBlank()) {
            lyricsResult = null
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        lyricsResult = LyricsRepository.fetchSyncedLyrics(
            trackName = mediaState.title,
            artistName = mediaState.artist,
            albumName = mediaState.album,
            durationMs = mediaState.durationMs,
        )
        isLoading = false
    }

    LaunchedEffect(mediaState) {
        while (isActive) {
            withFrameNanos {
                currentPositionMs = mediaState.interpolatedPositionMs()
            }
        }
    }

    val lines = (lyricsResult as? LyricsResult.Synced)?.lines.orEmpty()
    val lyricPositionMs = (currentPositionMs + syncOffsetMs).coerceAtLeast(0L)
    val activeIndex = remember(lines, lyricPositionMs) {
        lines.indexOfLast { lyricPositionMs >= it.startTimeMs }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex, lines) {
        if (activeIndex in lines.indices) {
            listState.animateScrollToItem(index = activeIndex, scrollOffset = -260)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0D))) {
        FluidAlbumBackground(colors = paletteColors, isPlaying = mediaState.isPlaying)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            TopTrackHeader(
                state = mediaState,
                isOverlayRunning = isOverlayRunning,
                onOverlayClick = if (isOverlayRunning) onStopOverlay else onRequestOverlay,
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    mediaState.title.isBlank() -> GuideMessage(
                        title = "재생 중인 곡이 없습니다",
                        detail = "Spotify에서 음악을 재생하면\n동기화 가사가 자동으로 표시됩니다.",
                    )

                    isLoading -> GuideMessage(
                        title = "가사를 불러오는 중",
                        detail = "LRCLIB에서 곡을 찾고 있습니다.",
                    )

                    lyricsResult is LyricsResult.Instrumental -> GuideMessage(
                        title = "연주곡입니다",
                        detail = "등록된 보컬 가사가 없는 곡입니다.",
                    )

                    lyricsResult is LyricsResult.NotFound -> GuideMessage(
                        title = "동기화 가사를 찾지 못했습니다",
                        detail = "곡 버전이나 재생 시간에 맞는 가사가 없을 수 있습니다.",
                        actionLabel = "다시 찾기",
                        onAction = { retryKey++ },
                    )

                    lyricsResult is LyricsResult.Failed -> GuideMessage(
                        title = "가사를 불러오지 못했습니다",
                        detail = (lyricsResult as LyricsResult.Failed).message,
                        actionLabel = "다시 시도",
                        onAction = { retryKey++ },
                    )

                    lines.isNotEmpty() -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 26.dp,
                            top = 116.dp,
                            end = 26.dp,
                            bottom = 250.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(25.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(
                            items = lines,
                            key = { index, line -> "${line.startTimeMs}-$index" },
                        ) { index, line ->
                            val isActive = index == activeIndex
                            val distance = if (activeIndex >= 0) abs(index - activeIndex) else Int.MAX_VALUE
                            val progress = when {
                                isActive -> {
                                    val span = (line.endTimeMs - line.startTimeMs).coerceAtLeast(300L)
                                    ((lyricPositionMs - line.startTimeMs).toFloat() / span)
                                        .coerceIn(0f, 1f)
                                }
                                index < activeIndex -> 1f
                                else -> 0f
                            }

                            LyricRow(
                                text = line.text,
                                isActive = isActive,
                                distance = distance,
                                progress = progress,
                                onClick = {
                                    MediaStateHolder.activeController
                                        ?.transportControls
                                        ?.seekTo(line.startTimeMs)
                                },
                            )
                        }
                    }
                }
            }

            BottomControls(
                mediaState = mediaState,
                currentPositionMs = currentPositionMs,
                syncOffsetMs = syncOffsetMs,
                onAdjustSync = { SyncOffsetStore.adjust(context, it) },
                onResetSync = { SyncOffsetStore.reset(context) },
            )
        }
    }
}

@Composable
private fun FluidAlbumBackground(colors: List<Color>, isPlaying: Boolean) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        while (isActive) {
            phase += if (isPlaying) 0.008f else 0.002f
            delay(16L)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(Color(0xFF090A0E))
        val width = size.width
        val height = size.height
        drawOrb(
            color = colors[0].copy(alpha = 0.54f),
            center = Offset(
                width * (0.28f + 0.18f * sin(phase)),
                height * (0.25f + 0.13f * cos(phase * 0.8f)),
            ),
            radius = width * 0.86f,
        )
        drawOrb(
            color = colors[1].copy(alpha = 0.43f),
            center = Offset(
                width * (0.76f + 0.16f * cos(phase * 0.7f)),
                height * (0.62f + 0.17f * sin(phase * 0.9f)),
            ),
            radius = width * 0.92f,
        )
        drawOrb(
            color = colors[2].copy(alpha = 0.38f),
            center = Offset(
                width * (0.38f + 0.22f * sin(phase * 0.5f)),
                height * (0.86f + 0.08f * cos(phase)),
            ),
            radius = width * 0.78f,
        )
        drawRect(Color.Black.copy(alpha = 0.34f))
    }
}

private fun DrawScope.drawOrb(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = color.alpha * 0.35f), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

@Composable
private fun TopTrackHeader(
    state: NowPlayingState,
    isOverlayRunning: Boolean,
    onOverlayClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            val art = state.albumArt
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = "앨범 아트",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("♪", color = Color.White.copy(alpha = 0.55f), fontSize = 22.sp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title.ifBlank { "재생 중인 곡 없음" },
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = state.artist.ifBlank { "Spotify" },
                color = Color.White.copy(alpha = 0.60f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.title.isNotBlank()) {
            Text(
                text = if (state.isPlaying) "LIVE" else "PAUSED",
                color = Color.White.copy(alpha = if (state.isPlaying) 0.72f else 0.38f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onOverlayClick) {
            Text(
                text = if (isOverlayRunning) "플로팅 끄기" else "플로팅",
                color = Color.White.copy(alpha = if (isOverlayRunning) 1f else 0.72f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun LyricRow(
    text: String,
    isActive: Boolean,
    distance: Int,
    progress: Float,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.03f else 0.96f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow),
        label = "lyricScale",
    )
    val targetAlpha = when {
        isActive -> 1f
        distance == 1 -> 0.45f
        distance == 2 -> 0.28f
        else -> 0.16f
    }
    val rowAlpha by animateFloatAsState(targetValue = targetAlpha, label = "lyricAlpha")
    val blurRadius = when {
        isActive -> 0.dp
        distance == 1 -> 1.dp
        distance == 2 -> 2.dp
        else -> 3.dp
    }
    val textBrush = if (isActive) {
        val stop = progress.coerceIn(0.01f, 0.91f)
        Brush.horizontalGradient(
            0f to Color.White,
            stop to Color.White,
            (stop + 0.08f) to Color.White.copy(alpha = 0.42f),
            1f to Color.White.copy(alpha = 0.42f),
        )
    } else {
        SolidColor(Color.White)
    }

    Text(
        text = text,
        style = TextStyle(
            brush = textBrush,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp,
            letterSpacing = (-0.4).sp,
        ),
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .alpha(rowAlpha)
            .blur(blurRadius)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    )
}

@Composable
private fun BottomControls(
    mediaState: NowPlayingState,
    currentPositionMs: Long,
    syncOffsetMs: Long,
    onAdjustSync: (Long) -> Unit,
    onResetSync: () -> Unit,
) {
    val progress = if (mediaState.durationMs > 0L) {
        (currentPositionMs.toFloat() / mediaState.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.20f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.88f)),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(currentPositionMs), color = Color.White.copy(alpha = 0.46f), fontSize = 11.sp)
            Text(formatTime(mediaState.durationMs), color = Color.White.copy(alpha = 0.46f), fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                onClick = { MediaStateHolder.activeController?.transportControls?.skipToPrevious() },
                enabled = mediaState.title.isNotBlank(),
            ) {
                Text("이전", color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp)
            }
            Button(
                onClick = {
                    val controls = MediaStateHolder.activeController?.transportControls
                    if (mediaState.isPlaying) controls?.pause() else controls?.play()
                },
                enabled = mediaState.title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.16f),
                    disabledContainerColor = Color.White.copy(alpha = 0.06f),
                ),
                shape = CircleShape,
            ) {
                Text(
                    text = if (mediaState.isPlaying) "일시정지" else "재생",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            TextButton(
                onClick = { MediaStateHolder.activeController?.transportControls?.skipToNext() },
                enabled = mediaState.title.isNotBlank(),
            ) {
                Text("다음", color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onAdjustSync(-500L) }) {
                Text("−0.5초", color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp)
            }
            TextButton(onClick = onResetSync) {
                Text(
                    text = "가사 싱크 ${formatOffset(syncOffsetMs)}",
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = { onAdjustSync(500L) }) {
                Text("+0.5초", color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun GuideMessage(
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.76f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail,
                color = Color.White.copy(alpha = 0.46f),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun extractPaletteColors(bitmap: Bitmap?): List<Color> {
    val fallback = listOf(Color(0xFF2B3A67), Color(0xFF496A81), Color(0xFF1B1F3B))
    if (bitmap == null) return fallback
    val palette = runCatching { Palette.from(bitmap).generate() }.getOrNull() ?: return fallback
    val first = palette.getVibrantColor(palette.getDominantColor(0xFF2B3A67.toInt()))
    val second = palette.getMutedColor(palette.getDarkVibrantColor(0xFF496A81.toInt()))
    val third = palette.getDarkMutedColor(0xFF1B1F3B.toInt())
    return listOf(Color(first), Color(second), Color(third))
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0L) / 1_000L)
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    )
    if (TextUtils.isEmpty(flat)) return false
    return flat.split(':').any { name ->
        ComponentName.unflattenFromString(name)?.packageName == context.packageName
    }
}
