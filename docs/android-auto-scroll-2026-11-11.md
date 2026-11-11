# 自動スクロールによる画面間の読書（第2段階）

## 着手条件

利用者の「監査役の承認を得られた場合、自動スクロールまで開発を進めても良い」との許可に従い、第1段階の独立監査承認後に着手した。監査担当は `/root/audit_implementation`。第1段階の承認記録は `android-screen-reading-2026-11-11.md` にある。

## 実装

- 0.3.0 / versionCode 3。単発取得は従来どおり既定。自動スクロールは初期OFFで、目的の説明に同意した後、利用者自身がAndroidのユーザー補助設定で有効にする。OS設定をアプリから書き換えない。
- `AutoScrollService` はwindow・package・領域・scroll action対応だけを調べる。canRetrieveWindowContentを必要とするが、nodeのtextは参照しない。gesture・キー・タップ・global actionの能力を要求／実行しない。`isAccessibilityTool=false` とし、一般読書補助として説明する。
- アイコンを押した時点の前面application windowを固定し、実windowとgetWindowsの一覧が一致しない場合は拒否する。画面全体のサイズ、対象window、safe boundsを記録。上位の未知windowが本文領域と重なる場合も拒否する。自分の読書欄・アイコンだけは、実際にaddViewしたルートのaccessibility window IDを弱参照登録して区別する。同じpackageの通常Activityは例外にしない。
- 自動時はAPI34以降で全画面共有設定を要求。OS/OEMの選択で範囲が違う可能性があるため、content resize/visibilityと表示サイズを検証する。対象領域をcropしてからOCRへ渡す。隣の分割画面やシステムバーを認識へ混ぜない。未知／不一致の取得範囲は採用しない。元画像は画面全体を一時的に含むが、OCR前に対象だけに切り出す。
- 読了時に読書欄を隠し、対象を再検証して1回のscroll actionを行う。可視・scrollable・対象window内の大きな領域を最大1000nodeまで探す。API35以降で対応を明示するnodeにだけ0.7画面の移動量を渡す。非対応では既定量となる。
- actionのtrueだけでは移動成功としない。所有ticket、window、package、発生時刻、source領域が一致するscroll eventを待ち、250ms安定後に確認する。1400ms以内に確認できない場合は停止。実際の移動量は対象アプリ依存である。
- 次画面は同じMediaProjection/VirtualDisplayのSurfaceを付け替え、新規ImageReaderから取得する。画面ごとに共有tokenを使って別VDを作らない。読書中はSurfaceを切り離してフレームの滞留を避ける。
- 前画面の末尾と次画面の先頭をNFC・空白除外した書記素で比較する。最大512書記素、12書記素以上の一致がある場合だけ重複を除いて続きを再生する。一致不明なら全文を残し、共有・操作・自動再生を停止する。文字列一致はOCR誤りや読み順の正しさを証明しない。
- 同じ本文の再出現、移動不能、50画面の上限で停止。アプリ切り替え、検知した元アプリの手動操作、未知の上位windowの重なり、回転／サイズ変更、画面ロック、権限解除、OS共有停止でも中止する。読書欄の停止と文章・範囲修正は自動だけを終了できる。
- 中止はticket／frame世代／live状態を直ちに無効化する。古いframe・OCR結果が表示や操作を再開しない。進行中OCRが参照する画像は完了コールバックで解放する。

## 監査で修正した点

先行監査で、window不明時にroot boundsへfallbackする処理、上位windowの未検査、自己packageを一律許容する処理を指摘された。共通resolver・実windowと一覧の照合・z-orderの重なり検査・実overlay ID登録・captureSafeを追加した。読書中の自分のcontrolsは許容する一方、取得／操作時はそれらがhiddenであることも必須とした。監査担当は再監査で先行P1/P2の解消を確認した。

既定のscroll actionで重なりが残らないアプリがあるとの指摘により、対応nodeだけ部分スクロールを指定する。非対応や重なり不明の場合に、安全な確認停止を緩めない。

## 検証・最終承認

最終文言を含むビルドでJUnit／Robolectric全62件が成功した。内訳はBubbleBounds4、既存Lifecycle8、Native描画2、本文候補3、単発画面読書19、自動読書12、Accessibility service14。FrameSource/OcrEngineとScrollPortを分離したパイプライン検証に加え、実際のAutoScrollServiceをJVM上のnode/window/eventで検証した。

Accessibilityのテストでは、標準RobolectricがgetWindows/getWindowに同じfixtureを返すため、productionのwindow.recycleでfixtureが消える問題があった。限定的なFreshWindows/FreshNodeWindows shadowにより呼び出し所有のコピーを返す。対象解決、操作、イベント照合はproductionのまま。監査担当もこの所有権の差を補う方法を確認した。元window不明、window一覧なし、未知上位window、通常の自己Activity、既知readerからfocused applicationへの解決、部分scroll引数、実viewの登録解除を含む14件が成功した。

Lintは0 errors／1 warning。警告はisAccessibilityTool属性がAPI31未満で無視される点で、属性のない旧OSで特別な支援機能扱いを要求するものではない。assembleDebug、apksigner v2の検証が成功した。前の版と同じ証明書を使用している。Androidの320px読書欄の描画は `verification/native-auto-reader-layout-320-2026-11-11.png` に記録する。これはJVM描画で実機画像ではない。

最終APKは47,740,870 bytes、SHA-256 `07c42b335e7be07fe5e839a7547b6f17d90000ee2f4e0188f2a27de998c6d5e8`。検証値は `verification/android-auto-scroll-phase2-2026-11-11.json`。Web回帰18件は全成功し、最終APKの配布・320px表示・MIME・チェックサム一致も追加1件で確認した。`verification/android-auto-scroll-download-320-2026-11-11.png` に記録する。

監査担当から「phase2の実装・ローカル検証・試用APK配布、および今回差分のmain反映を承認します」との明示的承認を受領した。監査担当は全ソースとビルドコピー、62件のXML、Lint、署名・16KB配置、権限、APK/SHA一致、Web記録と320px画像を独立確認した。承認対象は上記SHAの実機未確認・デバッグ署名試用版。実機での取得・OCR精度・連続スクロール、特定アプリの対応成功、Play公開は含まない。公開後のURL・APK・SHA・MIME確認と、その観測記録だけの追記・main反映も承認された。

## 公開確認

ソースcommit `fdcdaee52135d85fd52e42ce271ca459042e8876` をmainへ反映し、Vercelのsuccessを確認した。2026-11-11 22:28 JST、公開URL `https://gpt-delta.vercel.app/android.html` は200で試用版0.3と自動スクロール手順・実機未確認を表示。公開APKは200、MIMEはapplication/vnd.android.package-archive、47,740,870 bytes、ZIP magic有効。実際に取得したAPKのSHA-256と公開.sha256はいずれも承認対象の値と一致した。

観測記録は `verification/live-android-auto-scroll-2026-11-11.json`。これは公開HTTP配布の確認であり、Android実機でのインストール・認識・自動操作の確認ではない。

## 実機未確認の範囲

Android端末の接続は0台。JVMのRobolectricで状態・イベント・破棄・画面範囲を検証するが、FrameSource/OcrEngineは偽物である。実際のMediaProjection、ML Kit認識、OS同意・制限付き設定・通知、機種ごとのwindow一覧とscroll action、連続読書の対応アプリ、認識速度とバッテリーは未確認。Kindleや日経等の対応成功は主張しない。取得禁止画面は対象外。縦書き・段組みの読み順は未検証。

debug署名の試用APK配布を対象とする。PlayストアのAccessibilityポリシー審査・公開承認、正式署名、課金は含まない。

## 公式仕様

- [部分スクロール引数と対応判定](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT)
- [AccessibilityWindowInfoのwindow・layer・display](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo)
- [MediaProjectionの同意とVirtualDisplay](https://developer.android.com/media/grow/media-projection)
- [Androidの制限付き設定](https://support.google.com/android/answer/12623953?hl=ja)
