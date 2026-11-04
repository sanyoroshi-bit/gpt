import {createWorker} from 'tesseract.js';
import {createOcrSession} from './ocr-session.js';
import {cropRectangle} from './ocr-region.js';
const session=createOcrSession(logger=>{
 let fail,failed=false;
 const failure=new Promise((_,reject)=>{fail=reject;});
 return Promise.race([createWorker(['jpn','eng'],1,{
  workerPath:new URL('/ocr/worker.min.js',location.href).href,
  corePath:new URL('/ocr/tesseract-core-lstm.wasm.js',location.href).href,
  langPath:new URL('/ocr/lang',location.href).href,
  workerBlobURL:false,gzip:true,logger,errorHandler:e=>{failed=true;fail(new Error(String(e)));},
 }).then(worker=>{if(failed)worker.terminate();return worker;}),failure]);
});
export async function inspectImage(file){
 if(!['image/png','image/jpeg','image/webp'].includes(file.type)||file.size>15*1024*1024)throw new Error('15MB以下のPNG・JPEG・WebP画像を選んでください。');
 const image=await createImageBitmap(file);
 try{
  if(image.width*image.height>20000000)throw new Error('1画面分のスクリーンショットを選んでください。');
  return {width:image.width,height:image.height};
 }finally{image.close();}
}
export async function recognizeImage(file,progress,signal,region){
 signal.throwIfAborted();
 const started=performance.now();progress({status:'画像を準備しています',progress:0});
 const image=await createImageBitmap(file);
 let canvas;
 try{
  signal.throwIfAborted();
  const rect=cropRectangle(image.width,image.height,region);
  canvas=document.createElement('canvas');canvas.width=rect.width;canvas.height=rect.height;
  const ctx=canvas.getContext('2d');ctx.fillStyle='white';ctx.fillRect(0,0,canvas.width,canvas.height);
  ctx.drawImage(image,rect.left,rect.top,rect.width,rect.height,0,0,rect.width,rect.height);
  const prepared=performance.now();
  const result=await session.recognize(canvas,progress,signal);
  return {...result,timings:{...result.timings,imageMs:prepared-started,totalMs:performance.now()-started},rectangle:rect};
 }finally{image.close();if(canvas){canvas.width=0;canvas.height=0;}}
}
window.addEventListener('pagehide',()=>session.dispose());
document.addEventListener('visibilitychange',()=>{if(document.hidden)session.releaseIfIdle();});
