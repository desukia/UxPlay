package com.appletv.airplay.lyrics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

data class LyricLine(
    val timeMs: Long,
    val text: String
)

object LyricsManager {
    private const val TAG = "LyricsManager"
    private val lrcPattern = Pattern.compile("^\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\](.*)")
    private val lyricsCache = HashMap<String, List<LyricLine>>()

    suspend fun fetchLyrics(artist: String, title: String, durationSec: Int = 0): List<LyricLine> {
        val cleanArtist = cleanMetadata(artist)
        val cleanTitle = cleanMetadata(title)
        val cacheKey = "${cleanArtist.trim().lowercase()} - ${cleanTitle.trim().lowercase()}"

        lyricsCache[cacheKey]?.let { return it }

        val result = withContext(Dispatchers.IO) {
            try {
                // 1. Try exact match from LRCLIB
                var synced = requestGet(cleanArtist, cleanTitle, durationSec)

                // 2. Fallback: Search with artist + title
                if (synced.isNullOrBlank()) {
                    synced = requestSearch("$cleanArtist $cleanTitle", cleanArtist, cleanTitle)
                }

                // 3. Fallback: Search with title only if artist was complex/compound
                if (synced.isNullOrBlank() && cleanTitle.length > 2) {
                    synced = requestSearch(cleanTitle, cleanArtist, cleanTitle)
                }

                if (!synced.isNullOrBlank()) {
                    val parsed = parseLrc(synced)
                    if (parsed.isNotEmpty()) {
                        Log.i(TAG, "Successfully loaded ${parsed.size} synced lyrics from LRCLIB for '$cleanTitle'")
                        return@withContext parsed
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "LRCLIB query failed: ${e.message}")
            }

            // Real behavior: if no synced lyrics exist on LRCLIB, return emptyList so UI shows "Letras indisponíveis"
            emptyList<LyricLine>()
        }

        if (result.isNotEmpty()) {
            lyricsCache[cacheKey] = result
        }
        return result
    }

    private fun requestGet(artist: String, title: String, durationSec: Int): String? {
        try {
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val urlStr = StringBuilder("https://lrclib.net/api/get?artist_name=$encArtist&track_name=$encTitle")
            if (durationSec > 0) {
                urlStr.append("&duration=$durationSec")
            }

            val conn = URL(urlStr.toString()).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "AppleTV-AirPlay/1.0 (Android TV)")

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(response)
                if (json.has("syncedLyrics") && !json.isNull("syncedLyrics")) {
                    val text = json.getString("syncedLyrics")
                    if (text.isNotBlank()) return text
                }
            }
        } catch (e: Exception) {
            // Ignore get error and fall back to search
        }
        return null
    }

    private fun requestSearch(query: String, targetArtist: String, targetTitle: String): String? {
        try {
            val encQ = URLEncoder.encode(query, "UTF-8")
            val urlStr = "https://lrclib.net/api/search?q=$encQ"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "AppleTV-AirPlay/1.0 (Android TV)")

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val array = JSONArray(response)
                var bestSynced: String? = null
                val normTargetArtist = targetArtist.lowercase().trim()
                val normTargetTitle = targetTitle.lowercase().trim()

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    if (item.has("syncedLyrics") && !item.isNull("syncedLyrics")) {
                        val text = item.getString("syncedLyrics")
                        if (text.isNotBlank()) {
                            val itemArtist = item.optString("artistName", "").lowercase().trim()
                            val itemTitle = item.optString("trackName", "").lowercase().trim()

                            // Check for strong artist & title match
                            val artistMatch = normTargetArtist.isNotEmpty() &&
                                    (itemArtist.contains(normTargetArtist) || normTargetArtist.contains(itemArtist))
                            val titleMatch = normTargetTitle.isNotEmpty() &&
                                    (itemTitle.contains(normTargetTitle) || normTargetTitle.contains(itemTitle))

                            if (artistMatch && titleMatch) {
                                return text // Optimal match found
                            }

                            if (bestSynced == null) {
                                bestSynced = text
                            }
                        }
                    }
                }
                return bestSynced
            }
        } catch (e: Exception) {
            // Ignore search error
        }
        return null
    }

    fun parseLrc(lrcText: String): List<LyricLine> {
        val list = mutableListOf<LyricLine>()
        val lines = lrcText.split("\n", "\r")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val matcher = lrcPattern.matcher(trimmed)
            if (matcher.find()) {
                try {
                    val min = matcher.group(1)?.toLong() ?: 0L
                    val sec = matcher.group(2)?.toLong() ?: 0L
                    val msRaw = matcher.group(3)
                    val ms = when {
                        msRaw == null -> 0L
                        msRaw.length == 1 -> msRaw.toLong() * 100
                        msRaw.length == 2 -> msRaw.toLong() * 10
                        else -> msRaw.take(3).toLong()
                    }
                    val timeMs = (min * 60 + sec) * 1000 + ms
                    val text = matcher.group(4)?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        list.add(LyricLine(timeMs, text))
                    }
                } catch (e: Exception) {
                    // Ignore line parse error
                }
            }
        }
        return list.sortedBy { it.timeMs }
    }

    fun cleanMetadata(text: String): String {
        return text
            .replace(Regex("\\s*[\\[\\(](?:feat\\.?|ft\\.?|with|ao vivo|live|remaster(?:ed)?|acoustic|version|deluxe|bonus|single|radio edit|original).*?[\\]\\)]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*(?:Ao Vivo|Live|Remaster(?:ed)?|Acoustic|Version|Deluxe|Single|Radio Edit|Instrumental).*", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
