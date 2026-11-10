# ten Android画面リーダー（試用版0.2）

他アプリを見ながらtenアイコンを押し、OSの画面取得に同意すると、表示中の本文候補を端末内で認識し、同じアプリの上に定点リーダーを表示する。Android 8以降、compile/target SDK 36。現在のアプリ名は「ten 起動ボタン」。

## 使い方

1. APKをインストール／更新し、「画面のボタンを表示」から重ね表示を許可する。
2. 読みたいアプリを開き、tenアイコンを押す。OSの確認で対象アプリまたは画面の取得を許可する。
3. 本文候補を定点表示する。停止・再生・前後移動・読む位置・速度・意味／単語／一文字・文字サイズ・標準／明朝を選べる。
4. 誤った範囲は「範囲・文章・文字サイズを直す」で画像上を囲み直すか、全文へ戻して本文を修正する。原寸画像の再送信や再OCRは行わない。
5. 閉じる／通知の停止で読書を終了する。続きは元の画面を動かしてアイコンから取得し直す。

アイコンは移動・長押し非表示に対応。任意でクイック設定の「ten ボタン」を追加できる。Android 13以降で通知を拒否した場合、通知欄の操作は使えないがアイコンや読書欄の終了操作は使える。

## 取得とデータ

- 取得はOS同意を受けたセッションのみ。現在の実装は1枚取得後にProjectionを終了し、その本文を読書する。取得同意の再利用・自動復活・ブート開始は行わない。
- 画像と本文は端末内。日本語OCRモデルをAPKに同梱するため約46MiB。画像をファイルへ保存せず、OCR終了後に原寸画像を、読書終了時に縮小画像と本文を解放する。
- 認識中に中止した場合は結果の採用を即座に無効化し、認識処理中の画像は処理終了後に解放する。
- ML KitにはSDK利用状況の計測もある。アプリのINTERNET権限はmerged manifestから除去。実機の通信挙動は未検証なので、端末全体が通信しないという保証とはしない。
- 画面取得禁止の画面には対応しない。本文候補は自動判別なので必要に応じて範囲・文章を直す。縦書き・段組みの読み順は未検証。
- OS停止、画面ロック、重ね表示の取り消しで終了。Android 13以前の取得中の回転は再試行を案内する。OS・省電力設定で常時表示は保証しない。

## ビルドと検証

JDK 17または21、Android SDK 36、Build Tools 35.0.0、Gradle 8.13、AGP 8.12.2。Gradle wrapperには配布ZIPのSHA-256を固定している。

```powershell
.\scripts\build-android.ps1 -SdkRoot C:\path\to\android-sdk -JavaHome C:\path\to\jdk
```

Windowsの日本語パスを避けるため、一時領域へソースをコピーし、GradleキャッシュもASCIIパスに置く。元のフォルダを移動・削除しない。JUnit／Robolectric、Lint、assembleDebug、apksignerの全検証後にpublic/downloads/ten-launcher-debug.apkとSHA-256を保存する。署名鍵とレポートはgit管理外の.ten-cache/に保存。同じ鍵を保持すれば更新できる。

Linux/macOSではJDK・SDKとTEN_DEBUG_KEYSTOREを指定し、androidで ./gradlew testDebugUnitTest lintDebug assembleDebug を実行する。

検証の詳細は docs/android-screen-reading-2026-11-11.md を参照。RobolectricのFrameSource/OcrEngineはテスト用の偽物であり、実機の画面取得やOCRモデル本体の精度を証明しない。デバッグ署名の試用版として配布し、正式署名・ストア公開・課金は含まない。