import express from 'express';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import articleHandler from './api/article.js';
export {isPublic,validateUrl,extract,articleFailure} from './lib/article.js';

export const app=express();
app.disable('x-powered-by');
app.use(express.json({limit:'8kb'}));
app.use((req,res,next)=>{
 res.set('X-Content-Type-Options','nosniff');
 res.set('Content-Security-Policy',"default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'");
 next();
});
app.all('/api/article',articleHandler);
app.use('/api',(req,res)=>res.status(404).json({code:'API_NOT_FOUND',error:'記事取り込みの接続先が見つかりません。'}));
app.use((error,req,res,next)=>{
 if(!req.path.startsWith('/api/'))return next(error);
 const large=error.type==='entity.too.large';
 res.status(large?413:400).json({code:large?'REQUEST_TOO_LARGE':'INVALID_REQUEST',error:large?'URLが長すぎます。記事のURLだけを入力してください。':'送信形式を確認できませんでした。ページを更新して再試行してください。'});
});
app.use(express.static(path.join(path.dirname(fileURLToPath(import.meta.url)),'public')));
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))app.listen(Number(process.env.PORT||3000),'0.0.0.0',()=>console.log('ten listening on '+(process.env.PORT||3000)));
