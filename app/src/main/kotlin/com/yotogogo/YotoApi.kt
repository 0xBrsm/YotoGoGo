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

    // Fetch the full Yoto card page URL (preserving query string from NFC tag)
    // and extract card data from the embedded __NEXT_DATA__ JSON.
    // No auth required — the query string in the NFC URL is the ownership proof.
    suspend fun fetchCardFromPage(nfcUrl: String): YotoCard = withContext(Dispatchers.IO) {
        val html = client.newCall(Request.Builder().url(nfcUrl).get().build())
            .execute().use { resp ->
                val text = resp.body?.string()
                if (!resp.isSuccessful) throw Exception("Page fetch error ${resp.code}: $text")
                text
            } ?: throw Exception("Empty page response")

        val marker = """<script id="__NEXT_DATA__" type="application/json">"""
        val start  = html.indexOf(marker)
        if (start == -1) throw Exception("__NEXT_DATA__ not found in page")
        val jsonStart = start + marker.length
        val jsonEnd   = html.indexOf("</script>", jsonStart)
        val json      = html.substring(jsonStart, jsonEnd)

        gson.fromJson(json, NextData::class.java)
            ?.props?.pageProps?.card
            ?: throw Exception("No card in page data")
    }

    fun buildTrackList(card: YotoCard): List<TrackItem> {
        val items = mutableListOf<TrackItem>()
        card.content?.chapters?.forEachIndexed { ci, chapter ->
            val chTitle = chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${ci + 1}"
            chapter.tracks?.forEachIndexed { ti, track ->
                val url = track.trackUrl ?: return@forEachIndexed
                val ext = when {
                    track.format?.contains("mp3")  == true -> "mp3"
                    track.format?.contains("mpeg") == true -> "mp3"
                    track.format?.contains("m4a")  == true -> "m4a"
                    else -> "audio"
                }
                val safe = chTitle.replace(Regex("[^A-Za-z0-9 ]"), "").trim().replace(' ', '_')
                items.add(TrackItem(chTitle, ti + 1, url, "%02d_%02d_%s.%s".format(ci + 1, ti + 1, safe, ext)))
            }
        }
        return items
    }
}
