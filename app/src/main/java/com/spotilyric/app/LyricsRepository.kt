package com.spotilyric.app

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class LyricLine(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
)

sealed interface LyricsResult {
    data class Synced(val lines: List<LyricLine>) : LyricsResult
    data object Instrumental : LyricsResult
    data object NotFound : LyricsResult
    data class Failed(val message: String) : LyricsResult
}

private data class LyricsRecord(
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    val syncedLyrics: String? = null,
)

object LyricsRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    suspend fun fetchSyncedLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        durationMs: Long,
    ): LyricsResult = withContext(Dispatchers.IO) {
        if (trackName.isBlank()) return@withContext LyricsResult.NotFound

        try {
            fetchExact(trackName, artistName, albumName, durationMs)?.let { record ->
                return@withContext record.toResult(durationMs)
            }

            val candidates = search(trackName, artistName, albumName)
            val best = candidates
                .filter { it.instrumental || !it.syncedLyrics.isNullOrBlank() }
                .minByOrNull { it.matchScore(trackName, artistName, durationMs) }
                ?: return@withContext LyricsResult.NotFound

            best.toResult(durationMs)
        } catch (_: IOException) {
            LyricsResult.Failed("네트워크 연결을 확인해 주세요.")
        } catch (_: Exception) {
            LyricsResult.Failed("가사 응답을 처리하지 못했습니다.")
        }
    }

    private fun fetchExact(
        trackName: String,
        artistName: String,
        albumName: String,
        durationMs: Long,
    ): LyricsRecord? {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)
            .addQueryParameter("artist_name", artistName)
            .apply {
                if (albumName.isNotBlank()) addQueryParameter("album_name", albumName)
                if (durationMs > 0L) {
                    addQueryParameter(
                        "duration",
                        (durationMs / 1_000.0).roundToLong().coerceIn(1L, 3_600L).toString(),
                    )
                }
            }
            .build()

        val response = execute(url.toString())
        if (response.code == 404) return null
        if (!response.isSuccessful) throw IOException("LRCLIB ${response.code}")
        return gson.fromJson(response.body, LyricsRecord::class.java)
    }

    private fun search(
        trackName: String,
        artistName: String,
        albumName: String,
    ): List<LyricsRecord> {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)
            .apply {
                if (artistName.isNotBlank()) addQueryParameter("artist_name", artistName)
                if (albumName.isNotBlank()) addQueryParameter("album_name", albumName)
            }
            .build()

        val response = execute(url.toString())
        if (!response.isSuccessful) throw IOException("LRCLIB ${response.code}")
        val type = object : TypeToken<List<LyricsRecord>>() {}.type
        return gson.fromJson<List<LyricsRecord>>(response.body, type).orEmpty()
    }

    private data class SimpleResponse(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private fun execute(url: String): SimpleResponse {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SpotiLyric/1.0.0 (Android; personal lyrics viewer)")
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            SimpleResponse(response.code, response.body?.string().orEmpty())
        }
    }

    private fun LyricsRecord.toResult(fallbackDurationMs: Long): LyricsResult {
        if (instrumental) return LyricsResult.Instrumental
        val lrc = syncedLyrics?.takeIf { it.isNotBlank() } ?: return LyricsResult.NotFound
        val totalMs = if (duration > 0.0) (duration * 1_000).roundToLong() else fallbackDurationMs
        val lines = LrcParser.parse(lrc, totalMs)
        return if (lines.isEmpty()) LyricsResult.NotFound else LyricsResult.Synced(lines)
    }

    private fun LyricsRecord.matchScore(
        requestedTitle: String,
        requestedArtist: String,
        requestedDurationMs: Long,
    ): Double {
        val durationPenalty = if (requestedDurationMs > 0L && duration > 0.0) {
            abs(duration * 1_000 - requestedDurationMs) / 1_000.0
        } else {
            4.0
        }
        val titlePenalty = if (trackName.normalized() == requestedTitle.normalized()) 0.0 else 8.0
        val artistPenalty = if (
            requestedArtist.isBlank() || artistName.normalized().contains(requestedArtist.normalized())
        ) 0.0 else 5.0
        return durationPenalty + titlePenalty + artistPenalty
    }

    private fun String?.normalized(): String = orEmpty()
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
}

object LrcParser {
    private val timestampRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrcContent: String, totalDurationMs: Long): List<LyricLine> {
        val timedText = buildList {
            lrcContent.lineSequence().forEach lineLoop@ { rawLine ->
                val matches = timestampRegex.findAll(rawLine).toList()
                if (matches.isEmpty()) return@lineLoop
                val text = rawLine.substring(matches.last().range.last + 1).trim()
                if (text.isBlank()) return@lineLoop

                matches.forEach matchLoop@ { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: return@matchLoop
                    val seconds = match.groupValues[2].toLongOrNull() ?: return@matchLoop
                    val fraction = match.groupValues[3]
                    val millis = when (fraction.length) {
                        1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
                        2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
                        3 -> fraction.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    add((minutes * 60_000L + seconds * 1_000L + millis) to text)
                }
            }
        }
            .distinctBy { it.first to it.second }
            .sortedBy { it.first }

        return timedText.mapIndexed { index, (startMs, text) ->
            val nextStart = timedText.getOrNull(index + 1)?.first
            val fallbackEnd = if (totalDurationMs > startMs) totalDurationMs else startMs + 4_000L
            val endMs = (nextStart ?: fallbackEnd).coerceAtLeast(startMs + 250L)
            LyricLine(startTimeMs = startMs, endTimeMs = endMs, text = text)
        }
    }
}
