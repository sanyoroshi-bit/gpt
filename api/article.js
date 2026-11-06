// File-based Vercel Function. GET checks initialization only, not article-site access.
export const API_VERSION='article-v4';
const respond=(res,status,body)=>res.status(status).json(body);
function diagnostic(error){
 const safe=value=>typeof value==='string'&&/^[a-zA-Z0-9_.-]{1,64}$/.test(value)?value:'UNKNOWN';
 return {phase:'initialization',code:safe(error?.code),name:safe(error?.name),runtime:process.version,apiVersion:API_VERSION};
}
export function createArticleHandler(loadService=()=>import('../lib/article.js'),logFailure=error=>console.error('ten article initialization failed',error)){
 return async function articleHandler(req,res){
  res.setHeader('Cache-Control','no-store');res.setHeader('X-Ten-Article-Version',API_VERSION);
  if(!['GET','POST'].includes(req.method)){
   res.setHeader('Allow','GET, POST');return respond(res,405,{code:'METHOD_NOT_ALLOWED',error:'記事URLは取り込みボタンから送信してください。'});
  }
  let body=req.body;
  if(req.method==='POST')try{
   if(typeof body==='string'){if(Buffer.byteLength(body)>8192)throw new Error('body too large');body=JSON.parse(body);}
   if(!body||typeof body.url!=='string'||!body.url.trim()||body.url.length>4096)throw new Error('invalid URL input');
  }catch{return respond(res,400,{code:'INVALID_REQUEST',error:'記事のURLだけを入力してください。'});}
  let fetchArticle;
  try{
   ({fetchArticle}=await loadService());
   if(typeof fetchArticle!=='function')throw Object.assign(new Error('Missing article service export'),{code:'INVALID_SERVICE_EXPORT'});
  }catch(error){
   logFailure(error);
   return respond(res,503,{code:'ARTICLE_SERVICE_UNAVAILABLE',error:'記事取り込み機能を起動できませんでした。公開設定の確認が必要です。文章の貼り付けや画像からの読み取りは使えます。',diagnostic:diagnostic(error)});
  }
  if(req.method==='GET')return respond(res,200,{service:'ten-article',version:API_VERSION,status:'dependencies-ready',runtime:process.version,scope:'initialization-only'});
  try{return respond(res,200,await fetchArticle(body.url));}
  catch(error){
   const data=error.result||{code:'ARTICLE_FETCH_FAILED',error:'記事を取得できませんでした。文章の貼り付けや画像から取り込んでください。'};
   return respond(res,data.code==='NETWORK_POLICY_BLOCKED'?502:data.code==='ARTICLE_TIMEOUT'?504:422,data);
  }
 };
}
export default createArticleHandler();
