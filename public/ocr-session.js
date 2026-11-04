// One worker per session, no concurrent recognition or late cancelled-job updates.
export function createOcrSession(factory,{idleMs=60000}={}){
 let record=null,active=null,idleTimer;

 const terminate=r=>{if(r.worker)Promise.resolve(r.worker.terminate()).catch(()=>{});};
 function release(r){
  if(!r||r.disposed)return;
  r.disposed=true;terminate(r);
  if(record===r){record=null;clearTimeout(idleTimer);}
 }
 function expire(r){
  clearTimeout(idleTimer);
  if(record===r&&!active)idleTimer=setTimeout(()=>release(r),idleMs);
 }
 function getRecord(){
  if(record)return record;
  const r={worker:null,disposed:false};record=r;
  r.promise=Promise.resolve().then(()=>factory(e=>{
   if(active?.record===r&&!active.signal.aborted)active.progress(e);
  })).then(w=>{r.worker=w;if(r.disposed)terminate(r);else if(!active)expire(r);return w;},e=>{release(r);throw e;});
  return r;
 }
 function wait(promise,signal){
  return new Promise((resolve,reject)=>{
   const abort=()=>{signal.removeEventListener('abort',abort);reject(signal.reason);};
   signal.addEventListener('abort',abort,{once:true});
   Promise.resolve(promise).then(resolve,reject).finally(()=>signal.removeEventListener('abort',abort));
   if(signal.aborted)abort();
  });
 }
 return {
  async recognize(image,progress=()=>{},signal=new AbortController().signal){
   signal.throwIfAborted();
   if(active)throw new Error('前の読み取りが終わるまでお待ちください。');
   const controller=new AbortController();
   signal=AbortSignal.any([signal,controller.signal,AbortSignal.timeout(120000)]);
   clearTimeout(idleTimer);
   const r=getRecord(),reused=!!r.worker,job={record:r,progress,signal,controller};active=job;
   const started=performance.now();
   try{
    progress({status:reused?'準備済みの読み取り機能を使います':'読み取り機能を準備しています',progress:0});
    const worker=await wait(r.promise,signal);signal.throwIfAborted();
    const ready=performance.now();
    progress({status:'recognizing text',progress:0});
    const {data}=await wait(worker.recognize(image),signal);signal.throwIfAborted();
    return {text:data.text.trim(),confidence:data.confidence,timings:{setupMs:ready-started,recognitionMs:performance.now()-ready,reused}};
   }catch(e){
    // Pending initialization can serve the next job; it has no image content.
    if(e.name!=='AbortError'||r.worker)release(r);
    throw e;
   }finally{
    if(active===job)active=null;
    expire(r);
   }
  },
  releaseIfIdle(){if(!active)release(record);},
  dispose(){active?.controller.abort();release(record);},
 };
}
