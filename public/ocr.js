import {createWorker} from 'tesseract.js';
export async function recognizeImage(file,progress,signal){
 let worker;
 function wait(promise){return new Promise((resolve,reject)=>{const abort=()=>{signal.removeEventListener('abort',abort);reject(new DOMException('Cancelled','AbortError'));};signal.addEventListener('abort',abort,{once:true});Promise.resolve(promise).then(v=>{signal.removeEventListener('abort',abort);resolve(v);},e=>{signal.removeEventListener('abort',abort);reject(e);});if(signal.aborted)abort();});}
 try {
  if(!['image/png','image/jpeg','image/webp'].includes(file.type)||file.size>15*1024*1024)throw new Error('15MB以下のPNG・JPEG・WebP画像を選んでください。');
  const image=await createImageBitmap(file),pixels=image.width*image.height;image.close();if(pixels>20000000)throw new Error('1画面分のスクリーンショットを選んでください。');
  worker=await wait(createWorker(['jpn','eng'],1,{workerPath:new URL('/ocr/worker.min.js',location.href).href,corePath:new URL('/ocr/tesseract-core-lstm.wasm.js',location.href).href,langPath:new URL('/ocr/lang',location.href).href,workerBlobURL:false,gzip:true,logger:e=>{if(!signal.aborted&&e.status==='recognizing text')progress(Math.round(e.progress*100));}}).then(w=>{if(signal.aborted)w.terminate();return w;}));
  const {data}=await wait(worker.recognize(file));return {text:data.text.trim(),confidence:data.confidence};
 }finally{await worker?.terminate();}
}
