import fs from 'node:fs';
import {build} from 'esbuild';
const root=new URL('../',import.meta.url), asset=name=>new URL('public/'+name,root);
let css='';
for(const name of ['noto-sans-jp','noto-serif-jp','zen-maru-gothic','biz-udgothic','kaisei-decol']){
 const source=new URL(`node_modules/@fontsource/${name}/`,root),target=asset('fonts/'+name+'/');fs.mkdirSync(target,{recursive:true});
 let sheet=fs.readFileSync(new URL('400.css',source),'utf8').replace(/,\s*url\([^)]*\.woff\)\s*format\('woff'\)/g,'');
 sheet=sheet.replace(/url\(\.\/files\/([^)]*\.woff2)\)/g,(_,file)=>{fs.copyFileSync(new URL('files/'+file,source),new URL(file,target));return `url(/fonts/${name}/${file})`;});css+=sheet+'\n';fs.copyFileSync(new URL('LICENSE',source),new URL('LICENSE.txt',target));
}
fs.writeFileSync(asset('fonts.css'),css);fs.mkdirSync(asset('ocr/lang/'),{recursive:true});
for(const file of ['worker.min.js','worker.min.js.LICENSE.txt'])fs.copyFileSync(new URL('node_modules/tesseract.js/dist/'+file,root),asset('ocr/'+file));
for(const file of ['tesseract-core-lstm.wasm.js','tesseract-core-lstm.wasm','LICENSE'])fs.copyFileSync(new URL('node_modules/tesseract.js-core/'+file,root),asset('ocr/'+file));
for(const lang of ['jpn','eng'])fs.copyFileSync(new URL(`node_modules/@tesseract.js-data/${lang}/4.0.0/${lang}.traineddata.gz`,root),asset('ocr/lang/'+lang+'.traineddata.gz'));
for(const entry of ['app','share','share-worker'])await build({entryPoints:[asset(entry+'.js').pathname],bundle:true,platform:'browser',format:'esm',target:['es2022'],minify:true,legalComments:'inline',outfile:asset(entry+'.bundle.js').pathname});
console.log('Browser trial, local fonts, local OCR and QR page built.');
