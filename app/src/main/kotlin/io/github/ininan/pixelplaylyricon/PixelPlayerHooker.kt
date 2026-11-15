package io.github.ininan.pixelplaylyricon

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
 * 只依赖系统类 `android.media.session.MediaSession`——框架类不会被 R8 改名，
 * 所以无论 PixelPlayer 怎么混淆都不会失效。
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

    private fun sessionClass(): Class<*>? = runCatching {
        XposedHelpers.findClass("android.media.session.MediaSession", appClassLoader)
    }.getOrNull()

    private fun hookPlaybackState() {
        val clazz = sessionClass() ?: run {
            YLog.error(tag = Constants.TAG, msg = "找不到 MediaSession 类")
            return
        }
        hookSafely(clazz, "setPlaybackState", arrayOf(PlaybackState::class.java))
        hookSafely(clazz, "setPlaybackState", arrayOf(PlaybackState::class.java, Bundle::class.java))
    }

    private fun hookMetadata() {
        val clazz = sessionClass() ?: return
        hookSafely(clazz, "setMetadata", arrayOf(MediaMetadata::class.java))
    }

    private fun hookSafely(clazz: Class<*>, methodName: String, params: Array<Class<*>>) {
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
        val context = appContext ?: return
        val controller = runCatching { session.controller }.getOrNull() ?: return
        val metadata = runCatching { controller.metadata }.getOrNull()

        val mediaId = metadata?.description?.mediaId
        if (!mediaId.isNullOrBlank() && mediaId != lastMediaId) {
            lastMediaId = mediaId

            val resolved = LyricsResolver.resolve(context, mediaId)
            val parsed = resolved.parsed

            val info = MediaStoreInfo.query(context, mediaId)
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: info?.title.orEmpty()
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: info?.artist.orEmpty()
            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                ?.takeIf { it > 0 }
                ?: info?.duration
                ?: 0L

            if (parsed.lines.isEmpty()) {
                // 没有时间轴：退化为纯文本模式
                val plainText = parsed.plain.joinToString("\n").ifBlank { null }
                if (plainText != null) {
                    provider.player.sendText(plainText)
                }
                YLog.info(
                    tag = Constants.TAG,
                    msg = "无同步歌词 (source=${resolved.source}, 纯文本行数=${parsed.plain.size})"
                )
            } else {
                val song = Song(
                    id = mediaId,
                    name = title.orEmpty(),
                    artist = artist.orEmpty(),
                    duration = duration,
                    lyrics = parsed.lines,
                )
                val ok = provider.player.setSong(song)
                YLog.info(
                    tag = Constants.TAG,
                    msg = "setSong(id=$mediaId, source=${resolved.source}, 行数=${parsed.lines.size}, " +
                        "逐字=${parsed.hasWordLevel}, 翻译=${parsed.hasTranslation}) = $ok"
                )
            }

            provider.player.setDisplayTranslation(parsed.hasTranslation)
            provider.player.setDisplayRoma(false)
        }

        if (state != null) {
            provider.player.setPlaybackState(state)
        }
    }
}
