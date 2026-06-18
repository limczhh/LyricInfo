# 适配情况

## 已适配

| 应用 | 包名 | 歌词格式 | 获取方式 |
|------|------|----------|----------|
| 网易云音乐 | `com.netease.cloudmusic` | YRC → elrc | EApi 加密接口 |
| 荣耀定制版网易云 | `com.hihonor.cloudmusic` | YRC → elrc | 同上 |
| QQ 音乐 | `com.tencent.qqmusic` | QRC → elrc | 3DES 加密接口 |
| 汽水音乐 | `com.luna.music` | KRC → elrc | 读取本地缓存 KRC 文件 |
| 椒盐音乐 | `com.salt.music` | 自动检测 → elrc/lrc | DexKit hook 内部歌词类 |

## 待适配

| 应用 | 包名 | 备注 |
|------|------|------|
| 酷狗音乐 | `com.kugou.android` |   |
| 酷我音乐 | `cn.kuwo.player` |   |
| Apple Music | `com.apple.android.music` |   |

## 适配新应用

1. 在 `providers/` 下创建目录，实现 `BaseLyricProvider`（有 API 时）或直接实现 `LyricProvider`（需要 hook 时）
2. 在 `HookEntry.kt` 注册 Provider
3. 在 `scope.list` 添加目标包名
