// Deployment failures often return HTML/text rather than an API error document.
export async function readArticleResponse(response){
 const type=response.headers.get('content-type')||'';
 if(!type.includes('application/json')){
  const status=response.status;
  if(status===400||status===413)throw new Error(`送信するURLを確認してください（HTTP ${status}）。記事のURLだけを貼り付けて再試行してください。`);
  if(status===404)throw new Error('公開先に記事取り込み機能が見つかりません（HTTP 404）。ページを更新しても続く場合は公開設定の修正が必要です。');
  if(status===401||status===403)throw new Error(`記事取り込み機能へのアクセスが制限されています（HTTP ${status}）。公開先のアクセス設定を確認する必要があります。`);
  if(status===504)throw new Error('記事取り込み機能の応答が時間切れになりました（HTTP 504）。少し待って再試行するか、文章や画像から取り込んでください。');
  if(status>=500)throw new Error(`記事取り込み機能の起動・処理に失敗しました（HTTP ${status}）。文章や画像からの読み取りは使えます。`);
  throw new Error(`記事の代わりに別のページが返ってきました（HTTP ${status}）。公開先の記事取り込み設定を確認する必要があります。`);
 }
 let data;
 try{data=await response.json();}catch{throw new Error('記事取り込み機能から正しい応答がありませんでした。ページを更新して再試行してください。');}
 if(!response.ok){
  const error=new Error(typeof data?.error==='string'?data.error:`記事を取得できませんでした（HTTP ${response.status}）。`);
  const details={httpStatus:response.status,apiVersion:response.headers.get('x-ten-article-version')||'unknown'};
  const d=data?.diagnostic;
  if(d&&typeof d==='object')for(const key of ['phase','code','name','runtime','apiVersion'])if(typeof d[key]==='string'&&/^[a-zA-Z0-9_.-]{1,64}$/.test(d[key]))details[key]=d[key];
  error.diagnostic=details;throw error;
 }
 if(typeof data?.text!=='string'||!data.text.trim())throw new Error('記事本文が返ってきませんでした。文章の貼り付けや画像から取り込んでください。');
 return {text:data.text,title:typeof data.title==='string'?data.title:'取り込んだ記事',...(typeof data.source==='string'?{source:data.source}:{}),...(Array.isArray(data.warnings)?{warnings:data.warnings.filter(s=>typeof s==='string').slice(0,5)}:{})};
}
