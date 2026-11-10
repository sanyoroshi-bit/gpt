import {test} from 'node:test';import assert from 'node:assert/strict';import {tokenize,duration,characters} from '../public/reader.js';import {shareUrl} from '../public/share-url.js';import {extract,isPublic,validateUrl,app} from '../server.js';
test('長い表示単位を12書記素以内にし、全文・順序・絵文字を保つ',()=>{
 const samples=['A'.repeat(11),'B'.repeat(12),'C'.repeat(13),'https://example.com/'+('longpath'.repeat(20)),'「'+('👨‍👩‍👧‍👦🇯🇵e\u0301'.repeat(16))+'」。','あ'.repeat(80)];
 for(const mode of ['phrase','word'])for(const size of [4,6,10])for(const text of samples){
  const units=tokenize(text,size,mode);
  assert.equal(units.join(''),text.normalize('NFC'));
  assert.ok(units.every(unit=>characters(unit).length<=12));
  assert.deepEqual(units.flatMap(characters),characters(text.normalize('NFC')));
 }
 assert.deepEqual(tokenize('Z'.repeat(13),6,'word'),['Z'.repeat(12),'Z']);
 assert.deepEqual(tokenize('👨‍👩‍👧‍👦'.repeat(13),6,'character'),Array(13).fill('👨‍👩‍👧‍👦'));
});
test('入力上限の長い単語も欠落させず表示単位に分割する',()=>{
 const text='x'.repeat(200000);
 for(const mode of ['phrase','word']){const units=tokenize(text,6,mode);assert.equal(units.join(''),text);assert.equal(units.length,Math.ceil(text.length/12));}
});
test('連続空白で空の読書表示を作らず、文字の順序を保つ',()=>{
 const text='abc'+' \t　'.repeat(40)+'def';
 for(const mode of ['phrase','word']){const units=tokenize(text,6,mode);assert.ok(units.every(unit=>unit.trim()&&characters(unit).length<=12));assert.equal(units.join('').replace(/\s/g,''),'abcdef');}
});
test('意味のまとまり・語尾・数字を保つ',()=>{assert.deepEqual(tokenize('新しい景色に出会うこと。'),['新しい景色に','出会うこと。']);for(const size of [4,6,10]){const c=tokenize('東京都で人工知能について勉強しています。',size);assert.ok(c.includes('人工知能について'));assert.ok(c.includes('勉強しています。'));assert.ok(tokenize('2026年11月3日に発売です。',size).some(t=>t.includes('2026')));}});
test('モードを切り替えても文字を失わず、絵文字を分割しない',()=>{assert.deepEqual(tokenize('👨‍👩‍👧‍👦 e\u0301 🇯🇵',6,'character'),['👨‍👩‍👧‍👦','é','🇯🇵']);for(const mode of ['phrase','word','character']){const text='「新しい本」を読む。価格は1,200円です。';assert.equal(tokenize(text,6,mode).join('').replace(/\s/g,''),text);}assert.deepEqual(tokenize(' \n'),[]);assert.deepEqual(tokenize('Hello, world!',6,'word'),['Hello,','world!']);});
test('速度・句読点の間・表示時間の下限',()=>{assert.equal(duration('あいうえお',600,false),500);assert.equal(duration('あいうえお',6000,false),50);assert.equal(duration('あ',6000,false),16);assert.ok(duration('終わり。',600)>duration('終わり。',600,false));});
test('共有QRは公開HTTPS URLだけを使う',()=>{assert.equal(shareUrl('https://example.com/ten'),'https://example.com/ten?start=image');for(const u of ['https://127.0.0.1','https://[::1]','https://localhost','https://192.168.1.1','file:///ten.html','https://user:pass@example.com'])assert.throws(()=>shareUrl(u));});
test('本文抽出と内部IP拒否を検証する',async()=>{for(const a of ['127.0.0.1','169.254.169.254','10.0.0.1','::1','::ffff:127.0.0.1'])assert.equal(isPublic(a),false);for(const u of ['http://127.0.0.1/private','file:///etc/passwd','https://user:pass@example.com'])await assert.rejects(validateUrl(u));const text='読書は生活に新しい視点を与えてくれます。気になった文章を読み直して、自分のペースで楽しみましょう。';const a=extract('<html><title>読書の記事</title><nav>不要なメニュー</nav><article>'+('<p>'+text+'</p>').repeat(8)+'</article></html>','https://example.com');assert.ok(a.text.includes(text));assert.ok(!a.text.includes('不要なメニュー'));});
test('HTTP APIが内部URLを拒否する',async()=>{const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));try{const base=`http://127.0.0.1:${server.address().port}`;assert.equal((await fetch(base)).status,200);const r=await fetch(base+'/api/article',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:'http://127.0.0.1/private'})});assert.equal(r.status,422);}finally{server.closeAllConnections();await new Promise(r=>server.close(r));}});
