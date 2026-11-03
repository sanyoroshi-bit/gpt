import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./test/browser',workers:1,use:{baseURL:'http://127.0.0.1:3000',headless:true,launchOptions:{...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH}:{})}},webServer:{command:'npm start',url:'http://127.0.0.1:3000',reuseExistingServer:true}});
