# Syncra

A floating translator for Android that works on top of any app — including Twitter/X DMs, where text selection is not available.

## Documentation

- **[Full usage guide (Chinese, 中文使用攻略)](docs/USAGE.md)** — step-by-step install, permissions, model choice, troubleshooting, and the author's practical tips. Written for non-technical users, and structured so an AI assistant can answer user questions from the repo directly.
- [中文说明](README.zh.md)

## Features

- **Read-screen translation** — reads visible messages through the Accessibility API and draws the translation directly over the original text.
- **Input translation** — reads what you type, translates it into the other person's language, and writes it back into the input box.
- **Auto mode** — watches the screen, follows scrolling, and translates new messages or new input automatically.
- **Bring your own model** — uses OpenRouter; free models are selected by default. No API key is bundled.
- **Local cache** — translated text is stored on device, so the same sentence is never sent twice.
- **Four UI languages** — 中文 / English / 日本語 / 한국어.
- **~3 MB** — no on-device translation models.

## Why does it need Accessibility?

Android does not allow one app to read another app's screen content or write into another app's input box.
The only supported channel for both is the Accessibility service. Syncra uses it to:

1. read the text currently on screen (to translate incoming messages);
2. read and replace the text in the focused input box (to translate outgoing messages).

It does **not** click buttons, navigate apps, or collect data in the background.
The only network requests it makes go to the OpenRouter endpoint you configure.

## Privacy

- Your OpenRouter API key is stored locally on the device.
- Message text is sent to the OpenRouter API for translation — nothing else.
- No analytics, no ads, no telemetry.
- The translation cache lives in `filesDir/translation_cache.json`.

## Install

Download the APK from Releases, or build it yourself.

Requirements: Android 10 (API 29) or newer.

1. Install the APK.
2. Open the app, paste your OpenRouter API key, and save.
3. Grant "Display over other apps".
4. Enable the Accessibility service for Syncra.
5. Tap "Start floating ball".

## Build

```bash
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

Requires JDK 17 and Android SDK 36.

## Known limitations

- Translation quality depends on the selected model.
- Some apps use custom rendering (Flutter, games) and do not expose text to Accessibility — translation will not work there.
- The translation layer is an overlay drawn on top of the app; it is not a real in-app insertion.
- Release builds currently use the debug signing key; replace it with your own keystore before publishing.

## License

Apache-2.0. See [LICENSE](LICENSE).

## Credits

Based on [BubbleTranslate](https://github.com/vipinthakur25/bubble-translate) by Vipin (Apache-2.0).

The mascot character is an original fan design. This project is not affiliated with, endorsed by, or sponsored by DeepSeek or X Corp.
