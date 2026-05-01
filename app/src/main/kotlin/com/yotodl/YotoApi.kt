package com.yotodl

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class YotoApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    companion object {
        // Community-reverse-engineered Firebase project key for Yoto
        private const val FIREBASE_KEY = "AIzaSyDoETFqHxb7h-6MxCNvMwsmkiT_7cXFLmA"
        private const val FIREBASE_URL =
            "https://www.googleapis.com/identitytoolkit/v3/relyingparty/verifyPassword?key=$FIREBASE_KEY"
        private const val API_BASE = "https://api.yoto.io"
    }

    suspend fun login(email: String, password: String): String = withContext(Dispatchers.IO) {
        // Step 1: authenticate with Firebase
        val fbBody = gson.toJson(
            mapOf("email" to email, "password" to password, "returnSecureToken" to true)
        ).toRequestBody(jsonType)

        val fbReq = Request.Builder().url(FIREBASE_URL).post(fbBody).build()
        val fbBody2 = client.newCall(fbReq).execute().use { it.body?.string() }
            ?: throw Exception("No response from Firebase")

        val fbData = gson.fromJson(fbBody2, FirebaseAuthResponse::class.java)
        if (fbData.error != null) throw Exception("Login failed: ${fbData.error.message}")
        val idToken = fbData.idToken ?: throw Exception("No ID token in Firebase response")

        // Step 2: exchange for a Yoto API token
        val yotoBody = gson.toJson(mapOf("token" to idToken)).toRequestBody(jsonType)
        val yotoReq = Request.Builder()
            .url("$API_BASE/auth/token")
            .post(yotoBody)
            .build()

        val yotoBodyStr = client.newCall(yotoReq).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Yoto auth error ${resp.code}")
            resp.body?.string()
        } ?: throw Exception("Empty Yoto auth response")

        val yotoData = gson.fromJson(yotoBodyStr, YotoAuthResponse::class.java)
        yotoData.resolvedToken() ?: throw Exception("No token in Yoto auth response: $yotoBodyStr")
    }

    suspend fun getCard(authToken: String, cardSlug: String): YotoCard = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$API_BASE/card/$cardSlug")
            .header("Authorization", "Bearer $authToken")
            .get()
            .build()

        val body = client.newCall(req).execute().use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw Exception("Card fetch error ${resp.code}: $text")
            text
        } ?: throw Exception("Empty card response")

        gson.fromJson(body, CardResponse::class.java).card
            ?: throw Exception("No card field in response")
    }

    fun buildTrackList(card: YotoCard): List<TrackItem> {
        val items = mutableListOf<TrackItem>()
        card.content?.chapters?.forEachIndexed { ci, chapter ->
            val chTitle = chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${ci + 1}"
            chapter.tracks?.forEachIndexed { ti, track ->
                val url = track.key ?: return@forEachIndexed
                val ext = if (track.format?.contains("mpeg") == true) "mp3" else "audio"
                val name = "%02d_%02d_%s.%s".format(
                    ci + 1, ti + 1,
                    chTitle.replace(Regex("[^A-Za-z0-9 ]"), "").trim().replace(' ', '_'),
                    ext
                )
                items.add(TrackItem(chTitle, ti + 1, url, name))
            }
        }
        return items
    }
}
