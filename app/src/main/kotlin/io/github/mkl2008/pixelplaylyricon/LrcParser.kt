package io.github.mkl2008.pixelplaylyricon

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine

/**
 * 极简 LRC 解析器。
 *
 * 支持：
 *  - 行级时间轴 `[mm:ss.xx]歌词`
 *  - 逐字时间轴 `[mm:ss.xx]<mm:ss.xx>字<mm:ss.xx>字`
 *
 * PixelPlayer 的逐字标签格式来自其源码 `utils/LyricsUtils.kt`：
 *   LRC_WORD_REGEX = "<(\\d{2}):(\\d{2})[.:](\\d{2,3})>([^<]*)"
 */
object LrcParser {

    private const val DEFAULT_LINE_DURATION_MS = 5_000L

    private val LINE_REGEX = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?](.*)$""")
    private val WORD_REGEX = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META_REGEX = Regex("""^\[[a-zA-Z#]+:.*]$""")

    fun parse(raw: String?): List<RichLyricLine> {
        if (raw.isNullOrBlank()) return emptyList()

        // 一次性把 [mm:ss.xx] 行收集成 (begin, text)
        val rawLines = mutableListOf<Pair<Long, String>>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || META_REGEX.matches(trimmed)) return@forEachIndexed
            val match = LINE_REGEX.find(trimmed) ?: return@forEachIndexed
            val (minute, second, fraction, text) = match.destructured
            rawLines += toMillis(minute, second, fraction) to text
        }
        if (rawLines.isEmpty()) return emptyList()

        rawLines.sortBy { it.first }

        return rawLines.mapIndexed { index, (begin, text) ->
            val end = rawLines.getOrNull(index + 1)?.first ?: (begin + DEFAULT_LINE_DURATION_MS)
            RichLyricLine(
                begin = begin,
                end = end.coerceAtLeast(begin),
                text = WORD_REGEX.replace(text, "").trim(),
                words = parseWords(text, begin, end),
            )
        }
    }

    /** 把一行里的 `<mm:ss.xx>` 标签切成词，end 用下一个词的时间，最后一个词用行结束时间 */
    private fun parseWords(text: String, lineBegin: Long, lineEnd: Long): List<LyricWord>? {
        val markers = WORD_REGEX.findAll(text).toList()
        if (markers.size < 2) return null   // 单标签或无标签不算逐字

        val words = mutableListOf<LyricWord>()
        markers.forEachIndexed { index, marker ->
            val (minute, second, fraction) = marker.destructured
            val begin = toMillis(minute, second, fraction)
            val contentStart = marker.range.last + 1
            val contentEnd = markers.getOrNull(index + 1)?.range?.first ?: text.length
            if (contentEnd <= contentStart) return@forEachIndexed
            val word = text.substring(contentStart, contentEnd)
            if (word.isEmpty()) return@forEachIndexed

            val end = markers.getOrNull(index + 1)?.let {
                val (m, s, f) = it.destructured
                toMillis(m, s, f)
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
        // 两位小数按 10ms 计，三位按 1ms 计
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> f * 100
            2 -> f * 10
            else -> f
        }
        return m * 60_000 + s * 1_000 + millis
    }
}
