# ブランド素材の権利 / Brand assets

LanternのソースコードはMIT Licenseで提供されます。以下の公式ロゴおよびそこから派生したブランド素材にはMIT Licenseは適用されず、権利は著作権者に留保されます。

- `brand/Lantern.png`
- `app/src/main/res/drawable-nodpi/lantern_brand.png`
- `app/src/main/res/drawable/lantern_icon_mono.xml`
- 上記素材から生成・表示されるランチャー、Adaptive、Round、モノクロ、スプラッシュおよびアプリ情報画面のアイコン
- READMEに表示される公式ロゴと、その複製・派生素材

権利者の許諾なく、これらの素材を他製品へ転用、改変、再利用、独立して再配布することや、非公式フォーク・改変版で使用すること、公式製品と誤認させる使用は許諾されません。スクリーンショットに表示された公式アイコンも同様です。

MIT Licenseに基づくソースコードの利用、改変、再配布は制限されません。`app/src/main/res/mipmap-anydpi/lantern_launcher.xml` は素材を参照する設定ファイルで、組み合わされた公式アイコンを保護対象とします。フォークでは `drawable-nodpi/lantern_brand.png` を削除または独自画像へ差し替え、`drawable/lantern_icon_mono.xml` を独自素材へ差し替えてください。公式画像を削除した場合、`drawable/lantern_brand.xml` の汎用代替画像でビルドできます。

The MIT License applies to Lantern source code. The official logo and derivatives identified above are excluded and all rights are reserved by the copyright holder. Permission is not granted to reuse, modify, redistribute separately, use in other products or unofficial forks, or imply official endorsement without permission. This does not restrict rights granted by the MIT License to use, modify, and redistribute the source code. Forks may remove or replace the official bitmap and replace the monochrome resource; a generic fallback is provided for builds without the official bitmap.
