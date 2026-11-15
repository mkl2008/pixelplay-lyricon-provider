package io.github.ininan.pixelplaylyricon

object Constants {

    /** 本模块自身的包名，必须与 build.gradle.kts 里的 applicationId 保持一致 */
    const val MODULE_PACKAGE = "io.github.ininan.pixelplaylyricon"

    /** 被 hook 的目标应用 */
    const val TARGET_PACKAGE = "com.theveloper.pixelplay"

    const val TAG = "PixelPlayLyricon"

    /** PixelPlayer 的 Room 数据库文件名（源码 di/AppModule.kt） */
    const val DB_NAME = "pixelplay_database"

    /** 歌词表（源码 data/database/LyricsEntity.kt） */
    const val DB_TABLE_LYRICS = "lyrics"
}
