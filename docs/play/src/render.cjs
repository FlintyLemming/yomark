// 把 docs/play/src 里的 HTML 渲染成 Google Play 商品详情的素材。
//
//   node docs/play/src/render.cjs chat-page           → app/src/test/resources/play/chat-page.png（截图用的样图，1 倍）
//   node docs/play/src/render.cjs chat-page-en        → app/src/test/resources/play/chat-page-en.png（它的英文版，首次引导和英文截图用）
//   node docs/play/src/render.cjs order-page-en       → app/src/test/resources/play/order-page-en.png（快递详情样图的英文版，英文截图用）
//   node docs/play/src/render.cjs icon                → docs/play/icon.png（512×512）
//   node docs/play/src/render.cjs iap-icon            → docs/play/iap-remove-watermark.png（512×512，一次性商品「去除水印」的图标）
//   node docs/play/src/render.cjs feature-graphic     → docs/play/feature-graphic.png（1024×500）
//   node docs/play/src/render.cjs feature-graphic-en  → docs/play/feature-graphic-en.png（英文版）
//   node docs/play/src/render.cjs screenshots         → docs/play/screenshots/*.png（1080×1920，配文见 screenshots.json）
//   node docs/play/src/render.cjs screenshots-en      → docs/play/screenshots-en/*.png（英文版，配文见 screenshots-en.json）
//   node docs/play/src/render.cjs all                 → screenshots、icon、feature-graphic
//   node docs/play/src/render.cjs all-en              → screenshots-en、feature-graphic-en
//
// screenshots 需要先用 app/src/test/java/moe/flinty/yomark/store/PlayScreenshots.kt 把编辑器截图拍到 docs/play/shots/，
// screenshots-en 要先用同目录的 PlayScreenshotsEn.kt 拍到 docs/play/shots-en/。
// 渲染完再跑 python3 docs/play/src/finalize.py：去掉 Play 不收的 alpha 通道，并核对尺寸与体积。
// 需要 Playwright（npm i -g playwright，运行时带上 NODE_PATH="$(npm root -g)"）和能访问 Google Fonts 的网络。
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

const ROOT = path.join(__dirname, '..', '..', '..');

const TARGETS = {
  'chat-page': { html: 'chat-page.html', size: [1080, 1680], out: 'app/src/test/resources/play/chat-page.png' },
  'chat-page-en': { html: 'chat-page-en.html', size: [1080, 1680], out: 'app/src/test/resources/play/chat-page-en.png' },
  'order-page-en': { html: 'order-page-en.html', size: [1080, 1680], out: 'app/src/test/resources/play/order-page-en.png' },
  // Play 要的图标是带 alpha 的 32 位 PNG，其余素材都不能带 alpha（见 finalize.py）
  icon: { html: 'icon.html', size: [512, 512], out: 'docs/play/icon.png', transparent: true },
  'iap-icon': { html: 'iap-icon.html', size: [512, 512], out: 'docs/play/iap-remove-watermark.png', transparent: true },
  'feature-graphic': { html: 'feature-graphic.html', size: [1024, 500], out: 'docs/play/feature-graphic.png' },
  'feature-graphic-en': { html: 'feature-graphic-en.html', size: [1024, 500], out: 'docs/play/feature-graphic-en.png' },
};

// 中文一套、英文一套：配文各在各的 json 里，编辑器截图各在各的目录（screenshot.html 按 dir 找）
const SETS = {
  screenshots: { shots: require('./screenshots.json'), out: 'docs/play/screenshots' },
  'screenshots-en': { shots: require('./screenshots-en.json'), out: 'docs/play/screenshots-en', dir: '../shots-en/', lang: 'en' },
};

const screenshotJobs = ({ shots, out, dir, lang }) => shots.map((shot) => ({
  html: 'screenshot.html',
  size: [1080, 1920],
  out: `${out}/${shot.id}.png`,
  // 排版页先打开，再把这一张的配文与截图交给它；show() 等截图加载完才返回，返回时顺便报一声哪行字放不下
  prepare: async (page) => {
    const overflow = await page.evaluate((s) => window.show(s), { ...shot, dir, lang });
    if (overflow.length) console.warn(`  ${shot.id}：${overflow.join('、')}超出了版面`);
  },
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
  if (SETS[name]) jobs.push(...screenshotJobs(SETS[name]));
  else if (name === 'all') jobs.push(...screenshotJobs(SETS.screenshots), TARGETS.icon, TARGETS['feature-graphic']);
  else if (name === 'all-en') jobs.push(...screenshotJobs(SETS['screenshots-en']), TARGETS['feature-graphic-en']);
  else if (TARGETS[name]) jobs.push(TARGETS[name]);
  if (!jobs.length) {
    throw new Error(`用法：render.cjs <${[...Object.keys(TARGETS), ...Object.keys(SETS), 'all', 'all-en'].join('|')}>`);
  }

  const browser = await chromium.launch();
  for (const job of jobs) await render(browser, job);
  await browser.close();
})();
