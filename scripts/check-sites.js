// Manual live checks, intentionally separate from deterministic test runs.
// In the managed cloud proxy only, TEN_TRUST_PROXY_DNS=1 may be needed.
import fs from 'node:fs';
import {fetchArticle} from '../lib/article.js';
const urls=process.argv.slice(2);
if(!urls.length)urls.push('https://note.com/deeptech/n/ndd8646916e9c','https://ja.wikipedia.org/wiki/読書','https://github.com/mozilla/readability');
const results=[];
for(const url of urls){
 const start=performance.now();
 try{const data=await fetchArticle(url);results.push({url,status:'extracted',title:data.title,characters:data.text.length,method:data.method,warnings:data.warnings,durationMs:Math.round(performance.now()-start)});}
 catch(error){results.push({url,status:'failed',...(error.result||{error:error.message}),durationMs:Math.round(performance.now()-start)});}
}
const report={checkedAt:new Date().toISOString(),kind:'live-url-fetch',results};
fs.mkdirSync('dist',{recursive:true});fs.writeFileSync('dist/site-check.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
if(results.some(r=>r.status==='failed'))process.exitCode=1;
