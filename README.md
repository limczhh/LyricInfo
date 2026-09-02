# LyricInfo

LyricInfo 是一个基于 libxposed API 102 的 Xposed/LSPosed 模块。它将音乐应用中的歌曲信息和歌词整理为统一的 `lyricInfo` JSON，并写入 `MediaMetadata.extras.lyricInfo`，供 ColorOS 锁屏岛等歌词组件读取。

## lyricInfo JSON

### 基础字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `songName` | `string` | 歌曲名 |
| `artist` | `string` | 歌手 |
| `lyric` | `string` | 逐行 LRC |
| `songId` | `string` | 稳定歌曲 ID |

### 可选字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `album` | `string` | 专辑 |
| `rawLyric` | `string` | 增强逐字时间轴或其他格式 |
| `translation` | `string` | 独立翻译歌词 |
| `roma` | `string` | 独立罗马音歌词 |

字段规则：

- `lyric` 永远表示原文逐行歌词，不能混入翻译或罗马音。
- `rawLyric` 只表示原文的增强数据，不用于承载翻译或罗马音。
- `translation` 和 `roma` 是相互独立的歌词 lane，不能追加到 `lyric`，也不能彼此合并。
- 可选字段只有在内容有效且非空时才写入 JSON。

### 完整示例

```json
{
  "songName": "歌曲名",
  "artist": "歌手",
  "lyric": "[00:10.000]原文歌词",
  "songId": "12345",
  "album": "专辑名",
  "rawLyric": "[00:10.000]<00:10.000>原文歌词",
  "translation": "[00:10.000]翻译歌词",
  "roma": "[00:10.000]罗马音歌词"
}
```

### 最小有效示例

没有专辑、逐字时间轴、翻译或罗马音时，只输出有效的基础字段：

```json
{
  "songName": "歌曲名",
  "artist": "歌手",
  "lyric": "[00:10.000]原文歌词"
}
```


## 已适配播放器

当前完整版已适配以下播放器：

| 应用 | 包名 |
| --- | --- |
| 网易云音乐 | `com.netease.cloudmusic` |
| 荣耀定制版网易云 | `com.hihonor.cloudmusic` |
| QQ 音乐 | `com.tencent.qqmusic` |
| 酷狗音乐 | `com.kugou.android` |
| 小米音乐 | `com.miui.player` |
| 汽水音乐 | `com.luna.music` |
| 椒盐音乐 | `com.salt.music` |
| LX Music | `cn.toside.music.mobile` |
| IKun Music | `com.ikunshare.music.mobile` |

另有 `:lite` 精简版本，用于触发播放器自身针对 ColorOS 锁屏岛的歌词输出逻辑，用户可在 LSPosed 管理器中自由勾选任意播放器进行尝试。

详见 [适配情况](docs/supported-apps.md)。
## 构建

### 编译完整版

```bash
./gradlew.bat :app:assembleDebug
```

### 编译精简版

```bash
./gradlew.bat :lite:assembleDebug
```

---

- [ColorOS-Live-Lyrics-Bridge](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge)
- [saltplayer_color_ex](https://github.com/CCCC-L/saltplayer_color_ex)
- [LyricProvider](https://github.com/tomakino/LyricProvider)
- [SuperLyric](https://github.com/HChenX/SuperLyric)
