// Usage: node scripts/check-snapshots.js /path/to/mozilla/readability
// Uses upstream historical fixtures locally; it does not check today's websites.
import fs from 'node:fs';
import path from 'node:path';
import {JSDOM,VirtualConsole} from 'jsdom';
import {extract} from '../lib/article.js';
const root=process.argv[2];if(!root)throw new Error('Pass a local mozilla/readability checkout.');
const normalize=text=>text.replace(/\s/g,'');
const results=[];
for(const name of ['medium-1','bbc-1','blogger','wordpress','wikipedia','hukumusume']){
 const dir=path.join(root,'test/test-pages',name),html=fs.readFileSync(path.join(dir,'source.html'),'utf8');
 const doc=new JSDOM(html,{virtualConsole:new VirtualConsole()});
 const canonical=doc.window.document.querySelector('link[rel="canonical"]')?.href||doc.window.document.querySelector('meta[property="og:url"]')?.content;doc.window.close();
 const source=canonical?.startsWith('http')?canonical:'https://example.org/'+name;
 const expected=new JSDOM(fs.readFileSync(path.join(dir,'expected.html'),'utf8'));
 const paragraphs=[...expected.window.document.querySelectorAll('p')].map(p=>normalize(p.textContent)).filter(t=>t.length>=40);
 const expectedCharacters=normalize(expected.window.document.body.textContent).length;expected.window.close();
 try{
  const result=extract(html,source),text=normalize(result.text),matched=paragraphs.filter(p=>text.includes(p)).length;
  results.push({fixture:name,status:matched===paragraphs.length?'passed':'review',method:result.method,characters:text.length,expectedCharacters,paragraphsMatched:matched,paragraphs:paragraphs.length});
 }catch(e){results.push({fixture:name,status:'failed',error:e.message});}
}
fs.mkdirSync('dist',{recursive:true});const report={kind:'historical-html-snapshots',results};fs.writeFileSync('dist/snapshot-check.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
if(results.some(r=>r.status!=='passed'))process.exitCode=1;
