# ten 起動ボタン（Android試用版）

他アプリ上の小さなtenボタンから、ブラウザの`https://gpt-delta.vercel.app/`を開くネイティブの補助アプリ。Android 8以降、target/compile SDK 36。外部ライブラリなしのJava実装。

## 使い方

1. 試用APKをインストールし「ten 起動ボタン」を開く。
2. 「画面のボタンを表示」を押し、端末の重ね表示設定でtenを許可して戻る。
3. ボタンをタップしてtenを開く。ドラッグで移動、長押しで非表示。アプリ画面や表示中の通知からも非表示にできる。
4. 任意で「クイック設定にボタンを追加」。タイルは表示・非表示の切り替えに使う。追加できない場合は端末のクイック設定の編集から追加する。

Android 13以降で通知を拒否した場合もボタンは利用できるが、通知欄からの起動・停止は利用できない。アプリ画面、長押し、またはOSの実行中アプリ管理から停止できる。

## 範囲

- 初回のみ重ね表示の許可が必要。サービスは利用者の操作から開始し、再起動・OS停止後に自動復活しない。
- フォアグラウンドサービスに`specialUse`と用途を宣言。表示中は通知を維持し、停止時に解除する。
- タップとドラッグを区別し、長押し・キャンセル後にブラウザを誤起動しない。座標は端末内保存し、向き変更時に画面内へ補正。画面OFF時は隠す。
- 内部操作Activityとサービスは外部公開しない。公開ランチャーは設定画面を開く役割に限定する。
- アプリ自体にインターネット・画面取得・アクセシビリティ権限はない。画面やクリップボードを読まず、記事等の取り込みは開いたtenの既存操作で行う。
- OSや機種によるサービス停止、重ね表示が禁止される画面があるため、常時表示を保証しない。

## ビルド

JDK 17または21、Android SDKの`platforms;android-36`と`build-tools;35.0.0`、Gradle 8.13を利用。wrapperは配布ZIPのSHA-256を固定している。

Windowsではリポジトリのルートから:

```powershell
.\scripts\build-android.ps1 -SdkRoot C:\path\to\android-sdk -JavaHome C:\path\to\jdk
```

非ASCIIの作業パスに対応するため、一時領域にソースをコピーし、Gradleキャッシュも一時領域のASCIIパスに置いてユニットテスト・Lint・APKビルドを行う。元の作業ファイルを移動・削除しない。出力APKは`public/downloads/ten-launcher-debug.apk`、レポートとデバッグ署名鍵はgit管理外の`.ten-cache/`に保存する。同じ鍵を保持すると同じ署名で更新できる。JUnitとRobolectricはテスト時のみ利用する。

Linux/macOSはJDK/SDKと`TEN_DEBUG_KEYSTORE`に既存のデバッグ鍵を指定し、`cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug`を実行する。

このAPKはデバッグ署名の試用版。Playストア配布用の正式署名・課金・ストア審査は含まない。検証結果と独立監査の承認範囲は`docs/audit-android-launcher-2026-11-10.md`に記録する。

実機確認項目: 初回許可と拒否、2回目の起動、ドラッグ/長押し/回転、通知拒否と停止、クイック設定の追加/切り替え、画面ロック、権限取り消し、OS停止後の再表示。

## 参照した公式仕様

- [重ね表示の権限](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context))
- [Foreground serviceのspecialUse](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
- [Foreground service開始の制限](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [クイック設定タイル](https://developer.android.com/develop/ui/views/quicksettings-tiles)
- [タスクと既存Activityへの起動](https://developer.android.com/guide/components/activities/tasks-and-back-stack)
