import {Readability} from '@mozilla/readability';
import {normalizeArticleUrl} from './article-input.js';

// These selectors are fallbacks for known article layouts, not a live compatibility claim.
export const SITE_RULES=[
 {id:'note',name:'note',host:h=>h==='note.com',selectors:['.note-common-styles__textnote-body','.p-article__body']},
 {id:'qiita',name:'Qiita',host:h=>h==='qiita.com',selectors:['#personal-public-article-body','.it-MdContent']},
 {id:'zenn',name:'Zenn',host:h=>h==='zenn.dev',selectors:['article .znc','.znc']},
 {id:'hatena',name:'はてなブログ',host:h=>['hatenablog.com','hatenablog.jp','hateblo.jp','hatenadiary.jp','hatenadiary.com'].some(d=>h===d||h.endsWith('.'+d)),selectors:['.entry-content']},
 {id:'wikipedia',name:'Wikipedia',host:h=>h.endsWith('.wikipedia.org'),selectors:['#mw-content-text .mw-parser-output','.mw-parser-output']},
 {id:'nhk',name:'NHK',host:h=>h==='nhk.or.jp'||h.endsWith('.nhk.or.jp'),selectors:['.content--detail-body']},
 {id:'itmedia',name:'ITmedia',host:h=>h==='itmedia.co.jp'||h.endsWith('.itmedia.co.jp'),selectors:['#cmsBody']},
];
const BLOCKS=new Set(['ADDRESS','ARTICLE','ASIDE','BLOCKQUOTE','DIV','DL','DT','DD','FIGCAPTION','FIGURE','H1','H2','H3','H4','H5','H6','HR','LI','MAIN','OL','P','PRE','SECTION','TABLE','TR','UL']);
const NOISE='script,style,noscript,template,rt,rp,nav,footer,form,button,input,select,textarea,iframe,svg,[hidden],[aria-hidden="true"],[role="navigation"],.mw-editsection,.navbox,.vertical-navbox,.toc,.reflist .mw-cite-backlink,.entry-footer,.related-articles,.recommended-articles,.social-share,.share-buttons,.sharedaddy,.jp-relatedposts,[data-ad],.advertisement';
export function articleError(code,message){const error=new Error(message);error.code=code;return error;}
function clean(node){
 const copy=node.cloneNode(true);
 for(const item of copy.querySelectorAll(NOISE))item.remove();
 for(const item of copy.querySelectorAll('[style]'))if(/(?:display\s*:\s*none|visibility\s*:\s*hidden)/i.test(item.getAttribute('style')))item.remove();
 return copy;
}
export function readableText(node){
 const output=[];
 function visit(el){
  if(el.nodeType===3){output.push(el.nodeValue.replace(/[\t\r\n ]+/g,' '));return;}
  if(el.nodeType!==1)return;
  if(el.tagName==='BR'){output.push('\n');return;}
  const block=BLOCKS.has(el.tagName);
  if(block)output.push('\n');
  if(el.tagName==='PRE')output.push(el.textContent);else for(const child of el.childNodes)visit(child);
  if(el.tagName==='TD'||el.tagName==='TH')output.push('\t');
  if(block)output.push('\n');
 }
 visit(node);
 return output.join('').replace(/\u00a0/g,' ').replace(/ *\n */g,'\n').replace(/\n{3,}/g,'\n\n').trim();
}
function articleMetadata(doc){
 const found=[];
 function visit(value,depth=0){
  if(!value||typeof value!=='object'||depth>6)return;
  if(Array.isArray(value)){value.slice(0,100).forEach(v=>visit(v,depth+1));return;}
  const types=[value['@type']].flat();
  if(types.some(t=>typeof t==='string'&&/(?:^|[/#])(Article|NewsArticle|BlogPosting|TechArticle|Report|ScholarlyArticle)$/.test(t)))found.push(value);
  visit(value['@graph'],depth+1);visit(value.mainEntity,depth+1);
 }
 for(const script of [...doc.querySelectorAll('script[type="application/ld+json"]')].slice(0,30)){
  if(script.textContent.length>1000000)continue;
  try{visit(JSON.parse(script.textContent));}catch{/* Invalid structured data must not prevent DOM extraction. */}
 }
 return found;
}
function textCandidate(node){
 const copy=clean(node),text=readableText(copy);
 const links=[...copy.querySelectorAll('a')].reduce((sum,a)=>sum+a.textContent.trim().length,0);
 if(text.replace(/\s/g,'').length<40||links>text.length*.65)return null;
 return {text,hasMedia:!!copy.querySelector('img,picture,video,table,math'),node:copy};
}
function bestNode(doc,selectors){
 for(const selector of selectors){
  const nodes=[...doc.querySelectorAll(selector)].slice(0,20).map(textCandidate).filter(Boolean);
  if(nodes.length)return nodes.sort((a,b)=>b.text.length-a.text.length)[0];
 }
 return null;
}
export function extractDocument(doc,inputUrl){
 if(/^(?:Just a moment[.!…]*|Access Denied|Attention Required!?|403 Forbidden)$/i.test(doc.title.trim())||doc.querySelector('#challenge-form'))throw articleError('ARTICLE_ACCESS_DENIED','サイトのアクセス確認画面が返されました。ブラウザで閲覧できる文章・保存したHTML・画像から取り込んでください。');
 const source=normalizeArticleUrl(inputUrl)||null;
 const host=source?new URL(source).hostname:'';
 const site=SITE_RULES.find(rule=>rule.host(host));
 const meta=articleMetadata(doc);
 const samePage=value=>{try{const a=new URL(typeof value==='object'?value['@id']:value),b=new URL(source);return a.origin+a.pathname===b.origin+b.pathname;}catch{return false;}};
 const metadata=meta.find(m=>samePage(m.url)||samePage(m.mainEntityOfPage))||(meta.length===1?meta[0]:null);
 const restricted=meta.some(m=>m.isAccessibleForFree===false||m.isAccessibleForFree==='false');
 const warnings=[];
 if(restricted)warnings.push('会員・有料記事の可能性があります。取得できた部分だけを表示しています。');
 let title=doc.querySelector('meta[property="og:title"]')?.getAttribute('content')||doc.querySelector('h1')?.textContent||metadata?.headline||doc.title||host||'保存した記事';
 let candidate=site?bestNode(doc,site.selectors):null,method=candidate?'site:'+site.id:null;
 if(!candidate){
  candidate=bestNode(doc,['[itemprop="articleBody"]','.entry-content','.post-content','.article-body']);
  if(candidate)method='article-body';
 }
 if(!candidate){
  try{
   const article=new Readability(doc.cloneNode(true),{maxElemsToParse:100000}).parse();
   if(article?.content){
    const holder=doc.createElement('div');holder.innerHTML=article.content;
    candidate=textCandidate(holder);
    if(candidate){method='readability';title=article.title||title;}
   }
  }catch{/* A final metadata fallback can still work on an unusual layout. */}
 }
 // Never expose a paywalled body from embedded metadata when only a preview is visible.
 if(!candidate&&!restricted&&typeof metadata?.articleBody==='string'){
  const body=metadata.articleBody.trim();
  if(body.replace(/\s/g,'').length>=40){candidate={text:body,hasMedia:false};method='structured-data';}
 }
 if(!candidate)throw articleError(restricted?'ARTICLE_RESTRICTED':'ARTICLE_BODY_NOT_FOUND',restricted?'会員・有料記事の本文を取得できません。閲覧できる文章を貼り付けるか、画像から読み取ってください。':'本文を見つけられませんでした。記事そのもののURLか確認してください。保存したHTML・文章・画像からも読み取れます。');
 if(candidate.text.length>200000)throw articleError('ARTICLE_TOO_LONG','記事が長すぎます。必要な部分を文章として貼り付けてください。');
 if(candidate.hasMedia)warnings.push(source?'図・写真・表は速読の文章に含まれません。「元記事を開く」から確認できます。':'図・写真・表は速読の文章に含まれません。保存した元のページも確認してください。');
 if(candidate.text.replace(/\s/g,'').length<200)warnings.push('取得した本文が短いため、記事の一部だけの可能性があります。元記事と確認してください。');
 return {title:String(title).replace(/\s+/g,' ').trim().slice(0,300),text:candidate.text,source,site:site?.name||host||'保存したページ',method,warnings};
}
