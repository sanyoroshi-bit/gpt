import {takeSharedInput} from '../shared/share-store.js';
import {sharedInput} from '../shared/article-input.js';
export async function receiveShare(){
 const url=new URL(location.href),id=url.searchParams.get('share');
 if(!id)return null;
 url.searchParams.delete('share');history.replaceState(null,'',url.pathname+url.search+url.hash);
 const record=await takeSharedInput(id);
 if(!record)throw new Error('共有内容が見つからないか、受け取り期限を過ぎました。もう一度共有してください。');
 const input=sharedInput(new URLSearchParams(record));
 if(!input)throw new Error('共有された文章やURLを確認できませんでした。文章・リンクを選んで、もう一度共有してください。');
 return input;
}
export function enableMobileShare(){
 const status=document.getElementById('install-status'),button=document.getElementById('install-app');let prompt;
 window.addEventListener('beforeinstallprompt',event=>{event.preventDefault();prompt=event;button.hidden=false;});
 button.onclick=async()=>{if(!prompt)return;try{await prompt.prompt();await prompt.userChoice;}finally{prompt=null;button.hidden=true;}};
 window.addEventListener('appinstalled',()=>{button.hidden=true;status.textContent='ホーム画面に追加しました。Androidの共有メニューにtenが出る場合は、リンクや選択した文章を送れます。';});
 if('serviceWorker' in navigator)navigator.serviceWorker.register('/share-worker.bundle.js',{type:'module'}).catch(()=>{status.textContent='共有先としての登録を準備できませんでした。リンクや文章の貼り付けは使えます。';});
}
