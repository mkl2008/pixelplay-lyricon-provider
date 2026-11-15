package io.github.ininan.pixelplaylyricon

import java.io.File
import java.nio.charset.Charset

/**
 * 读取音频文件里的**内嵌歌词**。
 *
 * PixelPlayer 的 `Song.lyrics` 字段来自 MediaStore 扫描出来的内嵌歌词，但它不进数据库，
 * 所以模块只能自己再从文件里读一遍。支持：
 *
 *  - MP3：ID3v2 的 `USLT` 帧
 *  - FLAC / OGG / Opus：Vorbis comment 里的 `LYRICS` / `UNSYNCEDLYRICS`
 *  - M4A / MP4：`©lyr` atom
 *  - 同目录同名 `.lrc` 边车文件
 *
 * 全部做了防御式处理：解析失败一律返回 null，不让 hook 崩。
 */
object EmbeddedLyrics {

    private const val MAX_SCAN_BYTES = 4 * 1024 * 1024

    fun read(file: File): String? {
        if (!file.exists() || !file.canRead()) return null

        // 1) 同名 .lrc 边车文件优先（内容最规整）
        sidecar(file)?.let { return it }

        // 2) 按扩展名解析
        val ext = file.extension.lowercase()
        return runCatching {
            when (ext) {
                "mp3" -> readId3(file)
                "flac", "ogg", "opus" -> readVorbis(file) ?: genericScan(file)
                "m4a", "mp4", "aac" -> readMp4(file) ?: genericScan(file)
                else -> genericScan(file)
            }
        }.getOrNull()
    }

    private fun sidecar(file: File): String? = runCatching {
        val base = file.nameWithoutExtension
        val dir = file.parentFile ?: return null
        val candidate = File(dir, "$base.lrc")
        if (candidate.exists() && candidate.length() < MAX_SCAN_BYTES) candidate.readText() else null
    }.getOrNull()

    // ---------------------------------------------------------------- MP3 / ID3v2

    private fun readId3(file: File): String? = runCatching {
        val size = minOf(file.length(), MAX_SCAN_BYTES.toLong()).toInt()
        val buf = ByteArray(size)
        file.inputStream().use { it.read(buf) }
        if (size < 10 || buf[0] != 'I'.code.toByte() || buf[1] != 'D'.code.toByte() || buf[2] != '3'.code.toByte()) return null

        val major = buf[3].toInt()
        val flags = buf[5].toInt()
        var offset = 10
        if (flags and 0x40 != 0 && size > offset + 4) {
            // extended header
            val extSize = beInt(buf, offset)
            offset += 4 + extSize
        }

        val idLen = 4
        val headerLen = if (major >= 4) 10 else 10
        while (offset + headerLen <= size) {
            val id = String(buf, offset, idLen, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break
            val frameSize = if (major >= 4) syncsafe(buf, offset + 4) else beInt(buf, offset + 4)
            val frameStart = offset + headerLen
            val frameEnd = (frameStart + frameSize).coerceAtMost(size)
            if (frameSize <= 0 || frameEnd <= frameStart) break

            if (id == "USLT" || id == "ULT") {
                val text = decodeId3Text(buf, frameStart, frameEnd)
                if (!text.isNullOrBlank()) return text
            }
            offset = frameEnd
        }
        null
    }.getOrNull()

    /** USLT: encoding(1) + lang(3) + descriptor(NUL 结尾) + 歌词正文 */
    private fun decodeId3Text(buf: ByteArray, start: Int, end: Int): String? {
        if (end <= start + 4) return null
        val encoding = buf[start].toInt()
        val body = start + 4

        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }

        var text = String(buf, body, end - body, charset)
        // 去掉 descriptor（第一个 NUL 之前是描述）
        val nul = text.indexOf('\u0000')
        if (nul >= 0) {
            val rest = text.substring(nul + 1)
            if (rest.isNotBlank()) text = rest
        }
        return text.replace("\u0000", "").trim().ifBlank { null }
    }

    // ---------------------------------------------------------------- FLAC / OGG

    private fun readVorbis(file: File): String? = runCatching {
        val size = minOf(file.length(), MAX_SCAN_BYTES.toLong()).toInt()
        val buf = ByteArray(size)
        file.inputStream().use { it.read(buf) }

        val comments = mutableListOf<String>()
        // 直接扫 "LYRICS=" / "UNSYNCEDLYRICS=" 这类注释（FLAC 与 OGG 都是明文存放）
        val text = String(buf, Charsets.ISO_8859_1)
        for (key in listOf("LYRICS=", "UNSYNCEDLYRICS=", "UNSYNCED LYRICS=")) {
            var idx = text.indexOf(key, ignoreCase = true)
            while (idx >= 0) {
                val startValue = idx + key.length
                val end = text.indexOf('\u0004', startValue).let {
                    if (it < 0) text.indexOf('\u0000', startValue).let { z ->
                        if (z < 0) minOf(text.length, startValue + 20000) else z
                    } else it
                }
                if (end > startValue) comments += text.substring(startValue, end)
                idx = text.indexOf(key, end)
            }
        }
        comments.maxByOrNull { it.length }?.let { decodeMaybeUtf8(it) }
    }.getOrNull()

    // ---------------------------------------------------------------- MP4 / M4A

    private fun readMp4(file: File): String? = runCatching {
        val size = minOf(file.length(), MAX_SCAN_BYTES.toLong()).toInt()
        val buf = ByteArray(size)
        file.inputStream().use { it.read(buf) }

        val marker = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
        val idx = indexOf(buf, marker) ?: return null

        // ©lyr 后面紧跟 data atom：size(4) 'data'(4) type(4) locale(4) payload
        val dataIdx = indexOf(buf, "data".toByteArray(Charsets.ISO_8859_1), idx, minOf(buf.size, idx + 64)) ?: return null
        val payloadStart = dataIdx + 4 + 8
        if (payloadStart >= buf.size) return null

        // 取到下一个 atom 边界或以可打印字符结尾
        var end = minOf(buf.size, payloadStart + 60000)
        while (end > payloadStart && buf[end - 1] == 0.toByte()) end--
        String(buf, payloadStart, end - payloadStart, Charsets.UTF_8).trim().ifBlank { null }
    }.getOrNull()

    // ---------------------------------------------------------------- 兜底

    /** 把文件开头当作文本扫一遍，找出看起来像 LRC 的片段 */
    private fun genericScan(file: File): String? = runCatching {
        val size = minOf(file.length(), MAX_SCAN_BYTES.toLong()).toInt()
        val buf = ByteArray(size)
        file.inputStream().use { it.read(buf) }
        val text = decodeMaybeUtf8(String(buf, Charsets.ISO_8859_1))
        val start = text.indexOf("[00:")
        val fallbackStart = if (start >= 0) start else text.indexOf("\n[0")
        if (fallbackStart < 0) return null
        val slice = text.substring(fallbackStart)
        val cleaned = slice.filter { it == '\n' || it == '\r' || it.code in 32..0x2FF }
        cleaned.ifBlank { null }
    }.getOrNull()

    // ---------------------------------------------------------------- 工具

    private fun decodeMaybeUtf8(raw: String): String = runCatching {
        String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    }.getOrDefault(raw)

    private fun beInt(buf: ByteArray, offset: Int): Int {
        if (offset + 4 > buf.size) return 0
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    private fun syncsafe(buf: ByteArray, offset: Int): Int {
        if (offset + 4 > buf.size) return 0
        return ((buf[offset].toInt() and 0x7F) shl 21) or
            ((buf[offset + 1].toInt() and 0x7F) shl 14) or
            ((buf[offset + 2].toInt() and 0x7F) shl 7) or
            (buf[offset + 3].toInt() and 0x7F)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0, to: Int = haystack.size): Int? {
        if (needle.isEmpty()) return null
        val limit = minOf(to, haystack.size) - needle.size
        for (i in from..limit) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return null
    }
}
