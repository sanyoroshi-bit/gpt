import {Parser,jaModel} from 'budoux';
const parser=new Parser(jaModel), words=new Intl.Segmenter('ja',{granularity:'word'}), chars=new Intl.Segmenter('ja',{granularity:'grapheme'});
export const characters=text=>[...chars.segment(text)].map(s=>s.segment);
const MAX_DISPLAY_CHARACTERS=12;
function limitDisplayUnits(units){
 const result=[];
 for(const unit of units){
  const graphemes=characters(unit);
  for(let start=0;start<graphemes.length;start+=MAX_DISPLAY_CHARACTERS){
   const piece=graphemes.slice(start,start+MAX_DISPLAY_CHARACTERS).join('');
   if(piece.trim())result.push(piece);
  }
 }
 return result;
}
const closer=/^[\p{Pe}\p{Pf}、。，．！？!?…,:;]+$/u;
const end=/[。！？!?.][」』）】”’]*\s*$/u;
export function tokenize(text,size=6,mode='phrase') {
 const input=text.normalize('NFC');
 if(mode==='character')return characters(input).filter(s=>!/^\s+$/u.test(s));
 const result=[];
 for(const line of input.split(/\r\n|[\n\r\u2028\u2029]/u)) {
  let units=[];
  if(mode==='word') {
   let opening='';
   for(const {segment:s} of words.segment(line)) {
    if(/^\s+$/.test(s))continue;
    if(/^[\p{Ps}\p{Pi}「『]+$/u.test(s)){opening+=s;continue;}
    if(!opening&&units.length&&closer.test(s))units[units.length-1]+=s;
    else {units.push(opening+s);opening='';}
   }
   if(opening)units.push(opening);
   result.push(...units);continue;
  }
  if(!/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}]/u.test(line))units=line.match(/\S+\s*/gu)||[];
  else {
   const safe=new Set([...words.segment(line)].map(s=>s.index));safe.add(line.length);
   const blocked=new Set();
   for(const m of line.matchAll(/について|に対して|に関して|によって|にとって|として|という|ために|ように/gu))for(let i=m.index+1;i<m.index+m[0].length;i++)blocked.add(i);
   const bounds=new Set([line.length]);let offset=0;
   for(const p of parser.parse(line)){offset+=p.length;if(safe.has(offset)&&!blocked.has(offset))bounds.add(offset);}
   for(const m of line.matchAll(/[。！？!?]+[」』）】”’]*|\s+/gu))bounds.add(m.index+m[0].length);
   let start=0;
   for(const b of [...bounds].sort((a,b)=>a-b)) {
    const pieces=line.slice(start,b).match(/\S+\s*/gu)||[];start=b;
    for(const p of pieces){
     if(units.length&&(closer.test(p.trim())||!end.test(units.at(-1))&&/^(?:こと|もの|ため|よう|です|ます|ません|でした)(?:[はがをにともので]|[。、！？!?」』）]|$)/u.test(p.trimStart())))units[units.length-1]+=p;
     else units.push(p);
    }
   }
  }
  let chunk='';const length=s=>characters(s.replace(/\s/g,'')).length;
  const flush=()=>{if(chunk.trim())result.push(chunk.trim());chunk='';};
  for(const unit of units){if(chunk&&!/[「『（【(]$/.test(chunk)&&!closer.test(unit.trim())&&length(chunk+unit)>size)flush();chunk+=unit;if(end.test(chunk))flush();}flush();
 }
 return limitDisplayUnits(result);
}
export function duration(text,speed,pauses=true){const base=Math.max(16,characters(text.replace(/\s/g,'')).length*60000/speed);return base*(pauses&&/[。！？!?][」』）】”’]*$/.test(text)?1.8:pauses&&/[、，,][」』）】”’]*$/.test(text)?1.3:1);}
