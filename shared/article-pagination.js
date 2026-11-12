// A numbered URL, stable canonical article identity and unchanged headline are all required.
const PAGE_KEYS=['page','p','pageno','pageNo'];
export function numberedPage(value){
 try{
  const url=new URL(value);url.hash='';let number=1,style=null;
  for(const key of PAGE_KEYS)if(url.searchParams.has(key)){
   if(style||url.searchParams.getAll(key).length!==1||!/^\d{1,2}$/.test(url.searchParams.get(key)))return null;
   number=Number(url.searchParams.get(key));url.searchParams.delete(key);style='query:'+key;
  }
  if(!style){const match=url.pathname.match(/^(.*?)(?:[_-](\d{1,2}))\.(html?|shtml)$/i);if(match){number=Number(match[2]);url.pathname=match[1]+'.'+match[3];style='suffix';}}
  if(number<1||number>20)return null;
  url.searchParams.sort();return {identity:url.href,number,style};
 }catch{return null;}
}
export function consecutivePage(from,to){
 const a=numberedPage(from),b=numberedPage(to);
 return !!(a&&b&&a.identity===b.identity&&b.number===a.number+1&&(!a.style||a.style===b.style));
}
const heading=value=>String(value||'').normalize('NFC').replace(/\s+/g,'').replace(/[（(\[]\d+ページ(?:目)?[）)\]]$/,'');
function stableArticleIdentity(identity){
 const url=new URL(identity),leaf=url.pathname.split('/').filter(Boolean).at(-1)||'';
 if(['id','article_id','story_id'].some(key=>/^[\w-]{4,}$/.test(url.searchParams.get(key)||'')))return true;
 if(/^(?:index|article|story|read|view|news|default)(?:\.[a-z]+)?$/i.test(leaf))return false;
 let decoded;try{decoded=decodeURIComponent(leaf);}catch{return false;}
 return /\d{5,}|[a-z]{3,}[-_][a-z0-9-]{3,}|^news\d{3,}/i.test(leaf)||/[\u3040-\u30ff\u3400-\u9fff]{4,}/u.test(decoded);
}
export function hasNumberedContinuation(doc,source){
 return [...doc.querySelectorAll('link[rel~="next"],a[rel~="next"],nav[aria-label*="ページ"] a,.pagination a,[class~="pager"] a')].some(node=>{
  if(/次の記事|関連記事|おすすめ/.test(node.textContent||''))return false;
  try{const to=new URL(node.getAttribute('href'),source).href;return consecutivePage(source,to)||intendedNext(node)&&sameNumberedArticle(source,to);}catch{return false;}
 });
}
function intendedNext(node){return /(?:^|\s)next(?:\s|$)/i.test(node.getAttribute('rel')||'')||/^(?:次(?:の)?(?:ページ|頁)?(?:へ)?|Next[ >»]*)$/i.test((node.textContent||'').trim());}
function sameNumberedArticle(from,to){const a=numberedPage(from),b=numberedPage(to);return !!(a&&b&&a.identity===b.identity);}
export function paginationInfo(doc,source){
 const current=numberedPage(source);if(!current)return null;
 if(!stableArticleIdentity(current.identity))return null;
 const rawCanonical=doc.querySelector('link[rel~="canonical"]')?.getAttribute('href');
 let canonical,canonicalUrl;try{canonicalUrl=new URL(rawCanonical,source);canonicalUrl.hash='';canonicalUrl.searchParams.sort();canonical=numberedPage(canonicalUrl.href);}catch{return null;}
 if(!rawCanonical||!canonical||canonical.identity!==current.identity)return null;
 const title=heading(doc.querySelector('meta[property="og:title"]')?.getAttribute('content')||doc.querySelector('h1')?.textContent||doc.title);
 if(title.length<4)return null;
 const candidates=[...doc.querySelectorAll('link[rel~="next"],a[rel~="next"],nav[aria-label*="ページ"] a,.pagination a,[class~="pager"] a')];
 let next=null,unverified=false;
 for(const node of candidates){
  const label=node.textContent?.trim()||'';if(/次の記事|関連記事|おすすめ/.test(label))continue;
  let url;try{url=new URL(node.getAttribute('href'),source);if(url.origin!==new URL(source).origin)continue;}catch{continue;}
  if(consecutivePage(source,url.href)){if(next&&next!==url.href)return null;next=url.href;}
  else if(intendedNext(node)&&sameNumberedArticle(source,url.href))unverified=true;
 }
 return {identity:current.identity,canonical:canonicalUrl.href,number:current.number,title,next:unverified?null:next,unverified};
}
export function sameArticlePage(previous,next){return !!(previous&&next&&previous.identity===next.identity&&previous.canonical===next.canonical&&previous.title===next.title&&next.number===previous.number+1);}
