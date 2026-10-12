// 应用图标的三个方案，输出到 docs/icon/：
//
//   node docs/icon/src/icons.mjs
//
// 每张都是 1024×1024 的满版方形，不带圆角：圆形、圆角方形、超椭圆这些外形由启动器或展示的地方去裁。
// 1024 对应自适应图标露出来的 72 dp，主体都收在中间 66 dp 的安全圆里，裁成圆形也不会切到。
//
//   icon-a-office.svg  Office 式：右边一张由浅到深的色阶截图，左边压一块墨色方牌，牌上是那道码
//   icon-b-bigsur.svg  Big Sur 式：墨色底板上一张截图，一道天蓝色块横压过去，两头探出截图
//   icon-c-tape.svg    同上，色块换成两头撕口的胶带，斜着贴
//
// 颜色取自编辑器：打码块的天蓝 #87CEEB、待确认虚线框的琥珀 #FFB300、原图标的墨色 #101014。
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const OUT = fileURLToPath(new URL('..', import.meta.url));
mkdirSync(OUT, { recursive: true });
const write = (name, s) => {
  writeFileSync(`${OUT}${name}.svg`, s);
  console.log(`docs/icon/${name}.svg`);
};
const f = (n) => +n.toFixed(2);

const stops = (s) => s.map(([o, c, a = 1]) =>
  `      <stop offset="${o}" stop-color="${c}"${a === 1 ? '' : ` stop-opacity="${a}"`}/>`).join('\n');
const linear = (id, [x1, y1, x2, y2], s) =>
  `    <linearGradient id="${id}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" gradientUnits="userSpaceOnUse">\n${stops(s)}\n    </linearGradient>`;
const radial = (id, [cx, cy, r], s) =>
  `    <radialGradient id="${id}" cx="${cx}" cy="${cy}" r="${r}" gradientUnits="userSpaceOnUse">\n${stops(s)}\n    </radialGradient>`;
// 两层投影：贴得近的一层收边，散得开的一层给体积。
// 不用 SVG 2 才有的 feDropShadow，拆成 SVG 1.1 的模糊、位移、上色三步，旧一点的渲染器也认
const shadowLayer = ([dy, blur, color, opacity], name) =>
  `      <feGaussianBlur in="SourceAlpha" stdDeviation="${blur}"/>\n` +
  `      <feOffset dy="${dy}" result="${name}Offset"/>\n` +
  `      <feFlood flood-color="${color}" flood-opacity="${opacity}"/>\n` +
  `      <feComposite in2="${name}Offset" operator="in" result="${name}"/>\n`;
const dropShadow = (id, near, far) =>
  `    <filter id="${id}" x="-50%" y="-50%" width="200%" height="200%" color-interpolation-filters="sRGB">\n` +
  shadowLayer(far, 'far') + shadowLayer(near, 'near') +
  `      <feMerge><feMergeNode in="far"/><feMergeNode in="near"/><feMergeNode in="SourceGraphic"/></feMerge>\n` +
  `    </filter>`;
const doc = (defs, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">\n` +
  `  <title>有码 Yomark</title>\n  <defs>\n${defs.join('\n')}\n  </defs>\n${body.join('\n')}\n</svg>\n`;
const rr = (x, y, w, h, r, attrs) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" ${attrs}/>`;

const SKY = '#87CEEB', AMBER = '#FFB300';

// 圆角虚线框：四个角是带腿的圆弧，竖边只留一道缺口，横边上的短划均匀排开。
// tab 是左上角那条腿的长度，标签压在这一段实线上，标签底下就不会露出一截截的虚线
function marquee({ x, y, w, h, r, dash, gap, sw, color, tab = dash / 2 }) {
  const legV = (h - 2 * r - gap) / 2, legH = dash / 2;
  const d = [];
  d.push(`M${x} ${f(y + r + legV)}V${y + r}A${r} ${r} 0 0 1 ${x + r} ${y}H${f(x + r + tab)}`);
  d.push(`M${f(x + w - r - legH)} ${y}H${x + w - r}A${r} ${r} 0 0 1 ${x + w} ${y + r}V${f(y + r + legV)}`);
  d.push(`M${x + w} ${f(y + h - r - legV)}V${y + h - r}A${r} ${r} 0 0 1 ${x + w - r} ${y + h}H${f(x + w - r - legH)}`);
  d.push(`M${f(x + r + legH)} ${y + h}H${x + r}A${r} ${r} 0 0 1 ${x} ${y + h - r}V${f(y + h - r - legV)}`);
  // 横边上两条腿之间：n 段短划，n+1 道缺口，短划长度微调到正好排满
  const run = (x0, x1, yy) => {
    const len = x1 - x0, n = Math.max(1, Math.round((len - gap) / (dash + gap)));
    const seg = (len - (n + 1) * gap) / n;
    for (let i = 0; i < n; i++) d.push(`M${f(x0 + gap + i * (seg + gap))} ${yy}h${f(seg)}`);
  };
  run(x + r + tab, x + w - r - legH, y);
  run(x + r + legH, x + w - r - legH, y + h);
  return `<path d="${d.join('')}" fill="none" stroke="${color}" stroke-width="${sw}"/>`;
}

// 胶带：两头撕口是不太规则的锯齿，固定种子，每次生成都一样
function tape(x0, y0, w, h, teeth, depth, seed = 7) {
  let s = seed;
  const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647);
  const edge = (xBase, dir) => {
    const pts = [];
    for (let j = 0; j <= 2 * teeth; j++) {
      const jitter = j === 0 || j === 2 * teeth ? 0 : ((rnd() - 0.5) * h / teeth) * 0.35;
      const dd = j % 2 ? 0 : depth * (0.7 + rnd() * 0.6);
      pts.push([xBase + dir * dd, y0 + (j / (2 * teeth)) * h + jitter]);
    }
    return pts;
  };
  const right = edge(x0 + w, -1), left = edge(x0, 1).reverse();
  return 'M' + [...right, ...left].map(([x, y]) => `${f(x)} ${f(y)}`).join('L') + 'Z';
}

// ── B / C：墨色底板上一张截图，一道天蓝的码压在上面 ─────────────────────────────────────
function card(name, o) {
  const C = { x: 232, y: 156, w: 560, h: 712, r: 64 };
  const B = { x: 176, y: 306, w: 672, h: 144, r: 28, rot: 0, ...o.bar };
  const L = C.x + 64, line = '#D3D9E2';
  const M = { x: L, y: 560, w: 384, h: 92, r: 20 };
  const defs = [
    linear('bg', [0, 0, 0, 1024], [[0, '#34374D'], [1, '#0F1015']]),
    // 底板中间透一点蓝紫，呼应识别时那道天蓝到淡紫的扫光
    radial('glow', [512, 540, 580], [[0, '#4A4FA8', 0.45], [1, '#4A4FA8', 0]]),
    radial('sheen', [512, -120, 820], [[0, '#FFFFFF', 0.11], [1, '#FFFFFF', 0]]),
    linear('paper', [0, C.y, 0, C.y + C.h], [[0, '#FFFFFF'], [1, '#E6EBF2']]),
    linear('mask', [0, B.y, 0, B.y + B.h], [[0, '#AEE5F9'], [0.55, SKY], [1, '#62B9E2']]),
    // 上沿一道高光、下沿一道暗边，色块就有了厚度
    linear('maskLight', [0, B.y, 0, B.y + B.h], [[0, '#FFFFFF', 0.6], [0.16, '#FFFFFF', 0], [0.84, '#1C76A8', 0], [1, '#1C76A8', 0.35]]),
    dropShadow('cardShadow', [8, 10, '#000', 0.3], [40, 44, '#000', 0.5]),
    dropShadow('maskShadow', [5, 5, '#0A3A5C', 0.32], [20, 22, '#0A3A5C', 0.34]),
  ];
  const cx = B.x + B.w / 2, cy = B.y + B.h / 2;
  const shape = (attrs) => o.tape
    ? `<path d="${tape(B.x, B.y, B.w, B.h, 7, 15)}" ${attrs}/>`
    : rr(B.x, B.y, B.w, B.h, B.r, attrs);
  const body = [
    `  <rect width="1024" height="1024" fill="url(#bg)"/>`,
    `  <rect width="1024" height="1024" fill="url(#glow)"/>`,
    `  <rect width="1024" height="1024" fill="url(#sheen)"/>`,
    `  <!-- 截图 -->`,
    `  ${rr(C.x, C.y, C.w, C.h, C.r, 'fill="url(#paper)" filter="url(#cardShadow)"')}`,
    `  ${rr(L, C.y + 64, 208, 44, 22, 'fill="#BAC3CF"')}`,
    `  <!-- 琥珀虚线框和类型标签：识别到了，等你确认。标签的左下角顺着框内侧的圆角收进去，和框连成一块 -->`,
    `  <path d="M${M.x - 6} ${M.y + M.r}V${M.y - 30}a10 10 0 0 1 10 -10H${M.x + 98}a10 10 0 0 1 10 10V${M.y + 6}H${M.x + M.r}A${M.r - 6} ${M.r - 6} 0 0 0 ${M.x + 6} ${M.y + M.r}Z" fill="${AMBER}"/>`,
    `  ${marquee({ ...M, dash: 30, gap: 17, sw: 12, color: AMBER, tab: 108 - M.r })}`,
    `  ${rr(M.x + 30, M.y + 24, M.w - 60, 44, 22, `fill="${line}"`)}`,
    `  ${rr(L, 708, 400, 44, 22, `fill="${line}"`)}`,
    `  ${rr(L, 788, 268, 44, 22, `fill="${line}"`)}`,
    `  <!-- 天蓝色块：已经打上的码 -->`,
    `  <g transform="rotate(${B.rot} ${cx} ${cy})">`,
    `    ${shape('fill="url(#mask)" filter="url(#maskShadow)"')}`,
    `    ${shape('fill="url(#maskLight)"')}`,
    `  </g>`,
  ];
  write(name, doc(defs, body));
}

// ── A：右边一张色阶截图做底板，左边压一块方牌，牌上是那道码 ──────────────────────────────
function office(name) {
  const D = { x: 400, y: 184, w: 432, h: 656, r: 64 };
  const P = { x: 192, y: 332, w: 360, h: 360, r: 60 };
  const bands = ['#8DD5F3', '#62B2EC', '#4F8BDF', '#4C62CC'];
  const bh = D.h / bands.length;
  const defs = [
    linear('bg', [0, 0, 0, 1024], [[0, '#FFFFFF'], [1, '#EDF2F7']]),
    `    <clipPath id="screen">${rr(D.x, D.y, D.w, D.h, D.r, '')}</clipPath>`,
    linear('plate', [P.x, P.y, P.x + P.w, P.y + P.h], [[0, '#3E4256'], [1, '#111217']]),
    linear('plateLight', [0, P.y, 0, P.y + P.h], [[0, '#FFFFFF', 0.14], [0.5, '#FFFFFF', 0]]),
    linear('mark', [0, P.y + 128, 0, P.y + 232], [[0, '#B9EBFB'], [1, '#79C6EA']]),
    dropShadow('plateShadow', [4, 6, '#0E1430', 0.3], [18, 28, '#0E1430', 0.38]),
  ];
  const body = [
    `  <rect width="1024" height="1024" fill="url(#bg)"/>`,
    `  <!-- 截图：由浅到深的几行 -->`,
    `  <g clip-path="url(#screen)">`,
    ...bands.map((c, i) => `    <rect x="${D.x}" y="${f(D.y + i * bh)}" width="${D.w}" height="${f(bh + 1)}" fill="${c}"/>`),
    `  </g>`,
    `  <!-- 方牌和码 -->`,
    `  ${rr(P.x, P.y, P.w, P.h, P.r, 'fill="url(#plate)" filter="url(#plateShadow)"')}`,
    `  ${rr(P.x, P.y, P.w, P.h, P.r, 'fill="url(#plateLight)"')}`,
    `  ${rr(P.x + 64, P.y + 128, P.w - 128, 104, 16, 'fill="url(#mark)"')}`,
  ];
  write(name, doc(defs, body));
}

office('icon-a-office');
card('icon-b-bigsur', {});
card('icon-c-tape', { tape: true, bar: { rot: -7 } });
