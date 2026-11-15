# PixelPlay Lyricon Provider

给 [PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer) 用的[词幕](https://github.com/tomakino/lyricon)歌词提供器模块。

PixelPlayer 本身没有接入词幕的 Provider 协议，所以状态栏歌词是空的。这个模块通过 LSPosed 注入到
PixelPlayer 进程内，把当前播放状态和歌词推送给词幕 —— 不需要修改 PixelPlayer 本体，也不需要重新打包原应用。

## 它怎么工作

- **播放状态**：hook `android.media.session.MediaSession`，直接透传系统 `PlaybackState`，
  词幕据此计算实时进度
- **歌曲信息**：读 `MediaMetadata` 的 `mediaId` / 标题 / 歌手 / 时长
- **歌词来源**：在目标进程内直接读取 PixelPlayer 自己的 Room 数据库
  （`pixelplay_database` 的 `lyrics` 表），用 `mediaId` 转成 `songId` 查询
- **逐字歌词**：解析库里的 LRC 文本，含 `<mm:ss.xx>` 逐字标签
- **输出**：调用词幕官方 SDK `io.github.proify.lyricon:provider`

### 为什么不用 hook 应用内部类

PixelPlayer 的 release 包开启了 R8 混淆，类名方法名全被改写，而且不提供 mapping。
但 **SQL 表名、列名、字符串常量不会被混淆**，所以走数据库这条路不受版本更新影响。
唯一的依赖是系统框架类 `MediaSession`，它永远不会被混淆。

## 环境要求

- Android 11+（PixelPlayer minSdk 30）
- 已安装 LSPosed（或兼容的 Xposed 框架）
- 已安装词幕主体

## 安装

1. 下载 Releases 里的 APK 并安装
2. 在 LSPosed 里启用本模块，**作用域勾选 PixelPlayer**
3. 强行停止 PixelPlayer，重新打开
4. 播放一首带歌词的歌，状态栏应出现歌词

## 兼容性

| 项目 | 版本 |
|---|---|
| PixelPlayer | 0.7.5-beta（arm64） |
| 词幕 | 1.0.20 |
| 词幕 Provider SDK | 0.1.70 |

## 已知限制

- **内嵌歌词读不到**：PixelPlayer 的 `Song.lyrics` 字段（从音频文件标签读出的内嵌歌词）优先级高于数据库，
  这部分数据模块取不到。这类歌会显示歌名但没有歌词，后续计划回退到 LRCLIB 匹配。
- **纯文本歌词**：只有行级时间轴的 LRC 也能显示，但没有逐字效果。
- **翻译/罗马音**：当前版本未接入。

## 构建

CI 见 `.github/workflows/build.yml`，推送后自动产出 debug APK。

## 许可证

Apache-2.0。本项目为独立实现，不包含 PixelPlayer 的任何源码或资源。
