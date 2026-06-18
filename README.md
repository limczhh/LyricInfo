# LyricInfo

通过 Xposed 将歌词注入到 `MediaMetadata.extras.lyricInfo` 字段，任何有通知权限的应用都可以读取。

## 原理

OPPO ColorOS 16 锁屏岛歌词通过读取 `lyricInfo` 字段显示歌词，所以有这个模块的诞生。

1. Hook 音乐应用的 `MediaMetadata.Builder.build()`
2. 从音乐应用的歌词 API 或内部歌词类获取歌词
3. 将歌词统一转换为标准格式（elrc/lrc）
4. 写入 `MediaMetadata.extras.lyricInfo`
5. 任何有通知权限的应用可以通过 `MediaController.getMetadata()?.getString("lyricInfo")` 读取

## lyricInfo 格式

```json
{
  "songName": "歌名",
  "artist": "歌手",
  "songId": "12345",
  "lyric": "[00:16.440]<00:16.440>歌<00:16.800>词\n[00:16.440]翻译",
  "format": "elrc",
  "translation": "lrc"
}
```

- 目前的格式和 OPPO 读取的不同，比如 `format` 和 `translation` 是我个人新增的。
- `lyric` — 所有歌词（原文+翻译按时间戳交错）
- `format` — 歌词格式：`"lrc"`（逐行）或 `"elrc"`（增强逐字）
- `translation` — 翻译行的格式标识：`"lrc"` / `"elrc"` / `""`（无翻译）

## 支持的应用

| 应用 | 包名 | 歌词格式 | 获取方式 |
|------|------|----------|----------|
| 网易云音乐 | `com.netease.cloudmusic` | YRC → elrc | EApi 加密接口 |
| 荣耀定制版网易云 | `com.hihonor.cloudmusic` | YRC → elrc | 同上 |
| QQ 音乐 | `com.tencent.qqmusic` | QRC → elrc | 3DES 加密接口 |
| 椒盐音乐 | `com.salt.music` | 自动检测 → elrc/lrc | DexKit hook |

详见 [适配情况](docs/supported-apps.md)。

## 使用方法

1. 安装 APK
2. 在 LSPosed 中启用模块
3. 勾选需要注入歌词的音乐应用
4. 播放音乐，歌词自动注入

## 架构

```
app/src/main/java/com/lidesheng/lyricinfo/
├── HookEntry.kt                 ← 模块入口，按包名分发
├── core/
│   ├── LyricProvider.kt         ← 接口
│   ├── BaseLyricProvider.kt     ← 核心 hook + 注入逻辑
│   ├── LyricNormalizer.kt       ← 格式检测/转换/合并
│   ├── LyricResult.kt           ← 数据模型
│   └── LyricFileCache.kt        ← 文件缓存
└── providers/
    ├── netease/                  ← 网易云
    ├── qqmusic/                  ← QQ 音乐
    └── saltplayer/               ← 椒盐音乐（DexKit hook）
```

## 构建

```bash
./gradlew.bat assembleDebug
```

## 依赖

- libxposed API 102
- DexKit 2.2.0（用于查找混淆类）
- 无其他外部依赖（网络请求使用 HttpURLConnection）

## 参考项目

- [saltplayer_color_ex](https://github.com/CCCC-L/saltplayer_color_ex)
- [LyricProvider](https://github.com/tomakino/LyricProvider)