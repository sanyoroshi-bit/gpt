import {test,expect} from '@playwright/test';import jsQR from 'jsqr';
test('意味・文字モードの位置引き継ぎと設定保存・スマホ表示',async({page})=>{await page.setViewportSize({width:390,height:844});await page.goto('/');await page.locator('#text').fill('新しい景色に出会うこと。');await page.locator('#load').click();await expect(page.locator('#word')).toHaveText('新しい景色に');await page.locator('#next').click();await page.getByRole('radio',{name:'一文字ずつ'}).check();await expect(page.locator('#word')).toHaveText('出');await page.locator('#font-family').selectOption('rounded');await page.locator('#font-size').fill('48');await page.locator('[data-speed="6000"]').click();await page.locator('#play').click();await expect(page.locator('#state')).toHaveText('読み終わり');await page.reload();await expect(page.locator('#font-family')).toHaveValue('rounded');await expect(page.locator('#font-size')).toHaveValue('48');await expect(page.locator('#speed')).toHaveValue('6000');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.screenshot({path:'/tmp/ten-restored-mobile.png',fullPage:true});});
test('スマホで範囲を指定し、日本語OCRを再利用して元画像を参照できる',async({page},testInfo)=>{
 test.setTimeout(120000);await page.setViewportSize({width:390,height:844});
 await page.addInitScript(()=>{const NativeWorker=window.Worker;window.ocrWorkers=0;window.Worker=class extends NativeWorker{constructor(url,options){super(url,options);if(String(url).includes('/ocr/worker.min.js'))window.ocrWorkers++;}};});
 await page.goto('/?start=image');await expect(page.locator('#image-panel')).toBeVisible();
 const png=await page.evaluate(async()=>{
  await document.fonts.load('48px "Noto Sans JP"','今日は新しい本を読みます。広告おすすめ');
  const c=document.createElement('canvas');c.width=1280;c.height=900;const x=c.getContext('2d');x.fillStyle='white';x.fillRect(0,0,c.width,c.height);x.fillStyle='black';x.font='48px "Noto Sans JP"';
  [['広告おすすめ',130],['今日は新しい本を読みます。',400],['読書は新しい景色との出会いです。',540],['広告お問い合わせ',830]].forEach(([s,y])=>x.fillText(s,70,y));
  return c.toDataURL('image/png').split(',')[1];
 });
 const external=[];page.on('request',r=>{if(!r.url().startsWith('http://127.0.0.1:3000/')&&!r.url().startsWith('blob:'))external.push(r.url());});
 await page.locator('#image-file').setInputFiles({name:'screenshot.png',mimeType:'image/png',buffer:Buffer.from(png,'base64')});
 await expect(page.locator('#image-editor')).toBeVisible();expect(await page.evaluate(()=>window.ocrWorkers)).toBe(0);
 await page.locator('#start-ocr').click();await expect(page.locator('#ocr-status')).toContainText('読み取り完了',{timeout:100000});
 expect((await page.locator('#ocr-text').inputValue()).replace(/\s/g,'')).toContain('広告');await expect(page.locator('#state')).toHaveText('準備できました');
 const fullTiming=await page.locator('#ocr-timing').textContent();
 await page.locator('#crop-top').fill('30');await page.locator('#crop-bottom').fill('70');
 await page.locator('#start-ocr').click();await expect(page.locator('#ocr-timing')).toContainText('準備を再利用',{timeout:60000});
 const cropped=await page.locator('#ocr-text').inputValue();expect(cropped.replace(/\s/g,'')).toContain('今日は新しい本を読みます');expect(cropped).not.toContain('広告');expect(await page.evaluate(()=>window.ocrWorkers)).toBe(1);
 await testInfo.attach('ocr-timings.json',{body:JSON.stringify({full:fullTiming,crop:await page.locator('#ocr-timing').textContent(),device:'desktop Chromium, mobile viewport, synthetic screenshot'}),contentType:'application/json'});
 await page.locator('#ocr-text').fill('一時的な修正');await page.locator('#restore-ocr').click();await expect(page.locator('#ocr-text')).toHaveValue(cropped);
 await page.locator('#speed').fill('200');await page.locator('#load').click();await expect(page.locator('#state')).toHaveText('読書中');
 await page.locator('#show-source').click();await expect(page.locator('#source-dialog')).toBeVisible();await expect(page.locator('#state')).toHaveText('準備できました');
 const position=await page.locator('#position').textContent();await page.locator('#source-zoom').fill('200');await page.locator('#close-source').click();await expect(page.locator('#position')).toHaveText(position);
 await page.locator('#show-source').click();await page.keyboard.press('Escape');await expect(page.locator('#source-dialog')).toBeHidden();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect(external).toEqual([]);
 await page.screenshot({path:'/tmp/ten-ocr-improved-mobile.png',fullPage:true});
 await page.locator('#text-tab').click();await page.locator('#text').fill('別の文章です。');await page.locator('#load').click();await expect(page.locator('#show-source')).toBeHidden();
});
test('公開URLから作ったQRを読み戻せる',async({page})=>{await page.goto('/share.html');await expect(page.locator('#qr-result')).toBeHidden();await page.locator('#share-url').fill('https://example.com/ten');await page.locator('#share-form button').click();await expect(page.locator('#qr-result')).toBeVisible();const p=await page.locator('#qr-image').evaluate(async img=>{await img.decode();const c=document.createElement('canvas');c.width=img.naturalWidth;c.height=img.naturalHeight;const x=c.getContext('2d');x.drawImage(img,0,0);return {width:c.width,height:c.height,data:Array.from(x.getImageData(0,0,c.width,c.height).data)};});expect(jsQR(new Uint8ClampedArray(p.data),p.width,p.height).data).toBe('https://example.com/ten?start=image');await page.locator('#share-url').fill('https://127.0.0.1');await page.locator('#share-form button').click();await expect(page.locator('#qr-result')).toBeHidden();});
test('準備中に中止して再試行しても処理が重複しない',async({page})=>{
 test.setTimeout(120000);
 await page.addInitScript(()=>{const W=window.Worker;window.ocrWorkers=0;window.Worker=class extends W{constructor(url,options){super(url,options);if(String(url).includes('/ocr/worker.min.js'))window.ocrWorkers++;}};});
 let release;const gate=new Promise(r=>{release=r;});let requested;const requestedPromise=new Promise(r=>{requested=r;});
 await page.route('**/ocr/lang/jpn.traineddata.gz',async route=>{requested();await gate;await route.continue();});
 try{
  await page.goto('/?start=image');
  const png=await page.evaluate(()=>{const c=document.createElement('canvas');c.width=600;c.height=200;const x=c.getContext('2d');x.fillStyle='white';x.fillRect(0,0,600,200);x.fillStyle='black';x.font='40px sans-serif';x.fillText('READ THIS TEXT',30,100);return c.toDataURL().split(',')[1];});
  await page.locator('#image-file').setInputFiles({name:'retry.png',mimeType:'image/png',buffer:Buffer.from(png,'base64')});await expect(page.locator('#start-ocr')).toBeEnabled();
  await page.locator('#start-ocr').click();await requestedPromise;await page.locator('#cancel-ocr').click();
  await expect(page.locator('#ocr-status')).toHaveText('読み取りを中止しました。');await expect(page.locator('#ocr-text')).toHaveValue('');
  await page.locator('#start-ocr').click();release();await expect(page.locator('#ocr-status')).toContainText('読み取り完了',{timeout:90000});
  expect(await page.evaluate(()=>window.ocrWorkers)).toBe(1);await expect(page.locator('#ocr-text')).toHaveValue(/READ THIS TEXT/);
 }finally{release();}
});
test('記事APIの公開エラーを区別し、本文貼り付けへ進める',async({page})=>{
 await page.route('**/api/article',route=>route.fulfill({status:404,contentType:'text/html',body:'<h1>Not Found</h1>'}));
 await page.goto('/');await page.locator('#url-tab').click();
 await page.locator('#url').fill('https://note.com/deeptech/n/ndd8646916e9c');await page.locator('#load').click();
 await expect(page.locator('#message')).toContainText('HTTP 404');await expect(page.locator('#article-fallback')).toBeVisible();
 await page.locator('#fallback-text').click();await expect(page.locator('#text-panel')).toBeVisible();await expect(page.locator('#article-fallback')).toBeHidden();
 await page.locator('#text').fill('本文を貼り付けて読めます。');await page.locator('#load').click();await expect(page.locator('#original-text')).toHaveText('本文を貼り付けて読めます。');
 await page.unroute('**/api/article');await page.route('**/api/article',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({title:'取り込んだ記事',text:'記事を読み込みました。本文を速読できます。'})}));
 await page.locator('#url-tab').click();await page.locator('#load').click();await expect(page.locator('#article-title')).toHaveText('取り込んだ記事');await expect(page.locator('#article-fallback')).toBeHidden();
});
test('保存したHTMLを端末内で抽出し、埋め込みコードや画像を実行・送信しない',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.goto('/');await page.locator('#url-tab').click();
 await page.locator('.import-help summary').click();
 const requests=[];page.on('request',r=>{if(r.url().includes('outside.example'))requests.push(r.url());});
 const paragraph='保存したページの本文を、文字を読み取る処理なしで取り込みます。図や写真は元のページで確認し、文章を順番に読み進められます。';
 const html=`<!doctype html><html><head><title>保存した読書記事</title><link rel="canonical" href="https://note.com/test/n/n123456789abc"><link rel="stylesheet" href="https://outside.example/style.css"><script>window.htmlExecuted=true;fetch('https://outside.example/secret')</script></head><body><div class="note-common-styles__textnote-body"><p>${paragraph}</p><p>段落も分けて取り込めます。</p><img src="https://outside.example/image.png" onerror="window.htmlExecuted=true"><iframe src="https://outside.example/frame"></iframe></div><nav>不要なメニュー</nav></body></html>`;
 await page.locator('#html-file').setInputFiles({name:'article.html',mimeType:'text/html',buffer:Buffer.from(html)});
 await expect(page.locator('#message')).toContainText('保存したページから本文を取り込みました');
 await expect(page.locator('#original-text')).toContainText(paragraph);await expect(page.locator('#original-text')).not.toContainText('不要なメニュー');
 await expect(page.locator('#article-source')).toHaveAttribute('href','https://note.com/test/n/n123456789abc');
 expect(await page.evaluate(()=>window.htmlExecuted)).toBeUndefined();expect(requests).toEqual([]);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
async function waitForShareWorker(page){
 await page.goto('/');await page.evaluate(async()=>{await navigator.serviceWorker.ready;if(!navigator.serviceWorker.controller)await new Promise(resolve=>navigator.serviceWorker.addEventListener('controllerchange',resolve,{once:true}));});
}
async function sendShare(page,fields){
 await page.evaluate(fields=>{const f=document.createElement('form');f.method='POST';f.action='/share-target';for(const [name,value]of Object.entries(fields)){const input=document.createElement('input');input.name=name;input.value=value;f.append(input);}document.body.append(f);f.submit();},fields);
}
test('Android共有の受取経路: 文章をサーバーへ送らず、端末内で一度だけ受け取る',async({page,context})=>{
 await waitForShareWorker(page);
 let postedToServer=false;await context.route('**/share-target',route=>{postedToServer=true;return route.fulfill({status:500,body:'Share POST reached server'});});
 const text='共有した読書の文章です。ほかのアプリから、コピーし直さずに読むことができます。';
 await sendShare(page,{text,title:'共有記事'});
 await expect(page.locator('#article-title')).toHaveText('共有した文章');await expect(page.locator('#original-text')).toHaveText(text);
 expect(postedToServer).toBe(false);expect(page.url()).not.toContain('share=');expect(page.url()).not.toContain(encodeURIComponent(text));
 const count=await page.evaluate(()=>new Promise((resolve,reject)=>{const req=indexedDB.open('ten-shared-input',1);req.onsuccess=()=>{const db=req.result;const tx=db.transaction('pending');const q=tx.objectStore('pending').count();q.onsuccess=()=>resolve(q.result);tx.oncomplete=()=>db.close();};req.onerror=()=>reject(req.error);}));expect(count).toBe(0);
});
test('共有URLから本文取得へ進み、API応答・記事本文はキャッシュしない',async({page,context})=>{
 await waitForShareWorker(page);const seen=[];
 await context.route('**/api/article',route=>{seen.push(route.request().postDataJSON());return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({title:'共有リンクの記事',text:'共有リンクから取得した文章です。元記事を確認しながら読めます。',source:'https://note.com/test/n/n123456789abc',warnings:['記事の一部だけの可能性があります。']})});});
 await sendShare(page,{text:'気になる記事 https://note.com/test/n/n123456789abc #note'});
 await expect(page.locator('#article-title')).toHaveText('共有リンクの記事');expect(seen).toEqual([{url:'https://note.com/test/n/n123456789abc'}]);
 await expect(page.locator('#article-notice')).toContainText('一部だけ');await expect(page.locator('#article-source')).toBeVisible();
 const keys=await page.evaluate(async()=>{const result=[];for(const name of await caches.keys())for(const req of await(await caches.open(name)).keys())result.push(req.url);return result;});
 expect(keys.some(url=>url.includes('/api/')||url.includes('share='))).toBe(false);
});
