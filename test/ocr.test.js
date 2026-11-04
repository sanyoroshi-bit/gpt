import test from 'node:test';
import assert from 'node:assert/strict';
import {createOcrSession} from '../public/ocr-session.js';
import {cropRectangle} from '../public/ocr-region.js';
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
const result=text=>({data:{text,confidence:90}});

test('OCR reuses a ready worker and releases it after inactivity',async()=>{
 let created=0,terminated=0;
 const session=createOcrSession(async()=>{created++;return {recognize:async()=>result(' 本文 '),terminate:()=>terminated++};},{idleMs:15});
 const first=await session.recognize('image');const second=await session.recognize('image');
 assert.equal(created,1);assert.equal(first.text,'本文');assert.equal(first.timings.reused,false);assert.equal(second.timings.reused,true);
 await new Promise(r=>setTimeout(r,30));assert.equal(terminated,1);
 await session.recognize('image');assert.equal(created,2);session.dispose();
});
test('cancel during recognition rejects promptly, terminates worker, ignores old progress, and permits retry',async()=>{
 const pending=deferred(),entered=deferred();let created=0,terminated=0,oldLogger;const updates=[];
 const session=createOcrSession(async logger=>{created++;if(created===1){oldLogger=logger;return {recognize:()=>{entered.resolve();return pending.promise;},terminate:()=>terminated++};}return {recognize:async()=>result('次の画像'),terminate(){}};});
 const controller=new AbortController(),job=session.recognize('image',e=>updates.push(e),controller.signal);
 await entered.promise;controller.abort();await assert.rejects(job,{name:'AbortError'});assert.equal(terminated,1);
 const n=updates.length;oldLogger({status:'recognizing text',progress:1});pending.resolve(result('古い画像'));
 assert.equal((await session.recognize('next')).text,'次の画像');assert.equal(updates.length,n);session.dispose();
});
test('cancel during initialization shares pending setup without duplicate engines',async()=>{
 const init=deferred();let created=0,recognized=0;
 const session=createOcrSession(()=>{created++;return init.promise;});const controller=new AbortController();
 const job=session.recognize('old',()=>{},controller.signal);controller.abort();await assert.rejects(job,{name:'AbortError'});
 const next=session.recognize('new');init.resolve({recognize:async image=>{recognized++;return result(image);},terminate(){}});
 assert.equal((await next).text,'new');assert.equal(created,1);assert.equal(recognized,1);session.dispose();
});
test('failed initialization can be retried; dispose cancels running work',async()=>{
 let created=0;const entered=deferred();
 const session=createOcrSession(async()=>{if(++created===1)throw new Error('offline');return {recognize:()=>{entered.resolve();return new Promise(()=>{});},terminate(){}};});
 await assert.rejects(session.recognize('image'),/offline/);
 const job=session.recognize('next');await entered.promise;session.dispose();await assert.rejects(job,{name:'AbortError'});assert.equal(created,2);
});
test('cropping keeps original coordinates/resolution and rejects inverted boundaries',()=>{
 assert.deepEqual(cropRectangle(1080,1920,{left:10,top:25,right:90,bottom:75}),{left:108,top:480,width:864,height:960});
 assert.deepEqual(cropRectangle(1080,1920),{left:0,top:0,width:1080,height:1920});
 for(const region of [{left:90,top:0,right:10,bottom:100},{left:0,top:0,right:100,bottom:NaN}])assert.throws(()=>cropRectangle(1080,1920,region));
});
test('recognition failure discards engine; simultaneous jobs are rejected',async()=>{
 const pending=deferred(),entered=deferred();let created=0,terminated=0;
 const session=createOcrSession(async()=>{created++;return {recognize:()=>{if(created>1)return Promise.resolve(result('復旧'));entered.resolve();return pending.promise;},terminate:()=>terminated++};});
 const job=session.recognize('one');await entered.promise;
 await assert.rejects(session.recognize('two'),/前の読み取り/);
 pending.reject(new Error('broken'));await assert.rejects(job,/broken/);assert.equal(terminated,1);
 assert.equal((await session.recognize('retry')).text,'復旧');session.dispose();
});
