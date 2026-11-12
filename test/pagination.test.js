import test from 'node:test';
import assert from 'node:assert/strict';
import {createArticleFetcher} from '../lib/article.js';
import {consecutivePage} from '../shared/article-pagination.js';
const root='https://news.example.org/articles/reading-123.html';
const paragraph=n=>`第${n}ページの本文です。図表や見出しを含めずに、本文を最後まで読み進めます。次の記事へ勝手に移動しないことを確認します。`;
function html(n,{next=n<3?`${root}?page=${n+1}`:null,title='本文を読む方法',body=paragraph(n)}={}){return `<html><title>${title}</title><link rel="canonical" href="${root}">${next?`<link rel="next" href="${next}">`:''}<h1>${title}</h1><div itemprop="articleBody"><h2>除外する見出し</h2><p>${body}</p><table><tr><td>除外する表</td></tr></table></div></html>`;}
function fixture(entries,{deny}={}){
 const calls=[],validated=[],signals=new Set();
 const fetcher=createArticleFetcher({validate:async(value,{signal})=>{signal.throwIfAborted();const url=new URL(value);validated.push(url.href);signals.add(signal);if(deny?.(url))throw new Error('Private address blocked');return url;},request:async(url,{signal})=>{signals.add(signal);calls.push(url.href);const data=entries[url.href];if(data instanceof Response)return data;if(!data)return new Response('not found',{status:404});return new Response(data,{headers:{'content-type':'text/html; charset=utf-8'}});}});
 return {fetcher,calls,validated,signals};
}
test('only stable numbered pages can be consecutive, never next articles or page jumps',()=>{
 assert.equal(consecutivePage(root,root+'?page=2'),true);assert.equal(consecutivePage(root,root.replace('.html','_2.html')),true);
 for(const other of [root+'?page=3',root+'?page=0',root+'?page=2&page=3','https://other.example.org/articles/reading-123.html?page=2',root.replace('123','124')+'?page=2',root+'?page=2&article=other'])assert.equal(consecutivePage(root,other),false,other);
});
test('URL import combines verified pages of the same article and strips titles and tables',async()=>{
 const f=fixture({[root]:html(1),[root+'?page=2']:html(2),[root+'?page=3']:html(3)}),result=await f.fetcher(root);
 assert.equal(result.pagesRead,3);assert.equal(result.paginationStatus,'verified-pages');assert.equal(f.signals.size,1);
 assert.equal(result.text,[1,2,3].map(paragraph).join('\n\n'));assert.ok(!result.text.includes('除外'));assert.deepEqual(f.calls,[root,root+'?page=2',root+'?page=3']);
});
test('a one-page article with next-article rel=next never fetches the next article',async()=>{
 const other=root.replace('123','456');const f=fixture({[root]:html(1,{next:other})}),r=await f.fetcher(root);assert.equal(r.pagesRead,1);assert.equal(r.paginationStatus,'single-page');assert.deepEqual(f.calls,[root]);
});
test('changed headline or missing canonical preserves only verified pages with a partial warning',async()=>{
 for(const second of [html(2,{title:'別の記事の見出し'}),html(2).replace(/<link rel="canonical"[^>]+>/,'')]){
  const f=fixture({[root]:html(1),[root+'?page=2']:second}),r=await f.fetcher(root);assert.equal(r.text,paragraph(1));assert.equal(r.paginationStatus,'partial');assert.ok(r.warnings[0].includes('途中まで'));
 }
});
test('number suffixes and query page keys cannot collapse different self-canonical articles',async()=>{
 for(const [a,b] of [['https://news.example.org/story-1.html','https://news.example.org/story-2.html'],['https://news.example.org/read?p=1','https://news.example.org/read?p=2']]){
  const page=(url,next,text)=>`<title>最新の経済ニュース</title><link rel="canonical" href="${url}">${next?`<link rel="next" href="${next}">`:''}<article><p>${text}</p></article>`;
  const f=fixture({[a]:page(a,b,paragraph(1)),[b]:page(b,null,paragraph(2))}),result=await f.fetcher(a);
  assert.equal(result.pagesRead,1);assert.equal(result.text,paragraph(1));assert.equal(result.paginationStatus,'partial');
 }
});
test('generic page controllers do not treat p as pagination without a stable article identifier',async()=>{
 const a='https://news.example.org/read?p=1',b='https://news.example.org/read?p=2',doc=(url,next,n)=>`<title>最新の経済ニュース</title><link rel="canonical" href="https://news.example.org/read">${next?`<link rel="next" href="${next}">`:''}<article><p>${paragraph(n)}</p></article>`;
 const f=fixture({[a]:doc(a,b,1),[b]:doc(b,null,2)}),r=await f.fetcher(a);assert.equal(r.pagesRead,1);assert.equal(r.paginationStatus,'partial');assert.deepEqual(f.calls,[a]);
});
test('automatic pagination validates every redirect and refuses a private or different article destination',async()=>{
 const next=root+'?page=2',privateUrl='http://127.0.0.1/secret';
 const f=fixture({[root]:html(1),[next]:new Response(null,{status:302,headers:{location:privateUrl}})},{deny:url=>url.hostname==='127.0.0.1'}),r=await f.fetcher(root);
 assert.ok(f.validated.includes(privateUrl));assert.deepEqual(f.calls,[root,next]);assert.equal(r.paginationStatus,'partial');assert.equal(r.text,paragraph(1));
 const other=root.replace('123','456')+'?page=2',g=fixture({[root]:html(1),[next]:new Response(null,{status:302,headers:{location:other}})});assert.equal((await g.fetcher(root)).paginationStatus,'partial');assert.ok(!g.calls.includes(other));
});
test('repeated content and five-page limit cannot make an automatic request loop',async()=>{
 const f=fixture({[root]:html(1),[root+'?page=2']:html(2,{body:paragraph(1)})}),r=await f.fetcher(root);assert.equal(r.pagesRead,1);assert.equal(r.paginationStatus,'partial');
 const entries={};for(let n=1;n<=6;n++)entries[n===1?root:root+'?page='+n]=html(n,{next:root+'?page='+(n+1)});
 const g=fixture(entries),limited=await g.fetcher(root);assert.equal(limited.pagesRead,5);assert.equal(limited.paginationStatus,'partial');assert.equal(g.calls.length,5);
});
test('a failed continuation keeps the first page without claiming complete multi-page success',async()=>{
 const f=fixture({[root]:html(1)}),r=await f.fetcher(root);assert.equal(r.pagesRead,1);assert.equal(r.paginationStatus,'partial');assert.equal(r.text,paragraph(1));
});
test('skipped, backward and cyclic next-page candidates warn instead of claiming a complete single page',async()=>{
 for(const [start,next] of [[root,root+'?page=3'],[root+'?page=2',root],[root,root]]){
  const f=fixture({[start]:html(start===root?1:2,{next})}),r=await f.fetcher(start);assert.equal(r.pagesRead,1);assert.equal(r.paginationStatus,'partial');assert.ok(r.warnings[0].includes('途中まで'));assert.deepEqual(f.calls,[start]);
 }
 const f=fixture({[root]:html(1),[root+'?page=2']:html(2,{next:root})}),r=await f.fetcher(root);assert.equal(r.pagesRead,2);assert.equal(r.paginationStatus,'partial');assert.equal(f.calls.length,2);
});
test('the total byte budget is shared across pages and preserves only completed pages',async()=>{
 const padding='<!--'+'x'.repeat(2100000)+'-->',f=fixture({[root]:html(1)+padding,[root+'?page=2']:html(2)+padding,[root+'?page=3']:html(3)+padding});
 const r=await f.fetcher(root);assert.equal(r.pagesRead,2);assert.equal(r.paginationStatus,'partial');assert.equal(r.text,paragraph(1)+'\n\n'+paragraph(2));
});
test('one timeout budget covers the whole article instead of restarting for each page',async()=>{
 const alive=setTimeout(()=>{},3000);const signals=[];
 try{
  const fetcher=createArticleFetcher({budgetMs:1000,validate:async(value,{signal})=>{signal.throwIfAborted();signals.push(signal);return new URL(value);},request:async(url,{signal})=>{
   if(!url.search)return new Response(html(1),{headers:{'content-type':'text/html'}});
   return new Promise((resolve,reject)=>{signal.addEventListener('abort',()=>reject(signal.reason),{once:true});if(signal.aborted)reject(signal.reason);});
  }});
  const r=await fetcher(root);assert.equal(r.pagesRead,1);assert.equal(r.paginationStatus,'partial');assert.equal(new Set(signals).size,1);assert.equal(signals[0].aborted,true);
 }finally{clearTimeout(alive);}
});
