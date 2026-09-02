# 适配情况

根据所使用的模块版本适配情况有所不同。

## 精简版（`:lite`）

精简版具体输出内容取决于播放器自身的 `lyricInfo` 数据。

### 已确认支持

- **网易云音乐**（`com.netease.cloudmusic`）
- **QQ 音乐**（`com.tencent.qqmusic`）

理论上，其他原生集成了 OPPO 锁屏歌词特性的播放器也可能支持，但实际结果取决于播放器自身。

## 完整版（`:app`）

### 已适配

| 应用 | 包名 | 支持的内容 |
| --- | --- | --- |
| 网易云音乐 | `com.netease.cloudmusic` | 逐字歌词、翻译 |
| 荣耀定制版网易云 | `com.hihonor.cloudmusic` | 逐字歌词、翻译 |
| QQ 音乐 | `com.tencent.qqmusic` | 逐字歌词、翻译 |
| 酷狗音乐 | `com.kugou.android` | 逐字歌词、翻译 |
| 小米音乐 | `com.miui.player` | 逐字歌词 |
| 汽水音乐 | `com.luna.music` | 逐字歌词、翻译 |
| 椒盐音乐 | `com.salt.music` | 逐字歌词、翻译 |
| LX Music | `cn.toside.music.mobile` | 逐字歌词、翻译、罗马音 |
| IKun Music | `com.ikunshare.music.mobile` | 逐字歌词、翻译、罗马音 |

### 待适配

| 应用 | 包名 | 备注 |
| --- | --- | --- |
| 酷我音乐 | `cn.kuwo.player` | 未适配 |
| Apple Music | `com.apple.android.music` | 未适配 |

## 为完整版适配新应用

新增 Provider 时，需要确保其最终发布的歌词符合 lyricinfo json 字段约束，并在支持情况表中只填写已经实际支持的歌词内容：

1. 在 `app/src/main/java/com/lidesheng/lyricinfo/providers/` 下添加 Provider 实现。
2. 在 `app/src/main/java/com/lidesheng/lyricinfo/HookEntry.kt` 注册 Provider。
3. 在 `app/src/main/resources/META-INF/xposed/scope.list` 添加目标应用包名。
