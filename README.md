# ten. — 一点に、読む。

スマホ向けブラウザ速読試用版。文章貼り付け、スクリーンショットの端末内OCR、公開記事URLの取り込みに対応します。日本語の区切りはBudouXで推定し、語尾や数字の途中を避けます。読解力や速度の向上を保証するものではありません。

## ローカル起動

Node.js 24を使用してください。

```sh
npm ci
npm start
```

既定ポート3000。`npm run build` はフォントとOCRモデルを同梱し、ブラウザ用JSを生成します。画像はサーバーへ送信しません。OCRデータは読み取り開始時、フォントは使用時に読み込みます。フォント設定はブラウザ内に保存します。

## 公開

Vercel向けの設定と記事取得APIを同梱しています。VercelでGitHubのこのリポジトリをImportし、Deployします。設定値: Node24 / install `npm ci --include=dev` / build `npm run build` / output `public`。誰でも使う試用版ではDeployment Protectionの公開範囲も確認してください。Vercelの環境へ、管理クラウド専用のHTTP_PROXYやTEN_TRUST_PROXY_DNSをコピーする必要はありません。

この作業環境ではVercelへの通信が未許可のため、ツールからの公開は停止しています。環境設定が反映されれば `vercel deploy --temporary --yes` による一時公開も検証できます。Vercel CLI65.10.0のヘルプで一時公開オプションを確認していますが、実際の公開成功はまだ確認していません。

## QR

実際に公開したURLで `/share.html` を開くとQRを表示できます。CLIでは `npm run qr -- 'https://実際の公開先/'` で `dist/ten-trial-qr.png` を生成します。QRは画像取り込み画面を開きます。ローカルURLや架空のURLを、使える試用版のQRとして案内しないでください。

## チェック

`npm test`、`npm run test:e2e` を実行します。ブラウザテストは `npx playwright install chromium` または `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium` を使用します。

## ライセンス

BudouXはGoogle LLCによるApache-2.0ソフトウェアです。`licenses/BUDOUX-LICENSE.txt`を同梱します。フォントはFontsource経由で配布し、各フォントのOFLは生成先の `public/fonts/*/LICENSE.txt` にコピーします。OCRのライセンスも生成した `public/ocr` に含めます。


## 画像から読む

1. 「画像から読む」でスクリーンショットを選びます。
2. 上下左右のスライダーで本文を囲み、「この範囲を読み取る」を押します。枠外も元画像に保持されます。
3. 結果の不要文や誤字を確認・修正し、「確認した文章で読みはじめる」を押します。
4. 図や写真を参照するときは、リーダーの「図・写真を元画像で確認」を押します。読む位置を保って一時停止し、画像を拡大できます。

同一セッションの連続OCRでは準備済みエンジンを再利用します。未使用60秒で解放し、バックグラウンド時も待機中なら解放します。初回の準備時間や文字認識そのものの待ちは残ります。読み取り結果に準備・認識の所要時間を表示します。数値はその端末での処理時間であり、ファイル選択や範囲指定の操作時間を含みません。

## 記事取得APIの公開と確認

VercelのRoot Directoryはリポジトリのルートにし、`api/` と `lib/` をデプロイ対象に含めます。静的ファイルの出力先だけを `public` に設定します。`/api/article` は `api/article.js` の直接のFunctionで、rewriteは不要です。

`GET /api/article` が `{"service":"ten-article","version":"article-v4","status":"dependencies-ready"}` を返せば、APIへの到達と記事取得ライブラリの初期化を確認できています。これは外部サイトからの記事取得の成功を保証する検査ではありません。記事取得は同じURLへ `POST {"url":"記事URL"}` を送ります。

起動時の依存ライブラリ読み込み失敗はJSONの503、入力不備は400、記事側の取得失敗は通常422として返します。プラットフォームが返すHTMLの404/500等も画面で区別し、文章・画像取り込みへの切り替えを表示します。実際のnote記事の取得可否は公開環境で別途確認が必要です。


## 複数サイトと端末内取り込み（2026-11-06）

note・Qiita・Zenn・はてなブログ・Wikipedia・NHK・ITmediaの本文構造用ルールに加え、一般ブログ向け本文コンテナ、Readability、公開JSON-LDのarticleBodyを順に利用します。段落・見出し・表の区切りを保ち、関連記事や共有ボタンを除きます。会員・有料と明示された記事では、埋め込みデータから非表示の本文を取り出しません。取得できた文章を全文保証せず、短い本文や図表を含む記事には注意を表示します。

「記事のURL」には記事名を含む共有文も貼り付けられます。「URLから取り込めないときは」から保存済みHTML（5MB以下）を選ぶと、同じ抽出処理を端末内で行います。HTMLのコードを実行せず、画像・外部リソースも読み込みません。Androidの保存機能がMHTMLを生成する場合は対象外です。文章共有・貼り付け・画像取り込みを利用してください。

対応ルールの実装と実サイトの現在の取得成功は異なります。検証状況は `docs/site-support-2026-11-06.md` を参照してください。会員ページ、動的読み込み、アクセス拒否などをすべてのサイトで解消するものではありません。

## Androidの共有先として開く

対応するAndroid Chromeでホーム画面にインストールすると、Web Share Target対応環境でURL・選択した文章を共有できます。スクリーンショットの共有先登録と常駐ボタンは今回含みません。実機の共有メニュー表示は未確認です。

共有はService WorkerがPOSTを端末内で受け取り、IndexedDBを介して読書画面へ渡します。文章をURLクエリに入れず、サーバーにも送信しません。共有したURLの記事取得はサーバーを使います。共有データは受け取り時に削除し、次の共有でも置き換えます。受け取り可能な時間は10分です。アプリのHTML・JS・CSSのみをキャッシュし、記事API応答や画像はキャッシュしません。初回OCRモデルは別途必要です。

## サイト検証の再実行

`node scripts/check-sites.js [記事URL ...]` で実通信の結果を `dist/site-check.json` に保存します。管理クラウドの信頼済みプロキシでDNS解決を委任する場合のみ `TEN_TRUST_PROXY_DNS=1` を使用します。この設定は通信先の許可を追加しません。

`node scripts/check-snapshots.js /path/to/mozilla/readability` で、Mozilla Readabilityの過去HTMLテストデータ6件を確認します。期待本文の段落保持と文字数を記録し、現サイトの接続試験と混同しません。

Mozilla ReadabilityはApache-2.0です。`licenses/READABILITY-LICENSE.md` にライセンスを収録しています。
