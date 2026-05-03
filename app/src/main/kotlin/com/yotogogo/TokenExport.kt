package com.yotogogo

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

fun exportTokenForScript(context: Context, token: String) {
    val filename = "yoto_token.txt"
    val resolver = context.contentResolver
    val col = MediaStore.Downloads._ID
    resolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(col),
        "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(filename), null
    )?.use { if (it.moveToFirst()) resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "$col=?", arrayOf(it.getString(0))) }

    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, filename)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
        resolver.openOutputStream(uri)?.use { it.write(token.toByteArray()) }
    }
}
