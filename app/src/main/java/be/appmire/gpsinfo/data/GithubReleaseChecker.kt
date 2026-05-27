package be.appmire.gpsinfo.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dependency-free "is there a newer release?" probe against the GitHub
 * Releases API.
 *
 * A single HTTPS GET parsed with the platform's `org.json` — no Play
 * Services, no SDK, no HTTP library, matching the rest of the app. It
 * deliberately swallows every failure and returns null so a flaky network,
 * a rate-limit, or a malformed payload is silent rather than crashing.
 *
 * Used by the update nudge; the caller debounces calls to roughly once a
 * day and persists the result.
 */
object GithubReleaseChecker {

    private const val TIMEOUT_MS = 8_000

    /**
     * Returns the latest published release's version name — the git tag
     * with any leading "v" stripped — or null on any error.
     *
     * The `/releases/latest` endpoint already excludes drafts and
     * pre-releases, so we don't have to filter them out ourselves.
     *
     * @param repo "owner/name", e.g. "jeroentrappers/gpsinfo".
     */
    fun fetchLatestVersionName(repo: String): String? = runCatching {
        val url = URL("https://api.github.com/repos/$repo/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub rejects API requests that don't send a User-Agent.
            setRequestProperty("User-Agent", "GPSinfo-update-check")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("tag_name").trim()
                .removePrefix("v").removePrefix("V")
                .takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
