# Lantern

[English](README.en.md)

<img src="brand/Lantern.png" alt="Lanternのロゴ" width="128">

Lanternは、短編小説やSSをスマートフォンで書き、投稿の準備までできるAndroidアプリです。ルビや傍点を付けながら執筆し、原稿をフォルダで整理して、pixivやXなどに向けたテキストや画像を書き出せます。

## ダウンロード

[GitHub Releasesから最新版のAPKをダウンロード](https://github.com/sider51/Lantern/releases/latest)できます。対応環境は **Android 10以降** です。

## できること

### 執筆

- タイトルがなくても書き始められる横書きエディタ
- ルビ、傍点、太字、斜体、改ページ
- 自動保存、元に戻す・やり直す、文字数表示、編集位置の復元
- 記号・書式・編集操作を切り替えられる入力補助バーと、括弧の補完
- 本文のフォント、文字サイズ、行間、左右余白の調整
- Android標準のキーボードとスペルチェックに対応

### 原稿の管理

- Inboxと階層フォルダによる整理、原稿の移動・複製・検索・並び替え
- 複数選択や、左右スワイプに割り当てる操作の設定
- ゴミ箱からの復元と、保持期間の設定
- 原稿・フォルダ・設定のバックアップと復元、原稿JSONの読み込み・書き出し

### テキスト出力

プレーンテキスト、pixiv、X、カクヨム、小説家になろう、ハーメルン、エブリスタ向けに出力できます。プレビューで確認してからコピー・共有でき、タイトルの追加や字下げ、空行の整理も選べます。投稿先が対応していない書式は装飾を除き、本文を保持します。

### 画像出力

横書き・縦書きの原稿画像をプレビューし、PNGまたはJPEGで保存・共有できます。ルビや傍点、自動・手動改ページに対応し、フォント、文字サイズ、字間、行間、余白、色、解像度を調整できます。カスタムフォントや透かし、出力設定のプリセットも利用できます。

## 保存とバックアップ

原稿は初期状態でアプリ内に保存されます。Androidのフォルダ選択を使い、指定フォルダを保存先にすることもできます。Lantern自体はオフラインで動作します。

Androidのシステムバックアップは無効です。**機種変更やアプリのアンインストールの前に、アプリのバックアップ機能で外部へ保存し、バックアップファイルを確認してください。** アンインストールするとアプリ内のデータは削除されます。

## ビルド

JDK 17、Android SDK Platform 37.1、Build-Tools 36.0.0を用意し、`JAVA_HOME` と `ANDROID_HOME` を設定します。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

Debug APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。Releaseビルドの署名には `LANTERN_KEYSTORE`、`LANTERN_STORE_PASSWORD`、`LANTERN_KEY_ALIAS`、`LANTERN_KEY_PASSWORD` を環境変数として設定します。鍵とパスワードは安全に保管し、リポジトリに含めないでください。

Debug版と正式版の署名が異なる場合は上書きインストールできません。先にバックアップを外部へ保存・確認し、Debug版をアンインストールしてから正式版をインストールし、バックアップを復元してください。

## ライセンスとお問い合わせ

ソースコードは[MIT License](LICENSE)で提供しています。公式ロゴとブランド素材は対象外で、権利を留保します。詳しくは[ブランド素材の権利](BRAND_LICENSE.md)と[第三者ライセンス](THIRD_PARTY_NOTICES.md)をご覧ください。

[プライバシー](PRIVACY.md) · [不具合の報告・要望](https://github.com/sider51/Lantern/issues)
