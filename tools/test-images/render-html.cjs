// 把 tools/test-images 里的 HTML 渲染成测试图（1 倍像素，等网页字体加载完）。
//
//   node tools/test-images/render-html.cjs <html> <out.png> <宽> <高>
//
// 需要 Playwright（npm i -g playwright，运行时带上 NODE_PATH="$(npm root -g)"）和能访问 Google Fonts 的网络。
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

(async () => {
  const [html, out, width, height] = process.argv.slice(2);
  if (!html || !out || !width || !height) throw new Error('用法：render-html.cjs <html> <out.png> <宽> <高>');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: Number(width), height: Number(height) }, deviceScaleFactor: 1 });
  await page.goto(pathToFileURL(path.resolve(html)).href, { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: out });
  await browser.close();
  console.log(out);
})();
