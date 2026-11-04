import {test,expect} from '@playwright/test';import jsQR from 'jsqr';
test('意味・文字モードの位置引き継ぎと設定保存・スマホ表示',async({page})=>{await page.setViewportSize({width:390,height:844});await page.goto('/');await page.locator('#text').fill('新しい景色に出会うこと。');await page.locator('#load').click();await expect(page.locator('#word')).toHaveText('新しい景色に');await page.locator('#next').click();await page.getByRole('radio',{name:'一文字ずつ'}).check();await expect(page.locator('#word')).toHaveText('出');await page.locator('#font-family').selectOption('rounded');await page.locator('#font-size').fill('48');await page.locator('[data-speed="6000"]').click();await page.locator('#play').click();await expect(page.locator('#state')).toHaveText('COMPLETE');await page.reload();await expect(page.locator('#font-family')).toHaveValue('rounded');await expect(page.locator('#font-size')).toHaveValue('48');await expect(page.locator('#speed')).toHaveValue('6000');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.screenshot({path:'/tmp/ten-restored-mobile.png',fullPage:true});});
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
 expect((await page.locator('#ocr-text').inputValue()).replace(/\s/g,'')).toContain('広告');await expect(page.locator('#state')).toHaveText('READY');
 const fullTiming=await page.locator('#ocr-timing').textContent();
 await page.locator('#crop-top').fill('30');await page.locator('#crop-bottom').fill('70');
 await page.locator('#start-ocr').click();await expect(page.locator('#ocr-timing')).toContainText('準備を再利用',{timeout:60000});
 const cropped=await page.locator('#ocr-text').inputValue();expect(cropped.replace(/\s/g,'')).toContain('今日は新しい本を読みます');expect(cropped).not.toContain('広告');expect(await page.evaluate(()=>window.ocrWorkers)).toBe(1);
 await testInfo.attach('ocr-timings.json',{body:JSON.stringify({full:fullTiming,crop:await page.locator('#ocr-timing').textContent(),device:'desktop Chromium, mobile viewport, synthetic screenshot'}),contentType:'application/json'});
 await page.locator('#ocr-text').fill('一時的な修正');await page.locator('#restore-ocr').click();await expect(page.locator('#ocr-text')).toHaveValue(cropped);
 await page.locator('#speed').fill('200');await page.locator('#load').click();await expect(page.locator('#state')).toHaveText('READING');
 await page.locator('#show-source').click();await expect(page.locator('#source-dialog')).toBeVisible();await expect(page.locator('#state')).toHaveText('READY');
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
