package io.github.mkl2008.pixelplaylyricon

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * 直接读取 PixelPlayer 自己的 Room 数据库。
 *
 * 之所以这么做：release 包被 R8 混淆，类名方法名全变了，按名字 hook 应用内部的
 * 歌词仓库极不稳定；但 SQL 表名/列名是字符串常量，R8 不会改动，因此这条路对版本更新免疫。
 *
 * 数据库结构与源码 `app/schemas/...PixelPlayDatabase/42.json` 一致：
 *   lyrics(songId INTEGER, content TEXT, isSynced INTEGER)
 */
object LyricsStore {

    private const val QUERY = "SELECT content FROM lyrics WHERE songId = ? LIMIT 1"

    fun query(context: Context, mediaId: String): String? {
        val songId = mediaId.toLongOrNull() ?: return null
        val dbFile = runCatching { context.getDatabasePath(Constants.DB_NAME) }.getOrNull() ?: return null
        if (!dbFile.exists()) return null

        return runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery(QUERY, arrayOf(songId.toString())).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }
        }.getOrNull()
    }
}
