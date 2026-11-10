# 他アプリ上での画面読み取りと定点表示

## 実装範囲（第1段階）

利用者が別アプリを開いてtenアイコンを押すと、OSの画面取得への同意を求める。現在見えている画面を1枚取得し、端末内で日本語OCRを行い、本文候補を自動選択して元アプリ上の定点リーダーへ渡す。

- `CaptureActivity`: 非公開・透過・別task。同意結果が必要なのでnoHistoryを使わない。FGSを開始した後、同意Activityの破棄を通知して取得を始める。
- `ScreenReaderService` / `ProjectionFrameSource`: mediaProjectionのFGS、各同意につき1つのVirtualDisplay。新規ImageReaderへcompositor待ち後のフレームを渡し、自分のアイコンや読書欄を取得しない。正常な1枚取得後はProjectionを解放し、specialUseの読書サービスへ切り替える。
- Android 14以降の単一アプリ共有のサイズ変更は同じVDのresize/setSurfaceで処理。Android 13以前は取得中の回転を中止し、再取得を案内する。
- `BitmapFrames`: rowStrideと有効画素を区別して行ごとにコピー。最終行のpaddingがない合法バッファにも対応。取得サイズは最大約300万画素に抑える。
- Google ML Kit日本語モデル16.0.1を同梱。`JapaneseOcrEngine`が認識した行の座標を`OcrDocument`へ渡し、本文らしい列を選ぶ。選んだ範囲・全文・文章編集を利用者が修正できる。
- `ReaderOverlay`: 固定幅の定点表示、最大12書記素、意味／単語／一文字、200〜6000文字／分、文字サイズ20〜64、標準／明朝。Unicode空白のみの表示を避け、意味モード内の英単語間スペースを保持する。モード間の位置は非空白の書記素数で引き継ぐ。
- 画像はファイルへ保存しない。原寸画像はOCR完了後、縮小参照画像と本文は読書終了時に解放する。OCR中の中止は表示の採用を直ちに無効化し、画像自体は認識完了後に解放する。
- `CaptureTicket`で世代を管理し、中止・再取得・OS終了後の古い結果を表示しない。画面ロック、重ね表示の取り消し、通知の停止、アイコン非表示でも停止する。

## 通信と権限

APKのmerged manifestからINTERNETを除去した。依存SDKが追加するACCESS_NETWORK_STATEと署名保護の内部受信権限は含む。画面取得のFGS権限を追加し、同意トークンは保存・再利用しない。SDK自体に利用状況計測の仕組みがあることと、画像／本文を端末内で処理することを区別して案内する。実機の通信挙動は未検証。

OCRの64bit arm64/x86_64ライブラリはELFのLOAD alignment 16KB、APK内のZIP配置も16KBであることを確認した。32bit armv7/x86のELFは旧来の4KB。全ABIを含むAPKのサイズは約46MiB。

## 検証・監査

監査担当: `/root/audit_implementation`。先行指摘の最終行padding、空白、旧Android回転を修正し回帰テストを追加した。

JUnit／Robolectricでバッファ変換・本文候補・書記素・同意の拒否／受領・世代切替・OCR中止と遅延結果・権限取り消し・タイムアウト・旧Androidの回転・編集・定点欄の320px描画を検証する。標準フローはAPI36、旧版の回転はAPI26で検証。Native描画画像はJVM上のレイアウト検証で、実機画面の画像ではない。

最終ビルドは36件成功（BubbleBounds4、既存Lifecycle8、Native描画2、本文候補3、画面読書19）。OS fontScale=2／64sp／短い単位でも上下が欠けない高さ制約を追加し確認した。Lintは0 errors／固定Gradleの更新通知1 warning。assembleDebug、apksigner v2検証が成功。更新前と署名証明書が同じであることも確認。

第1段階APKは47,644,982 bytes、SHA-256 `392723ff3dd9a737b033bcfb75dd1bcaee278ba448e17bc3a1ad7fe157846abc`。最終APKと配布ページの320px表示・導線・MIME・チェックサム一致をブラウザテスト1件で確認した。実機のダウンロードや実際のOCR成功とは区別する。

監査担当から「phase1の実装・ローカル検証・試用APK配布と今回差分のmain反映を承認します。自動スクロール開発への着手も承認します」との明示的承認を受領した。監査担当は最終ソースとビルドコピー、36件のXML、Lint、署名、権限、APKチェックサム、配布導線・320px画像を独立確認した。承認範囲は実機未確認の試用実装であり、実取得・ML Kitの実精度を証明するものではない。

ユーザーの2026-11-11の条件付き許可に従い、この承認後に自動スクロールへ着手する。次工程も公開前に別途独立監査を受ける。

## 未検証事項

- 接続Android端末は0台。MediaProjectionからの実フレーム取得、日本語OCRモデル本体の精度・速度、重ね表示とIME・OS同意UI、実通知・タイル、機種別の省電力制限は実機未確認。
- パイプラインテストのFrameSource/OcrEngineは偽物であり、ロジックと破棄・競合を確認する。実際のモデルによる認識成功とは区別する。
- 本文自動選択はヒューリスティック。縦書き・段組みの範囲と読み順は未検証。一般の本で正しい読書順を保証するものではない。
- 画面取得禁止の画面は対象外。空画像や認識不能を本文として作らない。

## 公式仕様

- [MediaProjection・セッション同意](https://developer.android.com/media/grow/media-projection)
- [Image.Planeのバッファとpadding](https://developer.android.com/reference/android/media/Image.Plane#getBuffer())
- [同梱日本語OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [ML Kitの利用条件・データ収集](https://developers.google.com/ml-kit/terms)
