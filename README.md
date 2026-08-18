# LyricInfo

LyricInfo 是一个基于 libxposed API 102 的 Xposed/LSPosed 模块，针对不同音乐应用获取整首歌词并注入到 `MediaMetadata.extras.lyricInfo` 字段，任何有通知权限的应用都可以读取。


## 原理

### 完整版
1. Hook 音乐应用的 `MediaMetadata.Builder.build()` 或 `MediaSession.setMetadata()`。
2. 从 Provider 自身的歌曲 ID、hash、内部对象或本地缓存确定当前歌曲。
3. 通过音乐应用的 API 或内部歌词类获取整首歌词和歌曲信息。
4. 将歌词统一转换为标准格式（elrc/lrc），然后写入 `MediaMetadata.extras.lyricInfo`。

### 精简版
网易云音乐、QQ音乐等软件已经原生支持了 ColorOS 16 的锁屏岛歌词功能，但它们内部会判断系统属性 `ro.build.version.oplus.api` 是否 `>= 37`。Lite 模块通过 Hook 并伪装这一系统属性的值，使音乐应用认为自己运行在 ColorOS 16+ 的系统上，从而自动触发它们自身的官方歌词输出，原生写入 `lyricInfo` 字段，实现极其稳定的“零注入、免维护”歌词获取。

---

## lyricInfo 格式

### 完整版格式
完整版统一转换后的格式：
```json
{
  "songName": "歌名",
  "artist": "歌手",
  "album": "专辑",
  "songId": "12345",
  "lyric": "[00:16.440]<00:16.440>歌<00:16.800>词\n[00:16.440]翻译",
  "format": "elrc",
  "translation": "lrc"
}
```

### 精简版原生格式
取决于音乐软件原生输出的格式（例如网易云），QQ音乐还有 `noLyric` , `lyricType` , `transLyric` , `txtlyric` 等内容：
```json
{
  "lyric": "[00:16.44]歌词",
  "songName": "歌名",
  "artist": "歌手"
}
```

---

## 支持的应用

### 完整版 (`:app`) 支持列表
| 应用 | 包名 | 歌词格式 | 获取方式 |
|------|------|----------|----------|
| 网易云音乐 | `com.netease.cloudmusic` | YRC → elrc | EApi 加密接口 |
| 荣耀定制版网易云 | `com.hihonor.cloudmusic` | YRC → elrc | 同上 |
| QQ 音乐 | `com.tencent.qqmusic` | QRC → elrc | 3DES 加密接口 |
| 酷狗音乐 | `com.kugou.android` | KRC/LRC → elrc/lrc | 请求歌词接口并解析 |
| 小米音乐 | `com.miui.player` | 内部解析 → elrc/lrc | Hook 内部歌词引擎 |
| 汽水音乐 | `com.luna.music` | KRC → elrc | Hook `Track`/`TrackLyric`，本地 KRC 缓存兜底 |
| 椒盐音乐 | `com.salt.music` | 自动检测 → elrc/lrc | DexKit hook |
| LX Music | `cn.toside.music.mobile` | 自动检测 → elrc/lrc | Hook 内部歌词类 |
| IKun Music | `com.ikunshare.music.mobile` | 自动检测 → elrc/lrc | 同上 |

### 精简版 (`:lite`) 支持列表
* **理论上支持任何官方已针对 ColorOS 16 锁屏岛开发了歌词下发逻辑的音乐播放器**（包括网易云音乐、QQ音乐等）。
* 由于使用动态作用域，用户可在 LSPosed 管理器中自由勾选任意播放器进行尝试。

详见 [适配情况](docs/supported-apps.md)。

---

## 架构

```
LyricInfo/
├── app/                         ← 完整版模块 (com.lidesheng.lyricinfo)
│   ├── src/main/java/.../
│   │   ├── HookEntry.kt         ← 模块入口，分发至各大 App Provider
│   │   ├── core/                ← 核心格式检测及转换转换器
│   │   └── providers/           ← 针对各家音乐 API/缓存/内部 hook 的适配实现
├── lite/                        ← 精简版模块 (com.lidesheng.lyricinfo.lite)
│   ├── src/main/java/.../
│   │   └── HookEntry.kt         ← 极简入口，仅 Hook SystemProperties
```

---

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
