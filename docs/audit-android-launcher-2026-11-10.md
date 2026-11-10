# Android起動ボタンの実装と監査（2026-11-10）

## 対象

他アプリの上に小さなtenボタンを表示し、タップで既存のブラウザ版tenを開くAndroidネイティブの補助アプリ。画面取得・本文の自動読み取り・自動スクロールは別項目。

- Android 8以降を対象、compile/target SDK 36。Java、実行時の外部ライブラリなし。
- 利用者が表示を開始・停止する。初回の重ね表示許可、Android 13以降の任意の通知許可を案内。
- ドラッグ移動、座標保存と向き変更時の画面内補正、長押し非表示、画面OFF時の非表示。
- Foreground ServiceのspecialUseを用途とともに宣言し、通知から開く／停止する。クイック設定タイルから表示を切り替える。
- 非sticky、ブート受信なし。OS停止・再起動後の自動復活は行わない。
- 公開EntryActivityは設定画面を開く。内部操作ActivityとOverlayServiceはexported=false。ブラウザへのURLは固定HTTPS。アプリ自体にインターネット・画面取得・アクセシビリティ権限はない。
- 表示中の重ね表示許可変更をAppOpsで監視し、取り消し時にサービスを停止。停止時にウィンドウ、通知、監視、コールバックを解除する。

## 監査指摘による修正

監査担当: `/root/audit_implementation`（実装担当と別）。

- 既存Activityへ届く2回目の起動操作をonNewIntentから共通処理し、CLEAR_TOP/SINGLE_TOPとアクション消費で再起動・回転に対応。
- サービス停止完了を端末内のbroadcastで画面へ伝え、表示状態を同期。
- タイルにACTIVE_TILEを宣言。画面OFF時はロック状態の判定を待たず非表示。
- Android 11以降のOverlay用WindowContextは、DisplayManagerからdefault displayを得てcreateDisplayContext経由で作成。
- 表示中の権限取り消しの監視・停止を追加し、設定画面復帰時にも許可なしなら停止する。
- 配布ページで試用版・Android実機未確認・通知許可の条件・インストール手順を案内。

## ビルドと検証

Windows ARM64上でMicrosoft OpenJDK 21.0.13、Gradle 8.13、AGP 8.12.2、Android SDK 36、Build Tools 35.0.0を使用。公式配布ファイルのSHA-256を確認。wrapperもGradle配布ZIPのSHA-256を固定。

Windowsの非ASCIIパスによるAGPとGradle workerの問題を避けるため、ソースとGradleキャッシュをASCIIの一時パスに置く。元の作業フォルダは移動・削除しない。署名鍵はgit管理外の`.ten-cache/keys/`に保存する。

検証コマンド: `scripts/build-android.ps1`。JUnitの座標・移動閾値4件と、Robolectric/API36で既存Activityからの再起動、アクション消費、外部入口の入力、タイル、許可の拒否・復帰、表示中の許可取り消し、停止状態更新を検証する。Lint、APKビルド、apksignerの署名検証が成功した場合だけ`public/downloads/ten-launcher-debug.apk`へコピーする。

最終ビルドでJUnit4件とRobolectric/API36の8件、合計12件が全件成功。Lintは0 errors／1 warning。残る警告は、新しいGradle版があるという通知で、AGP 8.12.2との互換性を確認した8.13の固定を維持した。旧OS用APIへのLint誤検出は、正しいSDK分岐の下にある小helperだけを理由付きで抑制し、Lint全体は有効のままにした。

assembleDebug成功。apksigner verify成功（APK Signature Scheme v2）。監査担当も配布対象APKに対して署名検証を独立実行し成功した。aaptでpackage `jp.tenreader.launcher`、version 0.1.0、minSdk26／targetSdk36、権限4種類が設計通りであることを確認。

APKは24,923 bytes、SHA-256 `df022a135168dd9719e0b6a65389374a6b2ec090b07f37710341cd4d092f3686`。署名証明書SHA-256は`d9a8b754451ef083cd6dd2584d6cce95007cd7db2bc8ba46ad9dd23a17020e98`。APKと`.sha256`を同じ公開フォルダに保存した。要約結果は`docs/verification/android-launcher-2026-11-10.json`。

配布ページの320pxレイアウト、tenからの導線、APKのContent-TypeとZIPヘッダー、HTTP取得したバイト列とチェックサムの一致を新しいブラウザテストで確認済み。既存の幅・OCR・共有等を含む全ブラウザ18件が成功（Node.js 24.22.2 ARM64、Chrome 157）。配布ページの画像も目視確認し、`docs/verification/android-download-320-2026-11-10.png`へ保存した。

## 最終監査承認

監査担当から「Android起動ボタンの試用実装、検証済みAPKの配布、および今回差分のmain反映を承認します」との明示的承認を受領した。阻害指摘は解消済み。監査担当は最終ソースとビルドコピーの一致、テストXML、Lint、配布APKの署名・権限・SDK・SHA-256を独立確認し、全ブラウザ18件成功の報告と320px配布ページ画像・記録を確認した。

承認対象は上記SHA-256のデバッグ署名試用APKとローカル実装・検証・配布導線。Android実機のインストール・他アプリ上の表示／操作・通知／タイル・省電力制限、公開後のダウンロード成功は承認対象外。この承認後にcommit／pushを行う。

## 検証範囲の限界

`adb devices`で接続端末は0台。Android実機・Androidエミュレーター上のインストール、権限画面、他アプリ上での表示、実際のタップ／ドラッグ、端末固有の省電力制限、タイル登録は未検証。RobolectricはJVM上のAndroid API検証であり、実機成功の代わりとはしない。

配布するAPKはデバッグ署名の試用版。正式署名・Playストア公開・課金は今回の範囲外。
