import test from 'node:test';
import assert from 'node:assert/strict';
import {extract} from '../lib/article.js';
import {normalizeArticleUrl,sharedInput} from '../shared/article-input.js';
const first='朝の読書では、見出しと本文を順番に確認します。気になった部分は元の記事に戻って図や写真と見比べます。';
const second='夕方には読み終えた内容を振り返ります。文章は省略せず、読みやすい速さで自分の理解を確かめます。';
const body=`<h2>朝の読書</h2><p>${first}</p><h2>夕方の読書</h2><p>${second}</p><figure><img src="https://images.example/figure.png"><figcaption>図1：読む順番</figcaption></figure>`;
const fixtures=[
 ['note','https://note.com/test/n/n123456789abc','<div class="note-common-styles__textnote-body">','</div>'],
 ['qiita','https://qiita.com/test/items/1234567890abcdef1234','<div id="personal-public-article-body">','</div>'],
 ['zenn','https://zenn.dev/test/articles/reading','<article><div class="znc">','</div></article>'],
 ['hatena','https://staff.hatenablog.com/entry/test','<div class="entry-content">','</div>'],
 ['wikipedia','https://ja.wikipedia.org/wiki/読書','<div id="mw-content-text"><div class="mw-parser-output">','</div></div>'],
 ['nhk','https://www3.nhk.or.jp/news/test','<div class="content--detail-body">','</div>'],
 ['itmedia','https://www.itmedia.co.jp/news/test','<div id="cmsBody">','</div>'],
];
for(const [name,url,start,end] of fixtures)test(`${name}: known article structure keeps prose, excludes headings, captions and related content`,()=>{
 const html=`<html><title>読む時間</title><nav>会員登録メニュー</nav>${start}${body}<div class="related-articles">おすすめ商品の広告</div><p hidden>隠された本文</p>${end}<footer>運営会社</footer></html>`;
 const result=extract(html,url);assert.equal(result.method,'site:'+name);
 assert.ok(!result.text.includes('朝の読書\n'));assert.ok(result.text.includes(first));assert.ok(result.text.includes(second));assert.ok(!result.text.includes('図1：読む順番'));
 for(const unwanted of ['会員登録','広告','運営会社','隠された'])assert.ok(!result.text.includes(unwanted));
 assert.ok(result.warnings.some(w=>w.includes('図・写真・表')));assert.equal(result.source,new URL(url).href);
});
test('custom-domain blogs and WordPress do not read Jetpack share/related cards',()=>{
 const result=extract(`<title>日記</title><div class="entry-content">${body}<div class="sharedaddy">SNS共有ボタン</div><div class="jp-relatedposts">別の記事の要約</div></div>`,'https://blog.example.org/entry');
 assert.equal(result.method,'article-body');assert.ok(!result.text.includes('SNS共有'));assert.ok(!result.text.includes('別の記事'));
});
test('public structured articleBody is a fallback; summaries and paywalled embedded text are not',()=>{
 const metadata={'@context':'https://schema.org','@type':'NewsArticle',headline:'今日の読書',articleBody:first+'\n\n'+second};
 const html=m=>`<html><script type="application/ld+json">${JSON.stringify(m)}</script><body><h1>今日の読書</h1></body></html>`;
 assert.equal(extract(html(metadata),'https://news.example.org/article').method,'structured-data');
 assert.throws(()=>extract(html({...metadata,articleBody:undefined,description:first}),'https://news.example.org/article'),/本文を見つけ/);
 assert.throws(()=>extract(html({...metadata,isAccessibleForFree:false}),'https://news.example.org/article'),/会員・有料/);
 const preview=html({...metadata,isAccessibleForFree:false}).replace('</body>',`<article><p>${first}</p></article></body>`);
 const r=extract(preview,'https://news.example.org/article');assert.ok(!r.text.includes(second));assert.ok(r.warnings.some(w=>w.includes('会員・有料')));
});
test('structured data selects matching article, tolerates malformed JSON, and never replaces visible text',()=>{
 const m={'@graph':[{'@type':'Article',url:'https://example.org/other',articleBody:second},{'@type':'Article',url:'https://example.org/current',articleBody:first}]};
 const html=`<script type="application/ld+json">{broken</script><script type="application/ld+json">${JSON.stringify(m)}</script>`;
 assert.equal(extract(html,'https://example.org/current').text,first);
 assert.equal(extract(html+`<div itemprop="articleBody"><p>${second}</p></div>`,'https://example.org/current').text,second);
});
test('table cells are excluded while surrounding prose remains; challenge pages are not treated as articles',()=>{
 const html=`<div itemprop="articleBody"><p>${first}</p><table><tr><th>朝</th><th>夜</th></tr><tr><td>10</td><td>20</td></tr></table></div>`;
 const r=extract(html,'https://example.org/article');assert.ok(r.text.includes(first));assert.ok(!r.text.includes('朝\t夜'));assert.ok(!r.text.includes('10\t20'));
 assert.throws(()=>extract(`<title>Just a moment...</title><p>${first}</p>`,'https://example.org/article'),/アクセス確認/);
});
test('Readability cannot unwrap a one-cell table and reintroduce it as prose',()=>{
 const r=extract(`<title>本文を読む</title><article><h1>除外する見出し</h1><p>${first}</p><table><tr><td>除外する表の一セル</td></tr></table><p>${second}</p></article>`,'https://example.org/article');
 assert.equal(r.method,'readability');assert.ok(r.text.includes(first));assert.ok(r.text.includes(second));assert.ok(!r.text.includes('除外する表'));assert.ok(!r.text.includes('除外する見出し'));
});
test('URLs can come from share text or Markdown; long shared passages stay intact',()=>{
 const url='https://note.com/deeptech/n/ndd8646916e9c';
 for(const input of [url,`[読む記事](${url})`,`<${url}>`,`読む記事\n${url} #note`,`読む記事（${url}）`])assert.equal(normalizeArticleUrl(input,{embedded:true}),url);
 assert.equal(normalizeArticleUrl('https://example.org/a https://example.org/b',{embedded:true}),null);
 assert.equal(normalizeArticleUrl('javascript:alert(1)'),null);assert.equal(normalizeArticleUrl('https://user:pass@example.org'),null);
 assert.equal(sharedInput(new URLSearchParams({text:`読む記事 ${url}`})).kind,'url');
 const passage=first.repeat(20)+url;assert.deepEqual(sharedInput(new URLSearchParams({text:passage})),{kind:'text',value:passage});
});
