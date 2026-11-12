# 縦記事の読書・本文抽出・固有名詞・アイコン維持（0.4）

## 利用者と確認した要件

対象は日経電子版の「記事を開いた本文画面」で、紙面ビューアーではない。同じ記事を上下だけで最後まで読み、横の別記事へは遷移しない。ネット記事が複数ページの場合は同一記事の次ページだけをつなぎ、1ページ完結ならその記事で止める。タイトル・表等を除いて本文を選ぶ。固有名詞の区切りを改善し、設定・読書画面を閉じても浮動アイコンを残す。

## 原因と修正

- 0.3はscroll nodeを面積優先で選び、軸の保証がないFORWARDを実行した。0.4は内側の縦領域を優先・固定し、DOWNまたは既知の縦widgetのみを使う。固定領域の終端・消失時に外側pagerへ乗り換えない。2D WebViewでも明示DOWNだけを選ぶ。
- 0.3のbodyRegionは最大列の矩形を返し、その中のタイトル・表セルもtext(region)で戻っていた。0.4のBodyLayoutは本文の行集合と高確度の除外集合を持つ。ML Kitのblock/line/element座標・文字サイズ、複数行の列位置を利用する。位置だけを根拠に短い本文を捨てない。
- 本文同士の重複だけでなく原OCRの12書記素以上の重なりも確認する。表だけ、または既読本文と新しい表だけの画面を記事末尾にせず、その下の本文を探す。未読が除外集合だけと確認できない場合は自動モードを終了する。表で記事が終わった場合も停止理由を表示する。
- ICU単語境界だけの区切りからKuromoji IPADIC NORMALへ変更。POSに基づく隣接人名・組織名の複合と、最長一致の任意保持語辞書を使う。元surface・位置を保ち、NFC後の書記素内で分断しない。英字の保持語が別の単語内に一致して分割を増やすことを防ぐ。論理一語と12書記素の表示分割を区別する。
- 語の解析と初期化は再利用するworkerへ渡す。画面終了・新しい文章・モード変更で世代を切り替え、古い結果を採用しない。準備中の再生・位置変更・前後移動を無効化し、モード変更後は非空白書記素数で位置を復元する。辞書エラーでも本文を捨てず、準備の失敗を表示できる。
- LauncherのみSTART_STICKY、stopWithTask=false。設定終了・task removalで消さず、OS再生成時のnull intentを扱う。消灯中・ロック中は隠し、SCREEN_ON/USER_PRESENT後に復帰する。明示非表示・許可解除・強制停止を尊重する。取得・OCR・本文は復活させず、boot receiverも追加しない。

## Webの同一記事ページ取り込み

URL取り込みで同一記事の確認済みページを連結する。Nativeの縦操作とは別ルートであり、汎用ブラウザの次ページボタンの自動タップは行わない。

- 安定したarticle slug/ID、番号を除去しないcanonical実URLの一致、見出しの一致、ページ番号の連続性を必須にする。番号を削って別記事を同一視しない。
- 同一origin・同一記事の候補だけを要求し、転送前後もSSRF/DNS/redirect検証を通す。別記事、番号飛び、後戻り、循環、同文、見出し違い、canonical不明は追跡しない。次ページ候補を確認できない場合は「途中まで」を表示する。
- 全ページで合計15秒・5ページ・6MB・20万文字の上限を共有する。途中の取得失敗時は取得済みの確認できた本文を保持する。
- HTML構造がある本文から見出し・表・figure/figcaption・ページ操作UIを除き、図表下の段落を残す。API識別はarticle-v5。

## 独立監査と検証

監査担当は `/root/audit_implementation`。最終承認前にmainへ反映しない。

先行監査で、別記事canonicalを番号除去で一致させる問題、既読本文＋新しい表で空の自動リーダーが残る問題、ページ番号の飛び/後戻りで途中表示にならない問題、画面端の短い本文が消える問題を指摘され、それぞれ修正と回帰テストを追加した。

最終Android 88件は全成功（AutoReading15、AutoScroll18、Bounds4、実辞書5、LauncherLifecycle13、描画2、本文分類7、非同期/語境界4、単発読書20）。準備待ちのモード連続変更でも読書位置を保つ回帰と、中黒を挟むカタカナ人名・短い英字保持語が別単語を分断しない回帰も含む。JVMで実辞書を読み込み、夏目漱石・村上春樹・東京大学・日本経済新聞・ウォーレン・バフェット等を検証した。

Webユニット49件と、ブラウザ回帰18件＋確定APKの配布テスト1件が成功した。ブラウザの実表示で、Readabilityが単一セルの表を段落へ変換して再混入させる問題も検出し、変換前に除外する修正・回帰を追加した。複数ページの全本文が表示順に残り、途中警告が見えることを確認した。

最終ビルドのapp/src全ファイル・build.gradleがworkspaceと一致し、配布APKとビルド成果物も一致した。Lint 0 errors / 1 warning（isAccessibilityTool属性はAPI31未満で無視）、assembleDebug、apksigner v2検証が成功。証明書は従来と同じ。最終APKは61,140,839 bytes、SHA-256 `43ada6056f10ad8a76dee123dc8aabf8962d4791e5112a741a964fde24a9880f`。INTERNET権限なし、64bit OCRのELF LOAD 16KBとZIP 16KB配置も確認した。

検証値は `verification/vertical-article-reading-2026-11-12.json`。辞書bin8点と権利表記assets4点の同梱を記録した。JVM描画は `verification/native-v04-reader-layout-320-2026-11-12.png`、配布画面は `verification/android-v04-download-320-2026-11-12.png`。実機画像ではない。

監査担当は「0.4のローカル実装・試用APK配布・今回のmain反映を明示承認します。阻害指摘は残っていません」と承認した。Native88件・Lint・46ファイルのソース/ビルドコピー一致・配布APK一致・署名・16KB配置・辞書/権利表記・INTERNETなしを独立確認し、Web49件と追加ブラウザ2件も確認した。承認対象は上記SHAの実機未確認・debug署名試用版。日経の実動作・OCR精度・アイコン維持・辞書の実機速度/メモリの成功承認ではない。公開後の確認と、その観測記録だけの追記・反映も承認された。

## 公開確認

実装commit `fe41832b689b6a0da6ce2fda5600142f1c75dfe4` をmainへ反映し、Vercelのsuccessを確認した。2026-11-12 16:25 JST、公開 `/android.html` は200で試用版0.4・縦記事限定・アイコン維持・実機未確認を表示。公開APKは200、MIME application/vnd.android.package-archive、61,140,839 bytesで、ZIP magicと承認済みSHA-256、公開.sha256の一致を実際のダウンロードで確認した。

公開 `/api/article` GETは200・article-v5・initialization-only。依存起動だけの確認であり、実際の特定記事の取得成功ではない。観測値は `verification/live-vertical-article-reading-2026-11-12.json`。公開HTTP配布の確認とAndroid実機での成功を区別する。

## 未検証と限界

Android端末の接続は0台。日経電子版そのものの実node構造・実フレーム・OCR精度・実スクロール、アイコンの維持、実機の辞書初期化速度/メモリ量は未確認。Nativeのパイプラインは偽FrameSource/OcrEngineとJVM node/eventで検証し、Kuromojiは実辞書の文字列で検証する。Webのページ追跡は模擬HTML/HTTP応答で検証し、全ネット記事に対応したとは主張しない。

本文分類は画像の幾何・文字の情報による推定であり、表/見出しの完全な意味判別ではない。未知の固有名詞は保証せず、保持語と全文・範囲の修正を用意する。縦書き・段組みは未検証。OS/メーカーの強制停止を防ぐものではない。ストア審査・正式署名・課金は含まない。

## 公式資料と同梱物

- [Androidの方向付きスクロールaction](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction)
- [Serviceのsticky再生成](https://developer.android.com/reference/android/app/Service#START_STICKY)
- [stopWithTask](https://developer.android.com/guide/topics/manifest/service-element#stop)
- [Kuromoji](https://github.com/atilika/kuromoji)（Apache 2.0）とIPADIC通知・CONTRIBUTORSをAPK assetsへ同梱し、設定画面から読める。依存JARのMETA-INF通知も削除せずmergeする。
