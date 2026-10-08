// 把 docs/play/src 里的 HTML 渲染成 Google Play 商品详情的素材。
//
//   node docs/play/src/render.cjs chat-page        → app/src/test/resources/play/chat-page.png（截图用的样图，1 倍）
//   node docs/play/src/render.cjs icon             → docs/play/icon.png（512×512）
//   node docs/play/src/render.cjs feature-graphic  → docs/play/feature-graphic.png（1024×500）
//   node docs/play/src/render.cjs screenshots      → docs/play/screenshots/*.png（1080×1920，配文见 screenshots.json）
//   node docs/play/src/render.cjs all              → 后三项
//
// screenshots 需要先用 app/src/test/java/com/yomark/app/store/PlayScreenshots.kt 把编辑器截图拍到 docs/play/shots/。
// 渲染完再跑 python3 docs/play/src/finalize.py：去掉 Play 不收的 alpha 通道，并核对尺寸与体积。
// 需要 Playwright（npm i -g playwright，运行时带上 NODE_PATH="$(npm root -g)"）和能访问 Google Fonts 的网络。
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

const ROOT = path.join(__dirname, '..', '..', '..');
const SHOTS = require('./screenshots.json');

const TARGETS = {
  'chat-page': { html: 'chat-page.html', size: [1080, 1680], out: 'app/src/test/resources/play/chat-page.png' },
  // Play 要的图标是带 alpha 的 32 位 PNG，其余素材都不能带 alpha（见 finalize.py）
  icon: { html: 'icon.html', size: [512, 512], out: 'docs/play/icon.png', transparent: true },
  'feature-graphic': { html: 'feature-graphic.html', size: [1024, 500], out: 'docs/play/feature-graphic.png' },
};

const screenshotJobs = () => SHOTS.map((shot) => ({
  html: 'screenshot.html',
  size: [1080, 1920],
  out: `docs/play/screenshots/${shot.id}.png`,
  // 排版页先打开，再把这一张的配文与截图交给它；show() 等截图加载完才返回
  prepare: (page) => page.evaluate((s) => window.show(s), shot),
}));

async function render(browser, { html, size: [width, height], out, transparent = false, prepare }) {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  await page.goto(pathToFileURL(path.join(__dirname, html)).href, { waitUntil: 'networkidle' });
  if (prepare) await prepare(page);
  await page.evaluate(() => document.fonts.ready);
  const file = path.join(ROOT, out);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  await page.screenshot({ path: file, omitBackground: transparent });
  await page.close();
  console.log(out);
}

(async () => {
  const name = process.argv[2];
  const jobs = [];
  if (name === 'screenshots' || name === 'all') jobs.push(...screenshotJobs());
  if (name === 'all') jobs.push(TARGETS.icon, TARGETS['feature-graphic']);
  else if (TARGETS[name]) jobs.push(TARGETS[name]);
  if (!jobs.length) throw new Error(`用法：render.cjs <${[...Object.keys(TARGETS), 'screenshots', 'all'].join('|')}>`);

  const browser = await chromium.launch();
  for (const job of jobs) await render(browser, job);
  await browser.close();
})();
