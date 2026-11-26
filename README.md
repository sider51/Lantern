# Lantern

[English](README.en.md)

<img src="brand/Lantern.png" alt="Lantern" width="160">

Lanternは、短編小説やSSをスマートフォンで書くためのAndroidアプリです。原稿を整理し、pixivやXなどへの投稿に向けてテキストや画像を用意できます。

- ルビ、傍点、太字、斜体、改ページに対応
- 自動保存、Undo・Redo、入力補助バー
- 階層フォルダ、検索、ゴミ箱、バックアップ
- 投稿向けのテキスト出力と、横書き・縦書きの画像出力

![ライブラリ](brand/screenshots/library.png)
![画像出力の設定](brand/screenshots/image-export.png)

Android 10以降に対応します。[最新版のAPKをダウンロード](https://github.com/sider51/Lantern/releases/latest)してインストールできます。

原稿は初期状態でアプリ内に保存され、Androidのフォルダ選択から保存先を変更できます。アプリ自体はオフラインで動作します。端末変更やアプリ削除の前には、別の場所へバックアップを保存してください。Androidのシステムバックアップは無効です。

## ビルド

JDK 17、Android SDK Platform 37.1、Build-Tools 36.0.0を用意し、`JAVA_HOME` と `ANDROID_HOME` を設定します。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

Debug APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。Release APKには別途正式な署名が必要です。

正式署名には既存の鍵を安全な場所に保管し、`LANTERN_KEYSTORE`、`LANTERN_STORE_PASSWORD`、`LANTERN_KEY_ALIAS`、`LANTERN_KEY_PASSWORD` をビルド時の環境変数として設定します。鍵とパスワードはリポジトリへ含めないでください。

Debug版と正式版の署名が異なる場合、上書きインストールできません。先にアプリのバックアップを外部に保存し、バックアップを確認してからDebug版をアンインストールし、正式版をインストールして復元してください。アンインストールするとアプリ内データは削除されます。

[プライバシー](PRIVACY.md) · [第三者ライセンス](THIRD_PARTY_NOTICES.md) · [不具合の報告](https://github.com/sider51/Lantern/issues)

ソースコードは [MIT License](LICENSE) です。公式ロゴとブランド素材は対象外で、権利を留保します。対象範囲は [ブランド素材の権利](BRAND_LICENSE.md) を参照してください。
