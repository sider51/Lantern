# Lantern

[日本語](README.md)

<img src="brand/Lantern.png" alt="Lantern logo" width="128">

Lantern is an Android app for writing short fiction and fan fiction on your phone. Add ruby and emphasis marks as you write, organize drafts in folders, and prepare text or images for sharing on services such as pixiv and X.

## Download

[Download the latest APK from GitHub Releases](https://github.com/sider51/Lantern/releases/latest). Lantern requires **Android 10 or later**.

## Features

### Writing

- A horizontal editor that lets you start writing without a title
- Ruby, emphasis marks, bold, italic, and page breaks
- Autosave, undo and redo, character count, and editing position restoration
- A keyboard toolbar for symbols, formatting, and editing, plus bracket completion
- Adjustable editor font, text size, line spacing, and side margins
- Support for Android keyboards and the system spell checker

### Organizing drafts

- An Inbox and hierarchical folders, with move, duplicate, search, and sort actions
- Multiple selection and configurable left and right swipe actions
- Trash restoration and configurable retention
- Backup and restore for drafts, folders, and settings, plus individual draft JSON import and export

### Text export

Export as plain text or prepare text for pixiv, X, Kakuyomu, Shōsetsuka ni Narō, Hameln, and Everystar. Preview before copying or sharing, and choose whether to include the title, indent paragraphs, or reduce blank lines. When a destination does not support a format, Lantern removes the decoration and keeps the text.

### Image export

Preview horizontal or vertical pages and save or share them as PNG or JPEG. Ruby, emphasis marks, and automatic and manual page breaks are supported. Adjust the font, text size, character and line spacing, margins, colors, and resolution. Custom fonts, watermarks, and export presets are also available.

## Storage and backup

Drafts are stored inside the app by default. You can also choose a folder using Android's storage picker. Lantern itself works offline.

Android system backup is disabled. **Before changing devices or uninstalling, use Lantern's backup feature to save a copy elsewhere and verify the backup file.** Uninstalling removes app-local data.

## Build

Install JDK 17, Android SDK Platform 37.1, and Build-Tools 36.0.0, then set `JAVA_HOME` and `ANDROID_HOME`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. To sign a release build, set `LANTERN_KEYSTORE`, `LANTERN_STORE_PASSWORD`, `LANTERN_KEY_ALIAS`, and `LANTERN_KEY_PASSWORD` as environment variables. Keep the key and passwords secure and outside the repository.

If an installed debug build has a different signature, it cannot be updated directly. First export and verify a backup, then uninstall the debug build, install the signed release, and restore the backup.

## License and feedback

Source code is available under the [MIT License](LICENSE). Official logos and brand assets are excluded and all rights are reserved. See [BRAND_LICENSE.md](BRAND_LICENSE.md) and [third-party notices](THIRD_PARTY_NOTICES.md) for details.

[Privacy](PRIVACY.md) · [Report an issue or suggest an improvement](https://github.com/sider51/Lantern/issues)
