# Syncra

一个 Android 悬浮翻译器，可以覆盖在任何 App 上——包括 Twitter/X 私聊这种「长按只弹菜单、根本没法选中文字」的场景。

## 使用文档

完整的图文步骤见 **[docs/USAGE.md](docs/USAGE.md)**，包含：安装、OpenRouter Key 配置、悬浮窗与无障碍权限、悬浮球操作、读屏翻译、输入框翻译、自动模式、常见问题排查、隐私说明和已知限制。

这份文档是写给完全不懂技术的普通用户的；也可以把仓库链接直接发给 AI，让它读文档和源码后回答你的问题。

## 功能

- **读屏翻译**：通过无障碍服务读取屏幕上的消息文字，把译文直接画在原文位置上。
- **输入框翻译**：读取你正在输入的内容，翻成对方的语言后写回输入框。
- **自动模式**：自动识别屏幕内容、跟随滚动，有新消息或新输入时自动翻译。
- **自带模型**：走 OpenRouter，默认优先免费模型，不内置任何 API Key。
- **本地缓存**：翻译结果存在手机上，同一句不会重复请求。
- **四种界面语言**：中文 / English / 日本語 / 한국어。
- **约 3 MB**：不打包离线翻译模型。

## 为什么必须开无障碍？

Android 不允许一个 App 读取另一个 App 的界面内容，也不允许写进别的 App 的输入框。
能同时做到这两件事的官方通道只有无障碍服务。Syncra 只用它来：

1. 读取当前屏幕上的文字（翻译收到的消息）；
2. 读取并替换输入框里的文字（翻译你要发出的消息）。

它**不会**自动点按钮、跳转页面，也不会在后台收集数据。
唯一的网络请求发往你自己配置的 OpenRouter 地址。

## 隐私

- OpenRouter API Key 只存在本机。
- 消息文字会发给 OpenRouter 做翻译，除此之外不发送任何内容。
- 没有统计、没有广告、没有埋点。
- 翻译缓存存在本地 `filesDir/translation_cache.json`。

## 安装

从 Releases 下载 APK，或者自己编译。

要求：Android 10（API 29）及以上。

1. 安装 APK。
2. 打开 App，填入 OpenRouter API Key 并保存。
3. 授予「显示在其他应用上层」。
4. 在系统设置里开启 Syncra 的无障碍服务。
5. 点「启动悬浮球」。

## 构建

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

需要 JDK 17 和 Android SDK 36。

## 已知限制

- 翻译质量取决于所选模型。
- 部分 App（Flutter、游戏等自绘界面）不把文字暴露给无障碍，无法翻译。
- 译文层是覆盖在界面上的悬浮层，不是真正插入 App 内部。
- Release 包用项目自己的 keystore 签名（`CN=Syncra`）。请妥善保管 keystore 和密码，丢了就无法再发布能覆盖安装的更新。

## 许可

Apache-2.0，见 [LICENSE](LICENSE)。

## 致谢

基于 [BubbleTranslate](https://github.com/vipinthakur25/bubble-translate)（作者 Vipin，Apache-2.0）改造。

原版权声明和本项目修改说明见 [NOTICE](NOTICE)。

角色形象为同人二创。本项目与 DeepSeek、X Corp 均无关联，也未获其背书。
