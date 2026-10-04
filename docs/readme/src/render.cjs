// 把 docs/readme/src 里的 HTML 渲染成 PNG。
//
//   node docs/readme/src/render.cjs order-page → app/src/test/resources/readme/order-page.png（样图，1 倍）
//   node docs/readme/src/render.cjs hero       → docs/readme/hero.png
//   node docs/readme/src/render.cjs styles     → docs/readme/styles.png
//
// 先用 app/src/test/java/com/youma/app/readme/ReadmeScreenshots.kt 把截图拍到 docs/readme/shots/，再渲染。
// 需要 Playwright（npm i -g playwright）和能访问 Google Fonts 的网络。
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

const ROOT = path.join(__dirname, '..', '..', '..');
const TARGETS = {
  'order-page': { size: [1080, 1680], scale: 1, out: 'app/src/test/resources/readme/order-page.png' },
  hero: { size: [1280, 640], scale: 2, out: 'docs/readme/hero.png' },
  styles: { size: [1280, 500], scale: 2, out: 'docs/readme/styles.png' },
};

(async () => {
  const name = process.argv[2];
  const target = TARGETS[name];
  if (!target) throw new Error(`用法：render.cjs <${Object.keys(TARGETS).join('|')}>`);
  const [width, height] = target.size;
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: target.scale });
  await page.goto(pathToFileURL(path.join(__dirname, `${name}.html`)).href, { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);
  const out = path.join(ROOT, target.out);
  await page.screenshot({ path: out });
  await browser.close();
  console.log(out);
})();
