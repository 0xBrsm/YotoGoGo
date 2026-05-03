package com.yotogogo

import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class YotoApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        const val CLIENT_ID   = "Ui8g0T3UR0CIsZJMhHpzouU8dfAm4ZEK"
        const val REDIRECT_URI = "com.yotogogo://callback"

        private const val AUTHORIZE_URL = "https://login.yotoplay.com/authorize"
        private const val TOKEN_URL     = "https://login.yotoplay.com/oauth/token"
        private const val API_BASE      = "https://api.yotoplay.com"
        private const val SCOPES        = "family:library:view user:content:view offline_access"
        private const val USER_AGENT    =
            "Yoto/2.73 (com.yotoplay.Yoto; build:10405; iOS 17.4.0) Alamofire/5.6.4"

        fun generateCodeVerifier(): String {
            val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }

        fun generateCodeChallenge(verifier: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }

        fun buildAuthUrl(codeChallenge: String): String =
            Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("audience", API_BASE)
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("scope", SCOPES)
                .build().toString()
    }

    suspend fun exchangeCodeForToken(code: String, verifier: String): String =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", CLIENT_ID)
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .build()

            val json = client.newCall(Request.Builder().url(TOKEN_URL).post(body).build())
                .execute().use { resp ->
                    val text = resp.body?.string()
                    if (!resp.isSuccessful) throw Exception("Token exchange failed ${resp.code}: $text")
                    text
                } ?: throw Exception("Empty token response")

            gson.fromJson(json, TokenResponse::class.java).accessToken
                ?: throw Exception("No access token in response: $json")
        }

    suspend fun getCard(accessToken: String, cardSlug: String): YotoCard =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$API_BASE/card/$cardSlug")
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", USER_AGENT)
                .get().build()

            val body = client.newCall(req).execute().use { resp ->
                val text = resp.body?.string()
                if (!resp.isSuccessful) throw Exception("Card fetch error ${resp.code}: $text")
                text
            } ?: throw Exception("Empty card response")

            gson.fromJson(body, CardResponse::class.java).card
                ?: throw Exception("No card in response")
        }

    suspend fun getLibrary(accessToken: String): List<YotoCard> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$API_BASE/card/family/library")
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", USER_AGENT)
                .get().build()

            val body = client.newCall(req).execute().use { resp ->
                val text = resp.body?.string()
                if (!resp.isSuccessful) throw Exception("Library fetch error ${resp.code}: $text")
                text
            } ?: throw Exception("Empty library response")

            val response = gson.fromJson(body, LibraryResponse::class.java)
            val seen = mutableSetOf<String>()
            val all = mutableListOf<YotoCard>()

            fun add(entry: LibraryEntry) {
                val id = entry.cardId ?: entry.card?.cardId ?: return
                val card = entry.card ?: return
                if (seen.add(id)) {
                    all.add(if (card.cardId == null) card.copy(cardId = id) else card)
                }
            }

            response.cards?.forEach { add(it) }
            response.playlists?.forEach { playlist ->
                playlist.cards?.forEach { add(it) }
            }

            all.sortedBy { it.title?.lowercase() ?: "" }
        }

    fun buildTrackList(card: YotoCard): List<TrackItem> {
        val items = mutableListOf<TrackItem>()
        card.content?.chapters?.forEachIndexed { ci, chapter ->
            val chTitle = chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${ci + 1}"
            chapter.tracks?.forEachIndexed { ti, track ->
                val url = track.trackUrl ?: return@forEachIndexed
                val (ext, mime) = when {
                    track.format?.contains("mp3")  == true -> "mp3" to "audio/mpeg"
                    track.format?.contains("mpeg") == true -> "mp3" to "audio/mpeg"
                    track.format?.contains("m4a")  == true -> "m4a" to "audio/mp4"
                    else -> "mp3" to "audio/mpeg"
                }
                val safe = chTitle.replace(Regex("[^A-Za-z0-9 ]"), "").trim().replace(' ', '_')
                val displayTitle = track.title?.takeIf { it.isNotBlank() }
                    ?: if (chapter.tracks.size > 1) "$chTitle (${ti + 1})" else chTitle
                val iconUrl = track.display?.icon16x16 ?: chapter.display?.icon16x16
                items.add(TrackItem(chTitle, ti + 1, displayTitle, iconUrl, url, "%02d_%02d_%s.%s".format(ci + 1, ti + 1, safe, ext), mime))
            }
        }
        return items
    }
}
