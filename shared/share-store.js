const DB='ten-shared-input',STORE='pending',TTL=10*60*1000;
function open(){return new Promise((resolve,reject)=>{const request=indexedDB.open(DB,1);request.onupgradeneeded=()=>request.result.createObjectStore(STORE,{keyPath:'id'});request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});}
function complete(tx){return new Promise((resolve,reject)=>{tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error||new Error('保存を中止しました。'));});}
export async function saveSharedInput(input){
 const db=await open();
 try{
  const tx=db.transaction(STORE,'readwrite'),done=complete(tx),store=tx.objectStore(STORE),id=crypto.randomUUID();
  // Only the latest shared item is retained. The URL contains an opaque ID, never the passage.
  store.clear();store.put({id,createdAt:Date.now(),text:input.text.slice(0,200000),url:input.url.slice(0,4096),title:input.title.slice(0,300)});
  await done;return id;
 }finally{db.close();}
}
export async function takeSharedInput(id){
 if(typeof id!=='string'||!/^[a-f0-9-]{36}$/i.test(id))return null;
 const db=await open();
 try{
  const tx=db.transaction(STORE,'readwrite'),done=complete(tx),store=tx.objectStore(STORE),request=store.get(id);
  const [result]=await Promise.all([new Promise((resolve,reject)=>{request.onsuccess=()=>{const record=request.result;store.delete(id);resolve(record);};request.onerror=()=>reject(request.error);}),done]);
  return result&&Date.now()-result.createdAt<TTL?result:null;
 }finally{db.close();}
}
