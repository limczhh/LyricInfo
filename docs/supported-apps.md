# 适配情况

根据所使用的模块版本适配情况有所不同。

---

## 1. 精简版 (`:lite`) 适配情况

精简版通过系统属性伪装激活音乐软件**原生**的 ColorOS 16 锁屏岛下发逻辑。

### 已确认支持的应用
* **网易云音乐** (`com.netease.cloudmusic`)
* **酷狗音乐** (`com.kugou.android`)
* **QQ 音乐** (`com.tencent.qqmusic`)

理论上支持任何原生集成了 OPPO 锁屏歌词特性的软件。

---

## 2. 完整版 (`:app`) 适配情况

完整版通过逆向 API 或本地缓存主动解析并重组注入歌词。

### 已适配
| 应用 | 包名 | 歌词格式 | 获取方式 |
|------|------|----------|----------|
| 网易云音乐 | `com.netease.cloudmusic` | YRC → elrc | EApi 加密接口 |
| 荣耀定制版网易云 | `com.hihonor.cloudmusic` | YRC → elrc | 同上 |
| QQ 音乐 | `com.tencent.qqmusic` | QRC → elrc | 3DES 加密接口 |
| 汽水音乐 | `com.luna.music` | KRC → elrc | 读取本地缓存 KRC 文件 |
| 椒盐音乐 | `com.salt.music` | 自动检测 → elrc/lrc | DexKit hook 内部歌词类 |

### 待适配
| 应用 | 包名 | 备注 |
|------|------|------|
| 酷狗音乐 | `com.kugou.android` | 完整版尚未实现主动解析逻辑 |
| 酷我音乐 | `cn.kuwo.player` | 完整版尚未实现主动解析逻辑 |
| Apple Music | `com.apple.android.music` | 完整版尚未实现主动解析逻辑 |

---

## 3. 为完整版适配新应用

如果您希望在完整版中添加对新播放器的解析：
1. 在 `app/src/main/java/com/lidesheng/lyricinfo/providers/` 下创建对应播放器的目录，继承并实现 `BaseLyricProvider`（通过 API 获取歌词时）或 `LyricProvider` 接口（需要 hook 获取时）。
2. 在 `app/src/main/java/com/lidesheng/lyricinfo/HookEntry.kt` 中的 `providers` 列表中注册该新实现的 Provider。
3. 在 `app/src/main/resources/META-INF/xposed/scope.list` 中添加目标应用的包名。