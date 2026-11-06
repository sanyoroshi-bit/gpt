import {extractDocument} from '../shared/article-extractor.js';
import {normalizeArticleUrl} from '../shared/article-input.js';
export async function importHtml(file){
 if(!/\.html?$/i.test(file.name)&&!['text/html','application/xhtml+xml'].includes(file.type))throw new Error('ブラウザで保存したHTMLファイル（.html）を選んでください。');
 if(file.size>5*1024*1024)throw new Error('5MB以下のHTMLファイルを選んでください。');
 const bytes=await file.arrayBuffer();
 const prefix=new TextDecoder('ascii').decode(bytes.slice(0,4096));
 const encoding=prefix.match(/charset\s*=\s*["']?([\w-]+)/i)?.[1]||'utf-8';
 let html;try{html=new TextDecoder(encoding).decode(bytes);}catch{html=new TextDecoder().decode(bytes);}
 // A separate document has no browsing context: scripts, images, frames and CSS are never attached to the live page.
 const doc=document.implementation.createHTMLDocument('');
 doc.documentElement.innerHTML=html;
 const source=normalizeArticleUrl(doc.querySelector('link[rel="canonical"]')?.getAttribute('href'))||normalizeArticleUrl(doc.querySelector('meta[property="og:url"]')?.getAttribute('content'));
 return extractDocument(doc,source);
}
