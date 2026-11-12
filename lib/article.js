import {extractDocument,articleError} from '../shared/article-extractor.js';
import {normalizeArticleUrl} from '../shared/article-input.js';
import {consecutivePage,sameArticlePage} from '../shared/article-pagination.js';
import {JSDOM,VirtualConsole} from 'jsdom';
import {fetch,EnvHttpProxyAgent,Agent} from 'undici';
import {lookup} from 'node:dns/promises';
import ipaddr from 'ipaddr.js';
export function isPublic(address){return ipaddr.process(address).range()==='unicast';}
const proxy=process.env.HTTPS_PROXY||process.env.HTTP_PROXY;
function abortable(promise,signal){
 if(!signal)return promise;
 return new Promise((resolve,reject)=>{
  const abort=()=>{signal.removeEventListener('abort',abort);reject(signal.reason);};
  signal.addEventListener('abort',abort,{once:true});
  Promise.resolve(promise).then(resolve,reject).finally(()=>signal.removeEventListener('abort',abort));
  if(signal.aborted)abort();
 });
}

export async function validateUrl(value,{signal}={}){signal?.throwIfAborted();const normalized=normalizeArticleUrl(String(value));if(!normalized)throw articleError('INVALID_URL','公開記事のHTTP/HTTPS URLを入力してください。');const url=new URL(normalized);if(!['http:','https:'].includes(url.protocol)||url.username||url.password||url.port&&!['80','443'].includes(url.port))throw new Error('公開されている記事のHTTP/HTTPS URLを入力してください。');const host=url.hostname.replace(/^\[|\]$/g,'');if(!host.includes('.')&&!host.includes(':')||/(^|\.)(localhost|local|internal|test|invalid)$/.test(host)||ipaddr.isValid(host)&&!isPublic(host))throw new Error('ローカル・プライベートネットワークは取り込めません。');let addresses;try{addresses=await abortable(lookup(host,{all:true}),signal);}catch(e){if(signal?.aborted)throw signal.reason;if(proxy&&process.env.TEN_TRUST_PROXY_DNS==='1'&&['EAI_AGAIN','ENOTFOUND'].includes(e.code))return url;throw new Error('接続先を確認できませんでした。本文や画像から取り込んでください。');}if(!addresses.length||addresses.some(a=>!isPublic(a.address)))throw new Error('ローカル・プライベートネットワークは取り込めません。');return url;}
const dispatcher=proxy?new EnvHttpProxyAgent():new Agent({connect:{lookup(host,options,cb){lookup(host,{all:true}).then(addresses=>{if(!addresses.length||addresses.some(a=>!isPublic(a.address)))return cb(new Error('Private address blocked'));cb(null,options.all?addresses:addresses[0].address,addresses[0].family);}).catch(cb);}}});
export function extract(html,url){const dom=new JSDOM(html,{url,virtualConsole:new VirtualConsole()});try{return extractDocument(dom.window.document,url);}finally{dom.window.close();}}
export function articleFailure(error,host='記事サイト'){for(let cause=error,depth=0;cause&&depth<10;cause=cause.cause,depth++){if(/Proxy response \(403\).*HTTP Tunneling/i.test(cause.message||''))return {code:'NETWORK_POLICY_BLOCKED',error:`ネットワーク設定で${host}への接続が許可されていません。本文や画像からの取り込みも使えます。`};if(cause.name==='TimeoutError')return {code:'ARTICLE_TIMEOUT',error:'記事の取得がタイムアウトしました。本文や画像から取り込んでください。'};}return {code:typeof error.code==='string'&&error.code.startsWith('ARTICLE_')?error.code:'ARTICLE_FETCH_FAILED',error:/[ぁ-ん一-龯]/.test(error.message||'')?error.message:'記事を取得できませんでした。本文や画像から取り込んでください。'};}
export function createArticleFetcher({request=fetch,validate=validateUrl,budgetMs=15000}={}){
 return async function fetchArticle(value){
  const signal=AbortSignal.timeout(budgetMs),budget={bytes:0};let host;
  async function page(value,previous=null){
   let url=await validate(value,{signal}),response;host=url.hostname;
   for(let n=0;n<=4;n++){
    if(previous&&!consecutivePage(previous.source,url.href))throw articleError('ARTICLE_PAGINATION_MISMATCH','同じ記事の連続ページを確認できません。');
    signal.throwIfAborted();response=await request(url,{dispatcher,redirect:'manual',signal,headers:{Accept:'text/html','User-Agent':'TenReader/0.4 (article reader)','Accept-Language':'ja,en;q=0.8'}});
    if(![301,302,303,307,308].includes(response.status))break;
    await response.body?.cancel();if(n===4)throw articleError('ARTICLE_REDIRECT_LIMIT','記事の転送が多すぎます。');
    const location=response.headers.get('location');if(!location)throw articleError('ARTICLE_REDIRECT','転送先を確認できません。');
    url=await validate(new URL(location,url),{signal});host=url.hostname;
   }
   if(!response.ok){await response.body?.cancel();throw articleError(response.status===404?'ARTICLE_NOT_FOUND':response.status===429?'ARTICLE_RATE_LIMITED':'ARTICLE_ACCESS_DENIED','記事サイトから本文を取得できませんでした。ブラウザで読める文章・画像からも取り込めます。');}
   const type=response.headers.get('content-type')||'';
   if(!/^(?:text\/html|application\/xhtml\+xml)(?:\s*;|$)/i.test(type)){await response.body?.cancel();throw articleError('ARTICLE_CONTENT_TYPE','HTML記事に対応しています。');}
   let total=0;const buffers=[];
   for await(const bytes of response.body){signal.throwIfAborted();total+=bytes.length;budget.bytes+=bytes.length;if(total>3000000||budget.bytes>6000000)throw articleError('ARTICLE_TOO_LARGE','記事の取得量の上限に達しました。');buffers.push(Buffer.from(bytes));}
   const bytes=Buffer.concat(buffers),charset=type.match(/charset=["']?([^\s;"']+)/i)?.[1]||bytes.subarray(0,4096).toString('ascii').match(/charset\s*=\s*["']?([\w-]+)/i)?.[1]||'utf-8';
   let html;try{html=new TextDecoder(charset).decode(bytes);}catch{html=bytes.toString('utf8');}
   signal.throwIfAborted();const result=extract(html,url.href);signal.throwIfAborted();return result;
  }
  try{
   const first=await page(value),parts=[first.text],visited=new Set([first.source]),texts=new Set([first.text.replace(/\s/g,'')]);let current=first,partial=!!first.paginationUnverified;
   while(current.pagination?.next){
    if(parts.length>=5){partial=true;break;}
    const nextUrl=current.pagination.next;if(visited.has(nextUrl)){partial=true;break;}
    try{
     const next=await page(nextUrl,current);
     if(!consecutivePage(current.source,next.source)||!sameArticlePage(current.pagination,next.pagination)||visited.has(next.source)||texts.has(next.text.replace(/\s/g,''))||parts.join('\n\n').length+next.text.length+2>200000){partial=true;break;}
     parts.push(next.text);visited.add(nextUrl);visited.add(next.source);texts.add(next.text.replace(/\s/g,''));current=next;if(next.paginationUnverified)partial=true;
    }catch{partial=true;break;}
   }
   const warnings=[...new Set([...(first.warnings||[]),...(current.warnings||[])])];
   if(parts.length>1)warnings.unshift(`同じ記事の${parts.length}ページ分の本文を取り込みました。`);
   if(partial)warnings.unshift('次ページの同一記事確認または取得を完了できず、途中までの本文を表示しています。元記事で続きを確認してください。');
   return {...first,text:parts.join('\n\n'),warnings,pagesRead:parts.length,paginationStatus:partial?'partial':parts.length>1?'verified-pages':'single-page',pagination:undefined};
  }catch(error){const failure=new Error('Article request failed',{cause:error});failure.result=articleFailure(error,host);throw failure;}
 };
}
export const fetchArticle=createArticleFetcher();
