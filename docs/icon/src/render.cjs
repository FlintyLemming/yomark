// 从图标母版渲染要位图的地方。矢量的那些（启动器图标、favicon）由 icons.mjs 直接生成，不走这里。
//
//   node docs/icon/src/render.cjs   → site/apple-touch-icon.png（180×180）
//
// Play 商店图标走 node docs/play/src/render.cjs icon，README 头图走 node docs/readme/src/render.cjs hero。
// 需要 Playwright（npm i -g playwright，运行时带上 NODE_PATH="$(npm root -g)"）。
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

const ROOT = path.join(__dirname, '..', '..', '..');
const TARGETS = [
  { html: 'apple-touch-icon.html', size: 180, out: 'site/apple-touch-icon.png' },
];

(async () => {
  const browser = await chromium.launch();
  for (const { html, size, out } of TARGETS) {
    const page = await browser.newPage({ viewport: { width: size, height: size }, deviceScaleFactor: 1 });
    await page.goto(pathToFileURL(path.join(__dirname, html)).href, { waitUntil: 'networkidle' });
    await page.screenshot({ path: path.join(ROOT, out) });
    await page.close();
    console.log(out);
  }
  await browser.close();
})();
