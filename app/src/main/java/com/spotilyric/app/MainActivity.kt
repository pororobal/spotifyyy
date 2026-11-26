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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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
            background = Color(0xFF121214),
            surface = Color(0xFF121214),
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

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121214)) {
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
            PermissionScreen {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }
}

@Composable
private fun PermissionScreen(onOpenSettings: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF27334C), Color(0xFF121214)),
                    center = Offset(180f, 120f),
                    radius = 900f,
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
                    .size(68.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("♪", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(22.dp))
            Text("SpotiLyric", fontSize = 29.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Spotify의 현재 곡과 재생 위치를 읽으려면\n알림 접근 권한이 필요합니다.",
                color = Color.White.copy(alpha = 0.64f),
                fontSize = 15.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("알림 접근 허용하기", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "스위치가 비활성화되어 있다면 앱 정보의 우측 상단 메뉴에서\n‘제한된 설정 허용’을 먼저 켜 주세요.",
                color = Color.White.copy(alpha = 0.36f),
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
    val mediaState by MediaStateHolder.state.collectAsStateWithLifecycle()
    val isOverlayRunning by OverlayStateHolder.isRunning.collectAsStateWithLifecycle()
    val syncOffsetMs by SyncOffsetStore.offsetMs.collectAsStateWithLifecycle()
    var lyricsResult by remember { mutableStateOf<LyricsResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var retryKey by remember { mutableIntStateOf(0) }
    var paletteColors by remember { mutableStateOf(defaultPalette) }

    LaunchedEffect(mediaState.albumArt) {
        paletteColors = withContext(Dispatchers.Default) { extractPaletteColors(mediaState.albumArt) }
    }

    LaunchedEffect(mediaState.title, mediaState.artist, mediaState.album, mediaState.durationMs, retryKey) {
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

    LaunchedEffect(Unit) {
        while (isActive) {
            currentPositionMs = MediaStateHolder.state.value.interpolatedPositionMs()
            delay(100L)
        }
    }

    val lines = (lyricsResult as? LyricsResult.Synced)?.lines.orEmpty()
    val lyricPositionMs = (currentPositionMs + syncOffsetMs).coerceAtLeast(0L)
    val activeIndex = remember(lines, lyricPositionMs) {
        lines.indexOfLast { lyricPositionMs >= it.startTimeMs }
    }
    val listState = rememberLazyListState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121214))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val compact = maxHeight < 560.dp
        val centerPadding = (maxHeight / 2 - if (compact) 28.dp else 36.dp).coerceAtLeast(72.dp)

        StaticAlbumBackground(paletteColors)

        when {
            mediaState.title.isBlank() -> GuideMessage(
                title = "재생 중인 곡이 없습니다",
                detail = "Spotify에서 음악을 재생하면\n가사가 자동으로 표시됩니다.",
            )
            isLoading -> GuideMessage("가사를 불러오는 중", "저장된 가사와 온라인 소스를 확인하고 있습니다.")
            lyricsResult is LyricsResult.Instrumental -> GuideMessage("연주곡입니다", "등록된 보컬 가사가 없습니다.")
            lyricsResult is LyricsResult.NotFound -> GuideMessage(
                title = "가사를 찾지 못했습니다",
                detail = "곡 버전이나 제목 표기가 다를 수 있습니다.",
                actionLabel = "다시 찾기",
                onAction = { retryKey++ },
            )
            lyricsResult is LyricsResult.Failed -> GuideMessage(
                title = "가사를 불러오지 못했습니다",
                detail = (lyricsResult as LyricsResult.Failed).message,
                actionLabel = "다시 시도",
                onAction = { retryKey++ },
            )
            lines.isNotEmpty() -> {
                LaunchedEffect(activeIndex, lines) {
                    if (activeIndex in lines.indices) listState.animateScrollToItem(activeIndex)
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = if (compact) 28.dp else 26.dp,
                        top = centerPadding,
                        end = if (compact) 28.dp else 26.dp,
                        bottom = centerPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 19.dp else 26.dp),
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
                                ((lyricPositionMs - line.startTimeMs).toFloat() / span).coerceIn(0f, 1f)
                            }
                            index < activeIndex -> 1f
                            else -> 0f
                        }
                        LyricRow(
                            text = line.text,
                            isActive = isActive,
                            distance = distance,
                            progress = progress,
                            compact = compact,
                            onClick = { MediaStateHolder.seekTo(line.startTimeMs - syncOffsetMs) },
                        )
                    }
                }
            }
        }

        TopTrackHeader(
            state = mediaState,
            isOverlayRunning = isOverlayRunning,
            compact = compact,
            onOverlayClick = if (isOverlayRunning) onStopOverlay else onRequestOverlay,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        BottomControls(
            mediaState = mediaState,
            currentPositionMs = currentPositionMs,
            compact = compact,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private val defaultPalette = listOf(Color(0xFF74424B), Color(0xFF2F405E))

@Composable
private fun StaticAlbumBackground(colors: List<Color>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(Color(0xFF121214))
        drawOrb(colors[0].copy(alpha = 0.34f), Offset(size.width * 0.12f, size.height * 0.16f), size.width * 0.9f)
        drawOrb(colors[1].copy(alpha = 0.31f), Offset(size.width * 0.9f, size.height * 0.72f), size.width * 0.96f)
        drawRect(Color(0xFF09090B).copy(alpha = 0.28f))
    }
}

private fun DrawScope.drawOrb(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = color.alpha * 0.28f), Color.Transparent),
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
    compact: Boolean,
    onOverlayClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xC8121214), Color.Transparent)))
            .padding(horizontal = if (compact) 18.dp else 20.dp, vertical = if (compact) 8.dp else 13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 38.dp else 44.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            state.albumArt?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "앨범 아트",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } ?: Text("♪", color = Color.White.copy(alpha = 0.48f), fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title.ifBlank { "재생 중인 곡 없음" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = state.artist.ifBlank { "Spotify" },
                color = Color.White.copy(alpha = 0.50f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onOverlayClick, contentPadding = PaddingValues(horizontal = 9.dp)) {
            Text(
                text = if (isOverlayRunning) "플로팅 끄기" else "플로팅",
                color = Color.White.copy(alpha = if (isOverlayRunning) 0.96f else 0.62f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
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
    compact: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.97f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 180f),
        label = "lyricScale",
    )
    val rowAlpha by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            distance == 1 -> 0.43f
            distance == 2 -> 0.25f
            else -> 0.14f
        },
        label = "lyricAlpha",
    )
    val blurRadius = when {
        isActive -> 0.dp
        distance == 1 -> 0.5.dp
        distance == 2 -> 1.5.dp
        else -> 2.5.dp
    }
    val textBrush = if (isActive) {
        val stop = progress.coerceIn(0.01f, 0.91f)
        Brush.horizontalGradient(
            0f to Color.White,
            stop to Color.White,
            (stop + 0.08f).coerceAtMost(0.99f) to Color.White.copy(alpha = 0.38f),
            1f to Color.White.copy(alpha = 0.38f),
        )
    } else {
        SolidColor(Color.White)
    }

    Text(
        text = text,
        style = TextStyle(
            brush = textBrush,
            fontSize = if (isActive) {
                if (compact) 32.sp else 38.sp
            } else {
                if (compact) 22.sp else 27.sp
            },
            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
            lineHeight = if (isActive) {
                if (compact) 39.sp else 47.sp
            } else {
                if (compact) 29.sp else 35.sp
            },
            letterSpacing = (-0.5).sp,
        ),
        modifier = Modifier
            .fillMaxWidth()
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
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = if (mediaState.durationMs > 0L) {
        (currentPositionMs.toFloat() / mediaState.durationMs).coerceIn(0f, 1f)
    } else 0f
    var dragProgress by remember { mutableFloatStateOf(progress) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(progress, isDragging) { if (!isDragging) dragProgress = progress }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE8121214))))
            .padding(horizontal = if (compact) 20.dp else 24.dp, vertical = if (compact) 4.dp else 10.dp),
    ) {
        Slider(
            value = dragProgress,
            onValueChange = {
                isDragging = true
                dragProgress = it
            },
            onValueChangeFinished = {
                MediaStateHolder.seekTo((dragProgress * mediaState.durationMs).toLong())
                isDragging = false
            },
            enabled = mediaState.durationMs > 0L,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.9f),
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                disabledThumbColor = Color.Transparent,
                disabledActiveTrackColor = Color.White.copy(alpha = 0.12f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.12f),
            ),
            modifier = Modifier.fillMaxWidth().height(if (compact) 20.dp else 28.dp),
        )
        if (!compact) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(currentPositionMs), color = Color.White.copy(alpha = 0.38f), fontSize = 11.sp)
                Text(formatTime(mediaState.durationMs), color = Color.White.copy(alpha = 0.38f), fontSize = 11.sp)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 30.dp else 36.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 4.dp),
        ) {
            PlayerButton("◀◀", 17.sp, mediaState.title.isNotBlank()) { MediaStateHolder.skipPrevious() }
            PlayerButton(if (mediaState.isPlaying) "❚❚" else "▶", if (mediaState.isPlaying) 22.sp else 27.sp, mediaState.title.isNotBlank()) {
                MediaStateHolder.togglePlayback()
            }
            PlayerButton("▶▶", 17.sp, mediaState.title.isNotBlank()) { MediaStateHolder.skipNext() }
        }
    }
}

@Composable
private fun PlayerButton(label: String, size: androidx.compose.ui.unit.TextUnit, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 0.88f else 0.25f), fontSize = size, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GuideMessage(
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White.copy(alpha = 0.74f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(detail, color = Color.White.copy(alpha = 0.44f), fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onAction) { Text(actionLabel, color = Color.White) }
            }
        }
    }
}

private fun extractPaletteColors(bitmap: Bitmap?): List<Color> {
    if (bitmap == null) return defaultPalette
    val palette = runCatching { Palette.from(bitmap).resizeBitmapArea(12_000).generate() }.getOrNull()
        ?: return defaultPalette
    return listOf(
        Color(palette.getMutedColor(palette.getDominantColor(0xFF74424B.toInt()))),
        Color(palette.getDarkVibrantColor(palette.getDarkMutedColor(0xFF2F405E.toInt()))),
    )
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    if (TextUtils.isEmpty(flat)) return false
    return flat.split(':').any { name ->
        ComponentName.unflattenFromString(name)?.packageName == context.packageName
    }
}
