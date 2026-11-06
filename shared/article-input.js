export function normalizeArticleUrl(input,{embedded=false}={}){
 if(typeof input!=='string')return null;
 let value=input.trim();
 const markdown=value.match(/^\[[^\]]*\]\((https?:\/\/[^\s]+)\)$/i);
 if(markdown)value=markdown[1];
 else if(/^<https?:\/\/[^\s]+>$/i.test(value))value=value.slice(1,-1);
 else if(embedded){
  const links=value.match(/https?:\/\/[^\s<>「」『』]+/gi)||[];
  if(links.length!==1)return null;
  value=links[0].replace(/[。）、，]+$/u,'');
  // A URL in a shared Markdown link or parentheses should not include its wrapper.
  if(value.endsWith(')')&&!value.includes('('))value=value.slice(0,-1);
 }
 if(!/^https?:\/\/\S+$/i.test(value)||value.length>4096)return null;
 try{const url=new URL(value);if(url.username||url.password)return null;return url.href;}catch{return null;}
}
export function sharedInput(params){
 const url=normalizeArticleUrl(params.get('url'));
 if(url)return {kind:'url',value:url};
 const text=params.get('text')||'';
 const link=normalizeArticleUrl(text,{embedded:true});
 // Long shared passages remain text, even if they cite a URL.
 const surrounding=text.replace(/https?:\/\/[^\s<>「」『』]+/gi,'').trim();
 if(link&&text.length<600&&surrounding.length<100)return {kind:'url',value:link};
 if(text.trim())return {kind:'text',value:text.slice(0,200000)};
 return null;
}
