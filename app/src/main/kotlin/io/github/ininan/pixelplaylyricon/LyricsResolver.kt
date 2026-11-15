package io.github.ininan.pixelplaylyricon

import android.content.Context
import java.io.File

/**
 * 歌词来源按优先级解析：
 *
 *  1. PixelPlayer 自己的数据库（用户编辑过、或从 LRCLIB 抓下来存过的，最准）
 *  2. 音频文件内嵌歌词（ID3 USLT / Vorbis comment / MP4 ©lyr）—— 也就是 PixelPlayer 的 `Song.lyrics`
 *  3. 同目录同名 `.lrc` 边车文件
 */
object LyricsResolver {

    data class Result(
        val parsed: LrcParser.Parsed,
        val source: String,
    )

    fun resolve(context: Context, mediaId: String): Result {
        LyricsStore.query(context, mediaId)?.let { raw ->
            val parsed = LrcParser.parse(raw)
            if (!parsed.isEmpty) return Result(parsed, "database")
        }

        val info = MediaStoreInfo.query(context, mediaId)
        val path = info?.path
        if (!path.isNullOrBlank()) {
            val file = File(path)
            EmbeddedLyrics.read(file)?.let { raw ->
                val parsed = LrcParser.parse(raw)
                if (!parsed.isEmpty) return Result(parsed, "embedded")
            }
        }

        return Result(LrcParser.parse(null), "none")
    }
}
