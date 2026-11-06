import {extractDocument,articleError} from '../shared/article-extractor.js';
import {normalizeArticleUrl} from '../shared/article-input.js';
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
export async function fetchArticle(value){let host;const signal=AbortSignal.timeout(15000);try{let url=await validateUrl(value,{signal});host=url.hostname;let response;for(let n=0;n<=4;n++){response=await fetch(url,{dispatcher,redirect:'manual',signal,headers:{Accept:'text/html','User-Agent':'TenReader/0.3 (article reader)','Accept-Language':'ja,en;q=0.8'}});if(![301,302,303,307,308].includes(response.status))break;await response.body?.cancel();if(n===4)throw new Error('記事の転送が多すぎます。本文を貼り付けてください。');const location=response.headers.get('location');if(!location)throw new Error('記事の転送先を確認できません。');url=await validateUrl(new URL(location,url),{signal});host=url.hostname;}
 if(!response.ok){await response.body?.cancel();throw articleError(response.status===404?'ARTICLE_NOT_FOUND':response.status===429?'ARTICLE_RATE_LIMITED':'ARTICLE_ACCESS_DENIED',response.status===404?'記事が見つかりません。移動・削除されていないか元のページを確認してください。':response.status===429?'記事サイトのアクセス制限中です。しばらく待つか、文章・保存したHTML・画像から取り込んでください。':'記事サイトがサーバーからの取得を受け付けませんでした。ブラウザで読める文章・保存したHTML・画像からも取り込めます。');}
 const type=response.headers.get('content-type')||'';if(!/^(?:text\/html|application\/xhtml\+xml)(?:\s*;|$)/i.test(type)){await response.body?.cancel();throw new Error('HTML記事に対応しています。本文や画像を取り込んでください。');}
 let total=0;const buffers=[];for await(const bytes of response.body){total+=bytes.length;if(total>3000000)throw new Error('記事が大きすぎます。必要な本文を貼り付けてください。');buffers.push(Buffer.from(bytes));}const bytes=Buffer.concat(buffers),charset=type.match(/charset=["']?([^\s;"']+)/i)?.[1]||bytes.subarray(0,4096).toString('ascii').match(/charset\s*=\s*["']?([\w-]+)/i)?.[1]||'utf-8';let html;try{html=new TextDecoder(charset).decode(bytes);}catch{html=bytes.toString('utf8');}return extract(html,url.href);
 }catch(error){const failure=new Error('Article request failed',{cause:error});failure.result=articleFailure(error,host);throw failure;}}
