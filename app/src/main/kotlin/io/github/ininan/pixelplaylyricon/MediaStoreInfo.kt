package io.github.ininan.pixelplaylyricon

import android.content.Context
import android.provider.MediaStore

/**
 * 用 PixelPlayer 的进程身份查 MediaStore。
 *
 * mediaId 就是 MediaStore 的 `_ID`（源码里 `setMediaId(song.id)`，song.id 来自 MediaStore），
 * 拿到的路径用于读取内嵌歌词。
 */
object MediaStoreInfo {

    data class Info(
        val path: String?,
        val title: String?,
        val artist: String?,
        val duration: Long,
        val mimeType: String?,
    )

    private val PROJECTION = arrayOf(
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.MIME_TYPE,
    )

    fun query(context: Context, mediaId: String): Info? {
        val id = mediaId.toLongOrNull() ?: return null
        return runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                "${MediaStore.Audio.Media._ID} = ?",
                arrayOf(id.toString()),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                Info(
                    path = cursor.getString(0),
                    title = cursor.getString(1),
                    artist = cursor.getString(2),
                    duration = cursor.getLong(3),
                    mimeType = cursor.getString(4),
                )
            }
        }.getOrNull()
    }
}
