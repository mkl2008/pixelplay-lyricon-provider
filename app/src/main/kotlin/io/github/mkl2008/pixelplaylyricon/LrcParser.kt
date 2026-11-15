package io.github.mkl2008.pixelplaylyricon

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import java.util.TreeMap

/**
 * LRC 解析器，对齐 PixelPlayer 自己的存储格式（源码 utils/LyricsUtils.kt）：
 *
 *  - 一行可以带多个时间戳 `[00:12.34][01:20.00]歌词`
 *  - 同一时间戳的第二行视为**翻译**（`syncedToLrcString` 就是把 translation 按同一时间戳写出去的）
 *  - 逐字用 `<mm:ss.xx>` 标签包住每个字/词
 *  - 纯文本（没有任何时间戳）返回 plain 列表
 */
object LrcParser {

    private const val DEFAULT_LINE_DURATION_MS = 5_000L

    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_REGEX = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META_REGEX = Regex("""^\[[a-zA-Z#]+:[^\]]*]$""")

    data class Parsed(
        val lines: List<RichLyricLine>,
        val plain: List<String>,
        val hasWordLevel: Boolean,
        val hasTranslation: Boolean,
    ) {
        val isEmpty: Boolean get() = lines.isEmpty() && plain.isEmpty()
    }

    fun parse(raw: String?): Parsed {
        if (raw.isNullOrBlank()) return Parsed(emptyList(), emptyList(), false, false)

        val plainLines = mutableListOf<String>()
        val byTime = TreeMap<Long, MutableList<String>>()
        var wordLevel = false
        var hasTranslation = false

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            if (META_REGEX.matches(trimmed)) return@forEach

            val stamps = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (stamps.isEmpty()) {
                // 没有时间戳：当作纯文本歌词
                plainLines += trimmed
                return@forEach
            }

            val text = trimmed.substring(stamps.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            if (WORD_REGEX.findAll(text).count() >= 2) wordLevel = true

            stamps.forEach { stamp ->
                val millis = toMillis(stamp.groupValues[1], stamp.groupValues[2], stamp.groupValues[3])
                byTime.getOrPut(millis) { mutableListOf() }.add(text)
            }
        }

        if (byTime.isEmpty()) {
            return Parsed(emptyList(), plainLines, false, false)
        }

        val times = byTime.keys.toList()
        val lines = times.mapIndexed { index, begin ->
            val texts = byTime.getValue(begin)
            val main = texts.first()
            val translation = texts.drop(1).joinToString("\n").takeIf { it.isNotBlank() }
            if (translation != null) hasTranslation = true

            val end = times.getOrNull(index + 1) ?: (begin + DEFAULT_LINE_DURATION_MS)
            RichLyricLine(
                begin = begin,
                end = end.coerceAtLeast(begin),
                text = WORD_REGEX.replace(main, "").trim(),
                words = parseWords(main, begin, end),
                translation = translation.orEmpty(),
            )
        }

        return Parsed(lines, plainLines, wordLevel, hasTranslation)
    }

    /** `<mm:ss.xx>` 切词：每个词的时间取下一个标签的时间，最后一个词用行结束时间 */
    private fun parseWords(text: String, lineBegin: Long, lineEnd: Long): List<LyricWord>? {
        val markers = WORD_REGEX.findAll(text).toList()
        if (markers.size < 2) return null

        val words = mutableListOf<LyricWord>()
        markers.forEachIndexed { index, marker ->
            val contentStart = marker.range.last + 1
            val contentEnd = markers.getOrNull(index + 1)?.range?.first ?: text.length
            if (contentEnd <= contentStart) return@forEachIndexed

            val word = text.substring(contentStart, contentEnd)
            if (word.isEmpty()) return@forEachIndexed

            val begin = toMillis(
                marker.groupValues[1], marker.groupValues[2], marker.groupValues[3]
            )
            val end = markers.getOrNull(index + 1)?.let {
                toMillis(it.groupValues[1], it.groupValues[2], it.groupValues[3])
            } ?: lineEnd

            words += LyricWord(
                begin = begin.coerceAtLeast(lineBegin),
                end = end.coerceIn(begin, lineEnd.coerceAtLeast(begin)),
                text = word,
            )
        }
        return words.ifEmpty { null }
    }

    private fun toMillis(minute: String, second: String, fraction: String): Long {
        val m = minute.toLongOrNull() ?: 0L
        val s = second.toLongOrNull() ?: 0L
        val f = fraction.toLongOrNull() ?: 0L
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> f * 100
            2 -> f * 10
            else -> f
        }
        return m * 60_000 + s * 1_000 + millis
    }
}
