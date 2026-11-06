import {tokenize,characters,duration} from './reader.js';
import {readArticleResponse} from './article-client.js';
import {importHtml} from './html-import.js';
import {normalizeArticleUrl} from '../shared/article-input.js';
import {receiveShare,enableMobileShare} from './mobile-share.js';
import {recognizeImage,inspectImage} from './ocr.js';
const $=id=>document.getElementById(id), reduced=matchMedia('(prefers-reduced-motion:reduce)');
const sample='読むことは、新しい景色に出会うこと。\nいつもの通勤時間も、コーヒーを待つ数分も、小さな読書の時間になります。\nこのリーダーでは、文章がひとつの場所で切り替わります。視線をあちこち動かさず、目の前の言葉に集中してみてください。\nはじめはゆっくり。慣れてきたら、少しずつ速度を上げて。大切なのは、あなたに合ったリズムを見つけることです。';
const fonts={system:'system-ui, sans-serif',sans:'"Noto Sans JP", sans-serif',serif:'"Noto Serif JP", serif',rounded:'"Zen Maru Gothic", sans-serif',ud:'"BIZ UDGothic", sans-serif',kaisei:'"Kaisei Decol", serif'};
let text=sample,tokens=[],index=0,playing=false,complete=false,timer,tab='text',busy=false,ocrController,preview,suffix=[],selectedFile,readerImage,recognizedText='',resultFile;
const mode=()=>document.querySelector('[name="reading-mode"]:checked').value;
try{const s=JSON.parse(localStorage.getItem('ten-settings')||'{}');if(Number.isFinite(+s.speed)&&s.speed>=200)$('speed').value=Math.min(6000,+s.speed);if(fonts[s.font])$('font-family').value=s.font;if(s.fontSize>=20&&s.fontSize<=64)$('font-size').value=s.fontSize;if(['4','6','10'].includes(s.chunk))$('chunk').value=s.chunk;if(['phrase','word','character'].includes(s.mode))document.querySelector(`[name="reading-mode"][value="${s.mode}"]`).checked=true;if(typeof s.pauses==='boolean')$('pauses').checked=s.pauses;}catch{}
function save(){try{localStorage.setItem('ten-settings',JSON.stringify({speed:+$('speed').value,font:$('font-family').value,fontSize:+$('font-size').value,chunk:$('chunk').value,mode:mode(),pauses:$('pauses').checked}));}catch{}}
function message(value,error=false){$('message').textContent=value;$('message').classList.toggle('error',error);}
function timing(){suffix=new Array(tokens.length+1).fill(0);for(let i=tokens.length-1;i>=0;i--)suffix[i]=suffix[i+1]+duration(tokens[i],+$('speed').value,$('pauses').checked);}
function fit(){const word=$('word'),size=+$('font-size').value;word.style.fontSize=size+'px';word.style.fontFamily=fonts[$('font-family').value];const available=word.clientWidth;if(!available)return;const measure=el=>{const r=document.createRange();r.selectNodeContents(el);return r.getBoundingClientRect().width;};const required=2*Math.max(measure($('before')),measure($('after')))+measure($('anchor'))+12;if(required>available)word.style.fontSize=Math.max(8,Math.floor(size*available/required))+'px';}
function render(){const c=characters(tokens[index]||''),pivot=Math.max(0,Math.ceil(c.length/2)-1);$('before').textContent=c.slice(0,pivot).join('');$('anchor').textContent=c[pivot]||'';$('after').textContent=c.slice(pivot+1).join('');fit();$('position').textContent=`${tokens.length?index+1:0} / ${tokens.length}`;$('progress').max=Math.max(0,tokens.length-1);$('progress').value=index;$('percent').textContent=(complete?100:tokens.length>1?Math.round(index/(tokens.length-1)*100):0)+'%';$('remaining').textContent=`残り 約${complete?0:Math.ceil((suffix[index]||0)/1000)}秒`;$('state').textContent=complete?'読み終わり':playing?'読書中':'準備できました';$('play').textContent=playing?'Ⅱ 一時停止':complete?'↺ もう一度読む':'▶ 読みはじめる';$('finished').hidden=!complete;document.querySelector('.reader-panel').classList.toggle('is-playing',playing);}
function stop(){clearTimeout(timer);playing=false;render();}
function schedule(){clearTimeout(timer);timer=setTimeout(()=>{if(index>=tokens.length-1){complete=true;stop();return;}index++;render();schedule();},duration(tokens[index],+$('speed').value,$('pauses').checked));}
function toggle(){if(busy||!tokens.length)return;if(playing)return stop();if(complete){complete=false;index=0;}playing=true;render();schedule();}
function seek(value){stop();index=Math.max(0,Math.min(tokens.length-1,value));complete=false;render();}
function prepare(value,title,source=null){stop();$('article-source').hidden=true;$('article-source').removeAttribute('href');$('article-notice').hidden=true;$('article-notice').textContent='';if(readerImage)URL.revokeObjectURL(readerImage);$('source-image').removeAttribute('src');readerImage=source?URL.createObjectURL(source):null;$('show-source').hidden=!readerImage;text=value.trim();index=0;complete=false;tokens=tokenize(text,+$('chunk').value,mode());timing();$('article-title').textContent=title;$('original-text').textContent=text;render();}
function modeUI(){$('chunk').disabled=mode()!=='phrase';$('mode-hint').textContent=mode()==='phrase'?'言葉のまとまりを優先して区切ります。長さは目安です。':mode()==='word'?'単語ごとに表示します。句読点は前の単語につながります。':'一文字ずつ表示します。空白はスキップし、絵文字は分割しません。';}
function regroup(){const len=s=>s.replace(/\s/g,'').length,offset=len(tokens.slice(0,index).join('')),resume=playing,finished=complete;stop();tokens=tokenize(text,+$('chunk').value,mode());index=0;let count=0;while(index<tokens.length-1&&count+len(tokens[index])<=offset)count+=len(tokens[index++]);complete=finished;if(finished)index=Math.max(0,tokens.length-1);playing=resume&&!finished;timing();modeUI();save();render();if(playing)schedule();}
function appearance(){$('font-size-value').textContent=$('font-size').value+' px';fit();save();document.fonts.ready.then(fit);}
function speed(){$('speed-value').textContent=(+$('speed').value).toLocaleString('en-US');for(const button of document.querySelectorAll('[data-speed]'))button.setAttribute('aria-pressed',button.dataset.speed===$('speed').value);timing();save();render();if(playing)schedule();}
function changeTab(value){tab=value;$('article-fallback').hidden=true;for(const key of ['text','image','url']){$(key+'-tab').setAttribute('aria-selected',key===value);$(key+'-tab').tabIndex=key===value?0:-1;$(key+'-panel').hidden=key!==value;}$('load').textContent=value==='image'?'確認した文章で読みはじめる →':'リーダーに送る →';message(value==='url'?'URLは記事取得のためサーバーに送信されます。':'文章と画像はこの端末内で処理されます。');}
function getUrl(raw){return normalizeArticleUrl(raw);}
function applyArticle(data){prepare(data.text,data.title);$('text').value=data.text;$('text').oninput();const source=normalizeArticleUrl(data.source);if(source){$('article-source').href=source;$('article-source').hidden=false;}if(data.warnings?.length){$('article-notice').textContent=data.warnings.join(' ');$('article-notice').hidden=false;}}
function setBusy(value){busy=value;$('load').disabled=value;$('play').disabled=value;$('image-file').disabled=value;$('html-file').disabled=value;$('sample').disabled=value;$('start-ocr').disabled=value;$('crop-controls').disabled=value;$('ocr-text').disabled=value;$('restore-ocr').disabled=value;}
$('load').onclick=async()=>{if(busy)return;if(tab==='text'){const url=getUrl($('text').value);if(url){$('url').value=url;changeTab('url');}}
 if(tab==='text'||tab==='image'){const value=$(tab==='image'?'ocr-text':'text').value;if(!value.trim())return message(tab==='image'?'スクリーンショットを選んでください。':'読みたい文章を貼り付けてください。',true);prepare(value,tab==='image'?'スクリーンショットから':'貼り付けた文章',tab==='image'?resultFile:null);if(tab==='image'){toggle();if(innerWidth<780)document.querySelector('.reader-panel').scrollIntoView({behavior:reduced.matches?'instant':'smooth'});}else message('準備ができました。「読みはじめる」でスタート。');return;}
 const url=normalizeArticleUrl($('url').value,{embedded:true});if(!url)return message('https:// から始まる記事URLを入力してください。',true);stop();setBusy(true);$('article-fallback').hidden=true;message('記事の本文を取り込んでいます…');
 try{const response=await fetch('/api/article',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url}),signal:AbortSignal.timeout(20000)});const data=await readArticleResponse(response);applyArticle(data);message('本文を取り込みました。元の記事も確認してから読みはじめてください。');}catch(e){message(e.name==='TimeoutError'?'記事の取得に時間がかかっています。少し待って再試行するか、文章や画像から取り込んでください。':/[ぁ-ん一-龯]/.test(e.message)?e.message:'記事取り込み機能に通信できませんでした。通信状況を確認するか、文章や画像から取り込んでください。',true);$('article-fallback').hidden=false;}finally{setBusy(false);}
};
const cropKeys=['top','bottom','left','right'];
function region(){return Object.fromEntries(cropKeys.map(k=>[k,+$('crop-'+k).value]));}
function drawCrop(){const r=region();for(const k of cropKeys)$('crop-'+k+'-value').textContent=r[k]+'%';Object.assign($('crop-box').style,{left:r.left+'%',top:r.top+'%',width:(r.right-r.left)+'%',height:(r.bottom-r.top)+'%'});}
for(const k of cropKeys)$('crop-'+k).oninput=()=>{const opposite={top:'bottom',bottom:'top',left:'right',right:'left'}[k],start=k==='top'||k==='left';if(start&&+$('crop-'+k).value>=+$('crop-'+opposite).value)$('crop-'+k).value=+$('crop-'+opposite).value-1;if(!start&&+$('crop-'+k).value<=+$('crop-'+opposite).value)$('crop-'+k).value=+$('crop-'+opposite).value+1;drawCrop();};
$('crop-reset').onclick=()=>{for(const k of cropKeys)$('crop-'+k).value=k==='top'||k==='left'?0:100;drawCrop();};
$('image-file').onchange=async()=>{
 const file=$('image-file').files[0];$('image-file').value='';if(!file||busy)return;
 stop();setBusy(true);
 try{
  await inspectImage(file);
  selectedFile=file;resultFile=null;recognizedText='';$('ocr-text').value='';$('restore-ocr').hidden=true;
  if(preview)URL.revokeObjectURL(preview);preview=URL.createObjectURL(file);
  $('image-preview').src=preview;$('image-editor').hidden=false;
  for(const k of cropKeys)$('crop-'+k).value=k==='top'||k==='left'?0:100;drawCrop();
  $('ocr-status').textContent='本文の範囲を確認して、読み取りを開始してください。';$('ocr-timing').textContent='';
  message('枠の外も元画像に残ります。図や写真は読書中に確認できます。');
 }catch(e){message(/[ぁ-ん一-龯]/.test(e.message)?e.message:'画像を開けませんでした。別の画像で試してください。',true);}
 finally{setBusy(false);}
};
const phaseLabels={'loading tesseract core':'読み取り機能を読み込んでいます','initializing tesseract':'読み取り機能を起動しています','loading language traineddata':'日本語・英語のデータを準備しています','initializing api':'読み取りの準備を仕上げています','recognizing text':'文字を読み取っています'};
$('start-ocr').onclick=async()=>{
 if(!selectedFile||busy)return;
 stop();ocrController=new AbortController();setBusy(true);$('cancel-ocr').hidden=false;
 const started=performance.now();let phase='画像を準備しています',percent='';
 const showStatus=()=>$('ocr-status').textContent=`${phase}${percent} · ${Math.floor((performance.now()-started)/1000)}秒`;
 const clock=setInterval(showStatus,1000);showStatus();$('ocr-timing').textContent='';
 try{
  const data=await recognizeImage(selectedFile,e=>{phase=phaseLabels[e.status]||e.status;percent=Number.isFinite(e.progress)?` ${Math.round(e.progress*100)}%`:'';showStatus();},ocrController.signal,region());
  if(!data.text)throw new Error('文字を読み取れませんでした。範囲を広げるか、鮮明な画像で試してください。');
  recognizedText=data.text;resultFile=selectedFile;$('ocr-text').value=data.text;$('restore-ocr').hidden=false;
  prepare(data.text,'スクリーンショットから',resultFile);
  const t=data.timings,seconds=n=>(n/1000).toFixed(1);
  $('ocr-timing').textContent=`画像の準備 ${seconds(t.imageMs)}秒 / 読み取り準備 ${seconds(t.setupMs)}秒 / 文字認識 ${seconds(t.recognitionMs)}秒 / 合計 ${seconds(t.totalMs)}秒${t.reused?'（準備を再利用）':''}`;
  $('ocr-status').textContent='読み取り完了。内容を確認してから読みはじめてください。';
  message(data.confidence<50?'読み取りが不確かな箇所があります。元画像と照らし合わせて修正してください。':'不要な文や誤字を確認してください。図・写真・表は元画像で確認できます。');
 }catch(e){$('ocr-status').textContent=e.name==='AbortError'?'読み取りを中止しました。': '読み取りに失敗しました。';message(e.name==='AbortError'?'範囲を選び直して、もう一度読み取れます。':e.name==='TimeoutError'?'読み取りが長引いたため停止しました。通信状況を確認するか、範囲を小さくして再試行してください。':/[ぁ-ん一-龯]/.test(e.message)?e.message:'画像を読み取れませんでした。別の画像で試してください。',true);}
 finally{clearInterval(clock);setBusy(false);$('cancel-ocr').hidden=true;}
};
$('restore-ocr').onclick=()=>{$('ocr-text').value=recognizedText;message('修正前の読み取り結果に戻しました。');};
$('show-source').onclick=()=>{if(!readerImage)return;stop();$('source-image').src=readerImage;$('source-zoom').value=100;$('source-image').style.width='100%';$('source-dialog').showModal();};
$('close-source').onclick=()=>$('source-dialog').close();
$('source-zoom').oninput=()=>$('source-image').style.width=$('source-zoom').value+'%';
$('cancel-ocr').onclick=()=>ocrController?.abort();$('play').onclick=toggle;$('restart').onclick=()=>seek(0);$('back').onclick=()=>seek(index-5);$('next').onclick=()=>seek(index+5);$('progress').oninput=()=>seek(+$('progress').value);$('speed').oninput=speed;$('font-family').onchange=appearance;$('font-size').oninput=appearance;$('chunk').onchange=regroup;$('pauses').onchange=()=>{timing();save();render();if(playing)schedule();};for(const radio of document.querySelectorAll('[name="reading-mode"]'))radio.onchange=regroup;for(const button of document.querySelectorAll('[data-speed]'))button.onclick=()=>{$('speed').value=button.dataset.speed;speed();};
for(const key of ['text','image','url']){$(key+'-tab').onclick=()=>changeTab(key);$(key+'-tab').onkeydown=e=>{if(!['ArrowRight','ArrowLeft'].includes(e.key))return;e.preventDefault();const tabs=['text','image','url'],next=tabs[(tabs.indexOf(key)+(e.key==='ArrowRight'?1:2))%3];changeTab(next);$(next+'-tab').focus();};}
$('text').oninput=()=>$('char-count').textContent=characters($('text').value).length.toLocaleString()+' 文字';$('clear').onclick=()=>{$('text').value='';$('text').oninput();};$('sample').onclick=()=>{changeTab('text');$('text').value=sample;$('text').oninput();prepare(sample,'はじめての ten.');};$('focus').onclick=()=>{document.body.classList.toggle('focused');$('focus').textContent=document.body.classList.contains('focused')?'集中モードを閉じる ↙':'集中モード ↗';};
document.addEventListener('keydown',e=>{if($('source-dialog').open||e.target.closest('button,input,textarea,select,a,summary')||e.ctrlKey||e.metaKey||e.altKey)return;if(e.code==='Space'){e.preventDefault();toggle();}if(e.code==='ArrowLeft'){e.preventDefault();seek(index-5);}if(e.code==='ArrowRight'){e.preventDefault();seek(index+5);}});document.addEventListener('visibilitychange',()=>{if(document.hidden&&playing)stop();});
document.addEventListener('click',e=>{const target=e.target.closest('button,input[type=radio],input[type=checkbox]');if(!target||target.disabled||reduced.matches)return;const rect=(target.closest('label')||target).getBoundingClientRect(),fx=document.createElement('span');fx.className='tap-feedback';Object.assign(fx.style,{left:rect.left+'px',top:rect.top+'px',width:rect.width+'px',height:rect.height+'px',borderRadius:getComputedStyle(target.closest('label')||target).borderRadius});document.body.append(fx);setTimeout(()=>fx.remove(),550);});
new ResizeObserver(fit).observe($('stage'));document.fonts.addEventListener('loadingdone',fit);modeUI();prepare(sample,'はじめての ten.');appearance();speed();if(new URL(location.href).searchParams.get('start')==='image')changeTab('image');

$('fallback-text').onclick=()=>{changeTab('text');$('text').focus();};
$('fallback-image').onclick=()=>{changeTab('image');$('image-file').focus();};

$('html-file').onchange=async()=>{const file=$('html-file').files[0];$('html-file').value='';if(!file||busy)return;stop();setBusy(true);message('保存したページから本文を取り込んでいます…');try{const data=await importHtml(file);applyArticle(data);$('article-fallback').hidden=true;message('保存したページから本文を取り込みました。「読みはじめる」でスタート。');}catch(e){message(e.message,true);}finally{setBusy(false);}};
$('article-source').onclick=()=>stop();

enableMobileShare();
receiveShare().then(shared=>{if(!shared)return;if(shared.kind==='url'){changeTab('url');$('url').value=shared.value;$('load').click();}else{changeTab('text');$('text').value=shared.value;$('text').oninput();prepare(shared.value,'共有した文章');message('共有した文章を受け取りました。「読みはじめる」でスタート。');}}).catch(e=>message(e.message,true));
