package com.yotogogo

import com.google.gson.annotations.SerializedName

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("token_type") val tokenType: String?,
    @SerializedName("error") val error: String?
)

// __NEXT_DATA__ page embed — used by card page fetch approach
data class NextData(
    @SerializedName("props") val props: NextProps?
)
data class NextProps(
    @SerializedName("pageProps") val pageProps: NextPageProps?
)
data class NextPageProps(
    @SerializedName("card") val card: YotoCard?
)

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
    @SerializedName("trackUrl") val trackUrl: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("format") val format: String?,
    @SerializedName("duration") val duration: Double?,
    @SerializedName("fileSize") val fileSize: Long?
)

data class TrackItem(
    val chapterTitle: String,
    val trackIndex: Int,
    val url: String,
    val filename: String,
    val mimeType: String = "audio/mpeg",
    var status: DownloadStatus = DownloadStatus.PENDING
)

enum class DownloadStatus { PENDING, DOWNLOADING, DONE, ERROR }
