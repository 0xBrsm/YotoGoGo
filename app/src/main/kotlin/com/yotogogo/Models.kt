package com.yotogogo

import com.google.gson.annotations.SerializedName

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("token_type") val tokenType: String?,
    @SerializedName("error") val error: String?
)

data class CardResponse(
    @SerializedName("card") val card: YotoCard?
)

data class LibraryResponse(
    @SerializedName("cards") val cards: List<LibraryEntry>?,
    @SerializedName("playlists") val playlists: List<PlaylistEntry>?
)

data class LibraryEntry(
    @SerializedName("cardId") val cardId: String?,
    @SerializedName("card") val card: YotoCard?
)

data class PlaylistEntry(
    @SerializedName("cards") val cards: List<LibraryEntry>?
)

data class YotoCard(
    @SerializedName("cardId") val cardId: String?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("metadata") val metadata: CardMetadata?,
    @SerializedName("content") val content: CardContent?
)

data class CardMetadata(
    @SerializedName("cover") val cover: CardCover?
)

data class CardCover(
    @SerializedName("imageL") val imageL: String?,
    @SerializedName("imageM") val imageM: String?,
    @SerializedName("imageS") val imageS: String?
)

data class CardContent(
    @SerializedName("chapters") val chapters: List<Chapter>?
)

data class Chapter(
    @SerializedName("key") val key: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("tracks") val tracks: List<Track>?,
    @SerializedName("display") val display: ChapterDisplay?
)

data class ChapterDisplay(
    @SerializedName("icon16x16") val icon16x16: String?,
    @SerializedName("icon400x400") val icon400x400: String?
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
    val displayTitle: String,
    val iconUrl: String?,
    val url: String,
    val filename: String,
    val mimeType: String = "audio/mpeg",
    var status: DownloadStatus = DownloadStatus.PENDING
)

enum class DownloadStatus { PENDING, DOWNLOADING, DONE, ERROR }
