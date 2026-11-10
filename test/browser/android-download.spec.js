import {test,expect} from '@playwright/test';
import {createHash} from 'node:crypto';

test('Android試用版の案内がスマホに収まり、APKとチェックサムを取得できる',async({page},testInfo)=>{
 await page.setViewportSize({width:320,height:900});await page.goto('/');
 await page.locator('.mobile-install summary').click();
 await page.getByRole('link',{name:'Android用画面リーダーの試用版'}).click();
 await expect(page).toHaveURL(/\/android\.html$/);
 await expect(page.locator('.android-guide')).toContainText('Android実機での動作は未確認です');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBe(320);
 const link=page.getByRole('link',{name:'Android試用版をダウンロード ↓'});
 await expect(link).toHaveAttribute('download','');
 const response=await page.request.get(await link.getAttribute('href'));
 expect(response.ok()).toBe(true);
 expect(response.headers()['content-type']).toContain('application/vnd.android.package-archive');
 const body=await response.body();expect(body.subarray(0,4).toString('hex')).toBe('504b0304');
 const expected=await(await page.request.get('/downloads/ten-launcher-debug.apk.sha256')).text();
 expect(createHash('sha256').update(body).digest('hex')).toBe(expected.trim().split(/\s+/)[0]);
 await page.screenshot({path:testInfo.outputPath('android-download-320.png'),fullPage:true,animations:'disabled'});
});
