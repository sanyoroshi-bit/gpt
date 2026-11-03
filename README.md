# ten. — 一点に、読む。

スマホ向けブラウザ速読試用版。文章貼り付け、スクリーンショットの端末内OCR、公開記事URLの取り込みに対応します。日本語の区切りはBudouXで推定し、語尾や数字の途中を避けます。読解力や速度の向上を保証するものではありません。

## ローカル起動

Node.js 24を使用してください。

```sh
npm ci
npm start
```

既定ポート3000。`npm run build` はフォントとOCRモデルを同梱し、ブラウザ用JSを生成します。画像はサーバーへ送信しません。初回アクセスではOCRとフォントのデータを読み込みます。フォント設定はブラウザ内に保存します。

## 公開

Vercel向けの設定と記事取得APIを同梱しています。VercelでGitHubのこのリポジトリをImportし、Deployします。設定値: Node24 / install `npm ci --include=dev` / build `npm run build` / output `public`。誰でも使う試用版ではDeployment Protectionの公開範囲も確認してください。Vercelの環境へ、管理クラウド専用のHTTP_PROXYやTEN_TRUST_PROXY_DNSをコピーする必要はありません。

この作業環境ではVercelへの通信が未許可のため、ツールからの公開は停止しています。環境設定が反映されれば `vercel deploy --temporary --yes` による一時公開も検証できます。Vercel CLI65.10.0のヘルプで一時公開オプションを確認していますが、実際の公開成功はまだ確認していません。

## QR

実際に公開したURLで `/share.html` を開くとQRを表示できます。CLIでは `npm run qr -- 'https://実際の公開先/'` で `dist/ten-trial-qr.png` を生成します。QRは画像取り込み画面を開きます。ローカルURLや架空のURLを、使える試用版のQRとして案内しないでください。

## チェック

`npm test`、`npm run test:e2e` を実行します。ブラウザテストは `npx playwright install chromium` または `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium` を使用します。

## ライセンス

BudouXはGoogle LLCによるApache-2.0ソフトウェアです。`licenses/BUDOUX-LICENSE.txt`を同梱します。フォントはFontsource経由で配布し、各フォントのOFLは生成先の `public/fonts/*/LICENSE.txt` にコピーします。OCRのライセンスも生成した `public/ocr` に含めます。
