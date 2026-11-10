import {test,expect} from '@playwright/test';

const longText='短い文。\n'+('Supercalifragilisticexpialidocious'.repeat(3))+'。\n'+('👨‍👩‍👧‍👦🇯🇵e\u0301'.repeat(8))+'。';
async function loadText(page,text){await page.locator('#text').fill(text);await page.locator('#load').click();}
async function checkEveryUnit(page){
 return page.evaluate(()=>{
  const progress=document.getElementById('progress'),panel=document.querySelector('.reader-panel'),stage=document.getElementById('stage');
  const initial=panel.getBoundingClientRect().width,failures=[];
  for(let i=0;i<=+progress.max;i++){
   progress.value=i;progress.dispatchEvent(new Event('input',{bubbles:true}));
   const area=stage.getBoundingClientRect(),width=panel.getBoundingClientRect().width;
   if(Math.abs(width-initial)>.5||document.documentElement.scrollWidth>innerWidth)failures.push({i,width,initial,scroll:document.documentElement.scrollWidth,viewport:innerWidth});
   for(const id of ['before','anchor','after']){
    const node=document.getElementById(id);if(!node.textContent)continue;
    const range=document.createRange();range.selectNodeContents(node);const r=range.getBoundingClientRect();
    if(r.left<area.left+1-.5||r.right>area.right-1+.5)failures.push({i,id,text:node.textContent,left:r.left,right:r.right,areaLeft:area.left,areaRight:area.right});
   }
   if(failures.length>4)break;
  }
  return failures;
 });
}

for(const width of [320,360,390,780,781,1024,1440]){
 test(`${width}pxで文字数・モード・集中表示を変えても横幅が一定`,async({page})=>{
  await page.setViewportSize({width,height:900});await page.goto('/');
  await page.locator('#font-size').fill('64');await page.evaluate(()=>document.fonts.ready);
  for(const focused of [false,true]){
   if(focused)await page.locator('#focus').click();
   for(const mode of ['phrase','word','character']){
    await page.locator(`[name="reading-mode"][value="${mode}"]`).check();
    // The input panel is intentionally hidden in focus mode; load using the same UI handler.
    if(focused)await page.evaluate(text=>{document.getElementById('text').value=text;document.getElementById('load').click();},'短い文。');
    else await loadText(page,'短い文。');
    const shortWidth=await page.locator('.reader-panel').evaluate(el=>el.getBoundingClientRect().width);
    if(focused)await page.evaluate(text=>{document.getElementById('text').value=text;document.getElementById('load').click();},longText);
    else await loadText(page,longText);
    expect(await page.locator('.reader-panel').evaluate(el=>el.getBoundingClientRect().width)).toBeCloseTo(shortWidth,1);
    expect(await checkEveryUnit(page)).toEqual([]);
   }
  }
 });
}

test('320px・全フォント・64pxの長い表示単位が読書欄に収まる',async({page},testInfo)=>{
 await page.setViewportSize({width:320,height:900});await page.goto('/');await page.locator('#font-size').fill('64');await loadText(page,longText);
 for(const font of ['system','sans','serif','rounded','ud','kaisei']){
  await page.locator('#font-family').selectOption(font);await page.evaluate(()=>document.fonts.ready);
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  expect(await checkEveryUnit(page),font).toEqual([]);
 }
 await page.locator('#focus').click();
 await page.screenshot({path:testInfo.outputPath('reader-width-320-focused.png'),fullPage:true});
});
