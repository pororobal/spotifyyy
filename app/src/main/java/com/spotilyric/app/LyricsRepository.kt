package com.spotilyric.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

private data class LyricsOvhResponse(val lyrics: String? = null)

private data class CachedLyrics(
    val instrumental: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
)

private data class ProviderLookup(
    val result: LyricsResult? = null,
    val reachedServer: Boolean = false,
)

object LyricsRepository {
    private const val MAX_CACHE_ENTRIES = 2_500
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val fetchMutex = Mutex()

    @Volatile
    private var cacheDirectory: File? = null

    fun initialize(context: Context) {
        if (cacheDirectory != null) return
        synchronized(this) {
            if (cacheDirectory == null) {
                cacheDirectory = File(context.applicationContext.filesDir, "lyrics_cache")
                    .apply { mkdirs() }
            }
        }
    }

    suspend fun fetchSyncedLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        durationMs: Long,
    ): LyricsResult = withContext(Dispatchers.IO) {
        if (trackName.isBlank()) return@withContext LyricsResult.NotFound
        val cacheKey = cacheKey(trackName, artistName, albumName, durationMs)

        readCache(cacheKey)?.let { return@withContext it }

        return@withContext fetchMutex.withLock {
            // 메인 화면과 플로팅 서비스가 동시에 같은 곡을 요청해도 네트워크 조회는 한 번만 한다.
            readCache(cacheKey)?.let { return@withLock it }

            val lrclib = lookupLrclib(trackName, artistName, albumName, durationMs)
            lrclib.result?.let { result ->
                writeCache(cacheKey, result)
                return@withLock result
            }

            val lyricsOvh = lookupLyricsOvh(trackName, artistName, durationMs)
            lyricsOvh.result?.let { result ->
                writeCache(cacheKey, result)
                return@withLock result
            }

            if (!lrclib.reachedServer && !lyricsOvh.reachedServer) {
                LyricsResult.Failed("네트워크 연결을 확인해 주세요.")
            } else {
                LyricsResult.NotFound
            }
        }
    }

    private fun lookupLrclib(
        trackName: String,
        artistName: String,
        albumName: String,
        durationMs: Long,
    ): ProviderLookup {
        var reachedServer = false
        val records = mutableListOf<LyricsRecord>()
        val cleanedTitle = cleanTitle(trackName)
        val primaryArtist = primaryArtist(artistName)

        fun exact(title: String, artist: String, album: String) {
            try {
                val record = fetchExact(title, artist, album, durationMs)
                reachedServer = true
                if (record != null) records += record
            } catch (_: IOException) {
                // 다음 검색 방식과 다음 공급자로 계속 진행한다.
            } catch (_: JsonParseException) {
                // 응답 형식이 깨져도 다른 조회 방식은 사용할 수 있다.
            }
        }

        exact(trackName, artistName, albumName)
        records.firstOrNull()?.toResult(durationMs)?.let { result ->
            if (result is LyricsResult.Synced && !records.first().syncedLyrics.isNullOrBlank()) {
                return ProviderLookup(result, reachedServer = true)
            }
        }
        if (cleanedTitle != trackName || primaryArtist != artistName) {
            exact(cleanedTitle, primaryArtist, "")
        }

        fun search(block: () -> List<LyricsRecord>) {
            try {
                records += block()
                reachedServer = true
            } catch (_: IOException) {
                // 다른 검색 변형을 시도한다.
            } catch (_: JsonParseException) {
                // 형식이 잘못된 검색 응답을 건너뛴다.
            }
        }

        search { searchStructured(cleanedTitle, primaryArtist, albumName) }
        search { searchKeywords(listOf(cleanedTitle, primaryArtist).filter { it.isNotBlank() }.joinToString(" ")) }

        val best = records
            .distinctBy { listOf(it.trackName, it.artistName, it.duration, it.syncedLyrics).joinToString("|") }
            .filter { it.instrumental || !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
            .minByOrNull { record ->
                record.matchScore(cleanedTitle, primaryArtist, durationMs) +
                    if (record.syncedLyrics.isNullOrBlank()) 6.0 else 0.0
            }
            ?.takeIf { it.matchScore(cleanedTitle, primaryArtist, durationMs) <= 32.0 }

        return ProviderLookup(
            result = best?.toResult(durationMs)?.takeUnless { it is LyricsResult.NotFound },
            reachedServer = reachedServer,
        )
    }

    private fun lookupLyricsOvh(
        trackName: String,
        artistName: String,
        durationMs: Long,
    ): ProviderLookup {
        val title = cleanTitle(trackName)
        val artist = primaryArtist(artistName)
        if (title.isBlank() || artist.isBlank()) return ProviderLookup(reachedServer = true)

        return try {
            val url = "https://api.lyrics.ovh/v1".toHttpUrl().newBuilder()
                .addPathSegment(artist)
                .addPathSegment(title)
                .build()
            val response = execute(url.toString())
            if (response.code == 404) return ProviderLookup(reachedServer = true)
            if (!response.isSuccessful) throw IOException("lyrics.ovh ${response.code}")
            val plainLyrics = gson.fromJson(response.body, LyricsOvhResponse::class.java).lyrics.orEmpty()
            val lines = PlainLyricsParser.parse(plainLyrics, durationMs)
            ProviderLookup(
                result = lines.takeIf { it.isNotEmpty() }?.let { LyricsResult.Synced(it) },
                reachedServer = true,
            )
        } catch (_: Exception) {
            ProviderLookup(reachedServer = false)
        }
    }

    private fun readCache(key: String): LyricsResult? {
        val file = cacheDirectory?.resolve("$key.json") ?: return null
        if (!file.isFile) return null
        return runCatching {
            val cached = gson.fromJson(file.readText(), CachedLyrics::class.java)
            file.setLastModified(System.currentTimeMillis())
            when {
                cached.instrumental -> LyricsResult.Instrumental
                cached.lines.isNotEmpty() -> LyricsResult.Synced(cached.lines)
                else -> null
            }
        }.getOrNull()
    }

    private fun writeCache(key: String, result: LyricsResult) {
        val cached = when (result) {
            is LyricsResult.Synced -> CachedLyrics(lines = result.lines)
            LyricsResult.Instrumental -> CachedLyrics(instrumental = true)
            else -> return
        }
        val directory = cacheDirectory ?: return
        runCatching {
            val target = directory.resolve("$key.json")
            val temporary = directory.resolve("$key.tmp")
            temporary.writeText(gson.toJson(cached))
            if (!temporary.renameTo(target)) {
                target.writeText(temporary.readText())
                temporary.delete()
            }
            target.setLastModified(System.currentTimeMillis())
            directory.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_CACHE_ENTRIES)
                ?.forEach { it.delete() }
        }
    }

    private fun cacheKey(trackName: String, artistName: String, albumName: String, durationMs: Long): String {
        val identity = listOf(trackName, artistName, albumName, durationMs.toString())
            .joinToString("|") { it.lowercase().trim() }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
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
                    addQueryParameter("duration", (durationMs / 1_000.0).roundToLong().coerceIn(1L, 3_600L).toString())
                }
            }
            .build()
        val response = execute(url.toString())
        if (response.code == 404) return null
        if (!response.isSuccessful) throw IOException("LRCLIB ${response.code}")
        return gson.fromJson(response.body, LyricsRecord::class.java)
    }

    private fun searchStructured(trackName: String, artistName: String, albumName: String): List<LyricsRecord> {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)
            .apply {
                if (artistName.isNotBlank()) addQueryParameter("artist_name", artistName)
                if (albumName.isNotBlank()) addQueryParameter("album_name", albumName)
            }
            .build()
        return parseSearchResponse(execute(url.toString()))
    }

    private fun searchKeywords(query: String): List<LyricsRecord> {
        if (query.isBlank()) return emptyList()
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return parseSearchResponse(execute(url.toString()))
    }

    private fun parseSearchResponse(response: SimpleResponse): List<LyricsRecord> {
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
            .header("User-Agent", "SpotiLyric/1.1.0 (Android; personal lyrics viewer)")
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            SimpleResponse(response.code, response.body?.string().orEmpty())
        }
    }

    private fun LyricsRecord.toResult(fallbackDurationMs: Long): LyricsResult {
        if (instrumental) return LyricsResult.Instrumental
        val totalMs = if (duration > 0.0) (duration * 1_000).roundToLong() else fallbackDurationMs
        val synced = syncedLyrics?.takeIf { it.isNotBlank() }
            ?.let { LrcParser.parse(it, totalMs) }
            .orEmpty()
        if (synced.isNotEmpty()) return LyricsResult.Synced(synced)
        val approximate = plainLyrics?.let { PlainLyricsParser.parse(it, totalMs) }.orEmpty()
        return if (approximate.isEmpty()) LyricsResult.NotFound else LyricsResult.Synced(approximate)
    }

    private fun LyricsRecord.matchScore(title: String, artist: String, durationMs: Long): Double {
        val durationPenalty = if (durationMs > 0L && duration > 0.0) {
            (abs(duration * 1_000 - durationMs) / 1_000.0).coerceAtMost(24.0)
        } else 4.0
        val titlePenalty = when {
            trackName.normalized() == title.normalized() -> 0.0
            trackName.normalized().contains(title.normalized()) || title.normalized().contains(trackName.normalized()) -> 4.0
            else -> 14.0
        }
        val artistPenalty = when {
            artist.isBlank() -> 0.0
            artistName.normalized().contains(artist.normalized()) -> 0.0
            else -> 9.0
        }
        return durationPenalty + titlePenalty + artistPenalty
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("(?i)\\s*[\\[(](feat\\.?|ft\\.?|with|remaster(?:ed)?|live|version|edit|mix).*?[\\])]") , "")
        .replace(Regex("(?i)\\s+-\\s+.*?(remaster(?:ed)?|live|version|edit|mix).*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun primaryArtist(value: String): String = value
        .split(Regex("(?i)\\s+(?:feat\\.?|ft\\.?|with|x)\\s+|,|&"), limit = 2)
        .firstOrNull()
        .orEmpty()
        .trim()

    private fun String?.normalized(): String = orEmpty()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
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
        }.distinctBy { it.first to it.second }.sortedBy { it.first }

        return timedText.mapIndexed { index, (startMs, text) ->
            val nextStart = timedText.getOrNull(index + 1)?.first
            val fallbackEnd = if (totalDurationMs > startMs) totalDurationMs else startMs + 4_000L
            LyricLine(startMs, (nextStart ?: fallbackEnd).coerceAtLeast(startMs + 250L), text)
        }
    }
}

object PlainLyricsParser {
    fun parse(content: String, totalDurationMs: Long): List<LyricLine> {
        val lines = content
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 300 }
            .toList()
        if (lines.isEmpty()) return emptyList()

        if (totalDurationMs <= 0L) {
            return lines.mapIndexed { index, text ->
                LyricLine(index * 4_000L, (index + 1) * 4_000L, text)
            }
        }

        val introMs = (totalDurationMs * 0.03).toLong().coerceAtMost(8_000L)
        val outroMs = (totalDurationMs * 0.03).toLong().coerceAtMost(8_000L)
        val usableMs = (totalDurationMs - introMs - outroMs).coerceAtLeast(lines.size * 750L)
        val weights = lines.map { it.codePointCount(0, it.length).coerceIn(8, 48).toLong() }
        val totalWeight = weights.sum().coerceAtLeast(1L)
        var accumulated = 0L

        return lines.mapIndexed { index, text ->
            val startMs = introMs + usableMs * accumulated / totalWeight
            accumulated += weights[index]
            val endMs = if (index == lines.lastIndex) {
                (totalDurationMs - outroMs).coerceAtLeast(startMs + 750L)
            } else {
                (introMs + usableMs * accumulated / totalWeight).coerceAtLeast(startMs + 750L)
            }
            LyricLine(startMs, endMs, text)
        }
    }
}
