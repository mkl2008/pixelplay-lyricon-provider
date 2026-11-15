package io.github.mkl2008.pixelplaylyricon

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider

/**
 * 挂在 PixelPlayer 进程里的 hooker。
 *
 * 只依赖系统类 `android.media.session.MediaSession`——它是框架类，R8 不会改名，
 * 所以无论 PixelPlayer 怎么混淆都能稳定工作。
 */
open class PixelPlayerHooker : YukiBaseHooker() {

    private var provider: LyriconProvider? = null
    private var lastMediaId: String? = null

    override fun onHook() {
        YLog.info(tag = Constants.TAG, msg = "进程: $processName")

        onAppLifecycle {
            onCreate {
                initProvider()
            }
        }

        hookPlaybackState()
        hookMetadata()
    }

    // ------------------------------------------------------------- provider

    private fun initProvider() {
        val context = appContext ?: run {
            YLog.error(tag = Constants.TAG, msg = "appContext 为空，provider 无法创建")
            return
        }
        provider = runCatching {
            LyriconFactory.createProvider(
                context = context,
                providerPackageName = Constants.MODULE_PACKAGE,
                playerPackageName = Constants.TARGET_PACKAGE,
            ).apply {
                val ok = register()
                YLog.info(tag = Constants.TAG, msg = "register() = $ok")
            }
        }.onFailure {
            YLog.error(tag = Constants.TAG, msg = "创建 provider 失败: ${it.message}")
        }.getOrNull()
    }

    // ------------------------------------------------------------- hooks

    private fun hookPlaybackState() {
        val sessionClass = runCatching {
            XposedHelpers.findClass("android.media.session.MediaSession", appClassLoader)
        }.getOrNull() ?: run {
            YLog.error(tag = Constants.TAG, msg = "找不到 MediaSession 类")
            return
        }

        // API 21 版本
        hookSafely(sessionClass, "setPlaybackState", arrayOf(PlaybackState::class.java))
        // API 22+ 带 extras 的重载
        hookSafely(sessionClass, "setPlaybackState", arrayOf(PlaybackState::class.java, Bundle::class.java))
    }

    private fun hookMetadata() {
        val sessionClass = runCatching {
            XposedHelpers.findClass("android.media.session.MediaSession", appClassLoader)
        }.getOrNull() ?: return
        hookSafely(sessionClass, "setMetadata", arrayOf(MediaMetadata::class.java))
    }

    private fun hookSafely(
        clazz: Class<*>,
        methodName: String,
        params: Array<Class<*>>,
    ) {
        runCatching {
            XposedHelpers.findAndHookMethod(clazz, methodName, *params, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val session = param.thisObject as? MediaSession ?: return
                        val state = param.args.firstOrNull() as? PlaybackState
                        publish(session, state)
                    }.onFailure {
                        YLog.error(tag = Constants.TAG, msg = "hook $methodName 异常: ${it.message}")
                    }
                }
            })
            YLog.info(tag = Constants.TAG, msg = "已 hook MediaSession.$methodName(${params.size} 参)")
        }.onFailure {
            YLog.warn(tag = Constants.TAG, msg = "hook $methodName 失败: ${it.message}")
        }
    }

    // ------------------------------------------------------------- 数据推送

    private fun publish(session: MediaSession, state: PlaybackState?) {
        val provider = provider ?: return
        val controller = runCatching { session.controller }.getOrNull() ?: return
        val metadata = runCatching { controller.metadata }.getOrNull()

        val mediaId = metadata?.description?.mediaId
        if (!mediaId.isNullOrBlank() && mediaId != lastMediaId) {
            lastMediaId = mediaId
            val song = buildSong(mediaId, metadata)
            val ok = provider.player.setSong(song)
            YLog.info(
                tag = Constants.TAG,
                msg = "setSong(id=$mediaId, 歌词行数=${song.lyrics?.size ?: 0}) = $ok"
            )
        }

        if (state != null) {
            provider.player.setPlaybackState(state)
        }
    }

    private fun buildSong(mediaId: String, metadata: MediaMetadata?): Song {
        val context = appContext
        val rawLyrics = if (context != null) LyricsStore.query(context, mediaId) else null
        val lines = LrcParser.parse(rawLyrics)

        return Song(
            id = mediaId,
            name = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            lyrics = lines,
        )
    }
}
