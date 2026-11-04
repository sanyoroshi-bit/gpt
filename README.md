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

`GET /api/article` が `{"service":"ten-article","version":"article-v2","status":"route-ready"}` を返せば、新しいAPIの入口に到達しています。これは記事取得・外部接続の成功を保証する検査ではありません。記事取得は同じURLへ `POST {"url":"記事URL"}` を送ります。

起動時の依存ライブラリ読み込み失敗はJSONの503、入力不備は400、記事側の取得失敗は通常422として返します。プラットフォームが返すHTMLの404/500等も画面で区別し、文章・画像取り込みへの切り替えを表示します。実際のnote記事の取得可否は公開環境で別途確認が必要です。
