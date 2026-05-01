package com.yotogogo

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class YotoApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        // Register a free OAuth app at https://yoto.dev/get-started/start-here/ to get your CLIENT_ID
        const val CLIENT_ID = "YOUR_CLIENT_ID_HERE"

        private const val AUTH_URL  = "https://login.yotoplay.com/oauth/device/code"
        private const val TOKEN_URL = "https://login.yotoplay.com/oauth/token"
        private const val API_BASE  = "https://api.yotoplay.com"

        // Mimics the official app; used by community tools to avoid API blocks
        private const val USER_AGENT =
            "Yoto/2.73 (com.yotoplay.Yoto; build:10405; iOS 17.4.0) Alamofire/5.6.4"
    }

    // Step 1 of device code flow — returns the codes to show the user
    suspend fun requestDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("audience", API_BASE)
            .add("scope", "family:library:view offline_access")
            .build()

        val req = Request.Builder().url(AUTH_URL).post(body).build()
        val json = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Device code request failed: ${resp.code}")
            resp.body?.string() ?: throw Exception("Empty response")
        }
        gson.fromJson(json, DeviceCodeResponse::class.java)
    }

    // Step 2 — poll until the user authorizes; returns the access token
    suspend fun pollForToken(deviceCode: DeviceCodeResponse): String = withContext(Dispatchers.IO) {
        val intervalMs = (deviceCode.interval * 1000L).coerceAtLeast(5_000L)
        val expiresAt = System.currentTimeMillis() + deviceCode.expiresIn * 1000L

        while (System.currentTimeMillis() < expiresAt) {
            delay(intervalMs)

            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("device_code", deviceCode.deviceCode)
                .build()

            val req = Request.Builder().url(TOKEN_URL).post(body).build()
            val json = client.newCall(req).execute().use { it.body?.string() } ?: continue
            val resp = gson.fromJson(json, TokenResponse::class.java)

            when (resp.error) {
                null -> return@withContext resp.accessToken
                    ?: throw Exception("Token missing in response")
                "authorization_pending", "slow_down" -> continue
                "expired_token" -> throw Exception("Login timed out — please try again")
                else -> throw Exception("Auth error: ${resp.error}")
            }
        }
        throw Exception("Login timed out — please try again")
    }

    suspend fun getCard(accessToken: String, cardSlug: String): YotoCard = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$API_BASE/card/$cardSlug")
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val body = client.newCall(req).execute().use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw Exception("Card fetch error ${resp.code}: $text")
            text
        } ?: throw Exception("Empty card response")

        gson.fromJson(body, CardResponse::class.java).card
            ?: throw Exception("No card in response")
    }

    fun buildTrackList(card: YotoCard): List<TrackItem> {
        val items = mutableListOf<TrackItem>()
        card.content?.chapters?.forEachIndexed { ci, chapter ->
            val chTitle = chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${ci + 1}"
            chapter.tracks?.forEachIndexed { ti, track ->
                val url = track.trackUrl ?: return@forEachIndexed
                val ext = when {
                    track.format?.contains("mp3") == true  -> "mp3"
                    track.format?.contains("mpeg") == true -> "mp3"
                    track.format?.contains("m4a") == true  -> "m4a"
                    else -> "audio"
                }
                val safeName = chTitle.replace(Regex("[^A-Za-z0-9 ]"), "").trim().replace(' ', '_')
                val name = "%02d_%02d_%s.%s".format(ci + 1, ti + 1, safeName, ext)
                items.add(TrackItem(chTitle, ti + 1, url, name))
            }
        }
        return items
    }
}
