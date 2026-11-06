import test from 'node:test';
import assert from 'node:assert/strict';
import handler,{createArticleHandler,API_VERSION} from '../api/article.js';
import {readArticleResponse} from '../public/article-client.js';
import {app} from '../server.js';
import {readFile} from 'node:fs/promises';
function response(){return {headers:{},setHeader(k,v){this.headers[k]=v;},status(n){this.statusCode=n;return this;},json(data){this.body=data;return this;}};}

test('file-based endpoint configuration and local initialization match',async()=>{
 const config=JSON.parse(await readFile(new URL('../vercel.json',import.meta.url)));
 assert.ok(config.functions['api/article.js']);assert.equal(config.rewrites,undefined);
 const res=response();await handler({method:'GET',url:'/api/article'},res);
 assert.equal(res.statusCode,200);assert.equal(res.body.version,API_VERSION);assert.equal(res.headers['Cache-Control'],'no-store');
});
test('extraction dependency startup failure returns actionable JSON rather than an HTML crash',async()=>{
 const broken=createArticleHandler(async()=>{throw Object.assign(new Error('private path /do-not-expose'),{code:'ERR_MODULE_NOT_FOUND'});},()=>{});
 const res=response();await broken({method:'POST',body:{url:'https://note.com/deeptech/n/ndd8646916e9c'}},res);
 assert.equal(res.statusCode,503);assert.equal(res.body.code,'ARTICLE_SERVICE_UNAVAILABLE');assert.match(res.body.error,/起動できません/);assert.equal(res.body.diagnostic.code,'ERR_MODULE_NOT_FOUND');assert.ok(!JSON.stringify(res.body).includes('do-not-expose'));
});
test('function accepts parsed and string JSON bodies, with no request path dependency',async()=>{
 const successful=createArticleHandler(async()=>({fetchArticle:async url=>({title:'記事',text:'取得した本文',source:url})}));
 for(const body of [{url:'https://note.com/example'},'{"url":"https://note.com/example"}']){
  const res=response();await successful({method:'POST',body},res);assert.equal(res.statusCode,200);assert.equal(res.body.source,'https://note.com/example');
 }
 for(const body of [undefined,null,{},'{bad json}',{url:123},{url:'x'.repeat(5000)}]){
  const res=response();await handler({method:'POST',body},res);assert.equal(res.statusCode,400);assert.equal(res.body.code,'INVALID_REQUEST');
 }
 const res=response();await handler({method:'PUT'},res);assert.equal(res.statusCode,405);
});
test('public function retains private-network rejection',async()=>{
 const res=response();await handler({method:'POST',body:{url:'http://169.254.169.254/'}},res);
 assert.equal(res.statusCode,422);assert.match(res.body.error,/プライベート/);
});
test('browser distinguishes missing endpoint, platform failure, bad response, and source errors',async()=>{
 for(const [status,message] of [[404,/機能が見つかりません/],[403,/アクセスが制限/],[500,/起動・処理に失敗/],[504,/時間切れ/],[200,/別のページ/]]){
  await assert.rejects(readArticleResponse(new Response('<html>Error</html>',{status,headers:{'Content-Type':'text/html'}})),message);
 }
 await assert.rejects(readArticleResponse(Response.json({error:'記事サイトがアクセスを受け付けませんでした。'},{status:422})),/記事サイト/);
 await assert.rejects(readArticleResponse(Response.json({text:123})),/記事本文/);
 await assert.rejects(readArticleResponse(new Response('{bad',{headers:{'Content-Type':'application/json'}})),/正しい応答/);
 assert.deepEqual(await readArticleResponse(Response.json({title:'記事',text:'本文'})),{title:'記事',text:'本文'});
});
test('development API returns JSON for malformed bodies and missing API paths',async()=>{
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));
 try{
  const base=`http://127.0.0.1:${server.address().port}`;
  const r=await fetch(base+'/api/article',{method:'POST',headers:{'Content-Type':'application/json'},body:'{bad json}'});
  assert.equal(r.status,400);assert.equal((await r.json()).code,'INVALID_REQUEST');
  const missing=await fetch(base+'/api/missing');assert.equal(missing.status,404);assert.equal((await missing.json()).code,'API_NOT_FOUND');
 }finally{server.closeAllConnections();await new Promise(r=>server.close(r));}
});
test('an expired total request budget also stops URL validation',async()=>{
 const {validateUrl}=await import('../lib/article.js');const controller=new AbortController();controller.abort(new DOMException('Budget expired','TimeoutError'));
 await assert.rejects(validateUrl('https://example.org/article',{signal:controller.signal}),{name:'TimeoutError'});
});
test('GET initializes dependencies but never fetches an external article',async()=>{
 let loads=0,fetches=0;const check=createArticleHandler(async()=>{loads++;return {fetchArticle:async()=>{fetches++;}};});
 const res=response();await check({method:'GET'},res);assert.equal(loads,1);assert.equal(fetches,0);assert.equal(res.body.status,'dependencies-ready');assert.equal(res.body.scope,'initialization-only');
});
test('GET and POST expose safe initialization diagnostics, not private exception details',async()=>{
 for(const method of ['GET','POST']){
  const check=createArticleHandler(async()=>{throw Object.assign(new Error('/secret/path?token=private'),{name:'bad name /secret',code:'bad\ncode'});},()=>{});
  const res=response();await check({method,body:{url:'https://note.com/test'}},res);
  assert.equal(res.statusCode,503);assert.equal(res.body.diagnostic.code,'UNKNOWN');assert.equal(res.body.diagnostic.name,'UNKNOWN');assert.equal(res.body.diagnostic.phase,'initialization');assert.ok(!JSON.stringify(res.body).includes('private'));assert.equal(res.body.diagnostic.apiVersion,API_VERSION);
 }
});
