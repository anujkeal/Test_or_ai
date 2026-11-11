package com.keal.nexus.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset

data class CricketMatch(
    val home: String,
    val away: String,
    val homeScore: String,
    val awayScore: String,
    val status: String,
    val time: String
)

/**
 * Live cricket connector using SportScore's documented public widget endpoint.
 * No API key is embedded. Provider attribution is required by its terms.
 */
class NexusCricketConnector {
    companion object {
        const val ATTRIBUTION = "Cricket data provided by SportScore"
        private const val LIVE_URL = "https://sportscore.com/api/widget/matches/?sport=cricket&limit=30"
    }

    fun isCricketRequest(prompt: String): Boolean {
        val q = prompt.lowercase()
        val cricket = listOf("cricket", "ipl", "t20", "odi", "test match", "india match", "world cup")
            .any { q.contains(it) }
        val asksData = listOf("score", "run", "wicket", "over", "match", "playing", "today", "live", "fixture", "result", "won")
            .any { q.contains(it) }
        return cricket && asksData
    }

    suspend fun answer(prompt: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(LIVE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NEXUS-Android/1.0")
        }
        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) error("Cricket scores provider returned HTTP $statusCode.")
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(raw)
            val matches = root.optJSONArray("matches")
                ?: root.optJSONObject("data")?.optJSONArray("matches")
                ?: root.optJSONArray("data")
                ?: JSONArray()
            val parsed = (0 until matches.length()).mapNotNull { index ->
                val item = matches.optJSONObject(index) ?: return@mapNotNull null
                CricketMatch(
                    home = item.optString("home", item.optString("home_team", item.optString("team1", "Team 1"))),
                    away = item.optString("away", item.optString("away_team", item.optString("team2", "Team 2"))),
                    homeScore = score(item, "home_score", "homeScore", "score_home"),
                    awayScore = score(item, "away_score", "awayScore", "score_away"),
                    status = item.optString("status_text", item.optString("status", "Status unavailable")),
                    time = item.optString("time", item.optString("updated_at", ""))
                )
            }
            if (parsed.isEmpty()) {
                "I couldn't find any cricket matches in the live-score feed right now. This may mean there are no listed matches, or the provider response format has changed.\n\n$ATTRIBUTION"
            } else {
                val q = prompt.lowercase()
                val selected = if (q.contains("india")) parsed.filter {
                    it.home.contains("india", true) || it.away.contains("india", true)
                }.ifEmpty { parsed } else parsed
                val heading = if (q.contains("today")) "Cricket matches listed for today" else "Cricket live and recent matches"
                buildString {
                    append(heading).append(":\n\n")
                    selected.take(12).forEach { match ->
                        append(match.home).append(" vs ").append(match.away).append('\n')
                        if (match.homeScore.isNotBlank() || match.awayScore.isNotBlank()) {
                            append(match.home).append(": ").append(match.homeScore.ifBlank { "—" })
                                .append("  |  ").append(match.away).append(": ")
                                .append(match.awayScore.ifBlank { "—" }).append('\n')
                        }
                        append(match.status)
                        if (match.time.isNotBlank()) append(" · ").append(match.time)
                        append("\n\n")
                    }
                    append("Scores may update with a delay. For exact ball-by-ball details, open a live scorecard.\n")
                    append(ATTRIBUTION)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun score(item: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = item.opt(key)
            if (value != null && value != JSONObject.NULL) {
                val text = value.toString()
                if (text.isNotBlank() && text != "0") return text
            }
        }
        return ""
    }
}
