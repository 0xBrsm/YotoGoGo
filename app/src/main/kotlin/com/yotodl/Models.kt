package com.yotodl

import com.google.gson.annotations.SerializedName

data class FirebaseAuthResponse(
    @SerializedName("idToken") val idToken: String?,
    @SerializedName("error") val error: FirebaseError?
)

data class FirebaseError(
    @SerializedName("message") val message: String?
)

data class YotoAuthResponse(
    // Yoto may return the token under either key
    @SerializedName("token") val token: String?,
    @SerializedName("authToken") val authToken: String?
) {
    fun resolvedToken() = token ?: authToken
}

data class CardResponse(
    @SerializedName("card") val card: YotoCard?
)

data class YotoCard(
    @SerializedName("cardId") val cardId: String?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("content") val content: CardContent?
)

data class CardContent(
    @SerializedName("chapters") val chapters: List<Chapter>?
)

data class Chapter(
    @SerializedName("key") val key: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("tracks") val tracks: List<Track>?
)

data class Track(
    @SerializedName("key") val key: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("format") val format: String?,
    @SerializedName("duration") val duration: Double?
)

// Flat list item used by the RecyclerView
data class TrackItem(
    val chapterTitle: String,
    val trackIndex: Int,
    val url: String,
    val filename: String,
    var status: DownloadStatus = DownloadStatus.PENDING
)

enum class DownloadStatus { PENDING, DOWNLOADING, DONE, ERROR }
