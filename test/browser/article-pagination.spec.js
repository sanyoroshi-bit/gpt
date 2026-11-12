import {test,expect} from '@playwright/test';
import {createArticleFetcher} from '../../lib/article.js';

test('同じ記事の複数ページだけを本文として受け取り、途中停止の説明も表示する',async({page})=>{
 const root='https://news.example.org/articles/reading-123.html';
 const text=n=>`第${n}ページの本文を順番に読みます。タイトルや表を除き、同じ記事の続きを読むことを確認します。`;
 const html=n=>`<title>続けて読む記事</title><link rel="canonical" href="${root}">${n===1?`<link rel="next" href="${root}?page=2">`:''}<article><h1>見出しは本文に含めない</h1><p>${text(n)}</p><table><tr><td>表も本文に含めない</td></tr></table></article>`;
 const fetcher=createArticleFetcher({validate:async(value,{signal})=>{signal.throwIfAborted();return new URL(value);},request:async url=>new Response(html(url.search?2:1),{headers:{'content-type':'text/html'}})});
 const result=await fetcher(root);expect(result.pagesRead).toBe(2);
 await page.route('**/api/article',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(result)}));
 await page.setViewportSize({width:320,height:900});await page.goto('/');await page.locator('#url-tab').click();await page.locator('#url').fill(root);await page.locator('#load').click();
 await expect(page.locator('#original-text')).toHaveText(text(1)+'\n\n'+text(2));await expect(page.locator('#article-notice')).toContainText('2ページ分');
 await page.locator('[name="reading-mode"][value="character"]').check();
 const sequence=await page.evaluate(()=>{const range=document.getElementById('progress');let text='';for(let i=0;i<=+range.max;i++){range.value=i;range.dispatchEvent(new Event('input',{bubbles:true}));text+=document.getElementById('word').textContent;}return text;});
 expect(sequence).toBe((text(1)+text(2)).replace(/\s/g,''));expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBe(320);
 await page.unroute('**/api/article');await page.route('**/api/article',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({...result,text:text(1),warnings:['次ページを確認できず、途中までの本文を表示しています。']})}));
 await page.locator('#load').click();await expect(page.locator('#article-notice')).toContainText('途中まで');await expect(page.locator('#original-text')).toHaveText(text(1));
});
