import {saveSharedInput} from '../shared/share-store.js';
const CACHE='ten-shell-multisite-v1';
const SHELL=['/','/app.bundle.js','/style.css','/fonts.css'];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(SHELL)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key.startsWith('ten-shell-')&&key!==CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
 const request=event.request,url=new URL(request.url);
 if(url.origin!==self.location.origin)return;
 if(url.pathname==='/share-target'&&request.method==='POST'){
  event.respondWith((async()=>{
   try{
    const form=await request.formData();
    const field=name=>typeof form.get(name)==='string'?form.get(name):'';
    const input={title:field('title'),text:field('text'),url:field('url')};
    if(input.text.length>200000||input.url.length>4096)return new Response('共有する文章が長すぎます。必要な部分を選択して共有してください。',{status:413,headers:{'Content-Type':'text/plain; charset=utf-8'}});
    const id=await saveSharedInput(input);
    return Response.redirect(new URL('/?share='+id,self.location.origin),303);
   }catch{return new Response('共有内容を端末に保存できませんでした。tenを開いて貼り付けてください。',{status:400,headers:{'Content-Type':'text/plain; charset=utf-8'}});}
  })());return;
 }
 // Only the app shell is cached. Article API responses, screenshots and OCR files are excluded.
 if(request.method==='GET'&&SHELL.includes(url.pathname)){
  event.respondWith((async()=>{
   const cache=await caches.open(CACHE);
   try{const response=await fetch(request,{signal:AbortSignal.timeout(3500)});if(response.ok&&!response.redirected)await cache.put(url.pathname,response.clone());return response;}
   catch{return await cache.match(url.pathname)||new Response('通信を確認してtenを開き直してください。',{status:503});}
  })());
 }
});
