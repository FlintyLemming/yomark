// 应用图标，输出 docs/icon/icon.svg：
//
//   node docs/icon/src/icons.mjs
//
// 墨色底板上一张截图，一条天蓝的胶带斜着贴住其中一行，下面一行套着琥珀虚线框和类型标签，
// 和编辑器里「已打码」「待确认」两种标记对应。颜色也取自编辑器：打码块的天蓝 #87CEEB、
// 待确认框的琥珀 #FFB300。
//
// 1024×1024 满版方形，不带圆角：圆形、圆角方形、超椭圆这些外形由启动器或展示的地方去裁。
// 1024 对应自适应图标露出来的 72 dp，主体都收在中间 66 dp 的安全圆里，裁成圆形也不会切到。
//
// 面上都是平涂，光影只留在边上：胶带上沿一道亮边、下沿一道暗边，截图底边一道浅灰，
// 再各带一圈贴边的投影。不用大面积的渐变和高光，看久了不腻。
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const OUT = fileURLToPath(new URL('..', import.meta.url));
const f = (n) => +n.toFixed(2);

const stops = (s) => s.map(([o, c, a = 1]) =>
  `      <stop offset="${f(o)}" stop-color="${c}"${a === 1 ? '' : ` stop-opacity="${a}"`}/>`).join('\n');
const linear = (id, [x1, y1, x2, y2], s) =>
  `    <linearGradient id="${id}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" gradientUnits="userSpaceOnUse">\n${stops(s)}\n    </linearGradient>`;
// 贴边的一圈投影。不用 SVG 2 才有的 feDropShadow，拆成 SVG 1.1 的模糊、位移、上色三步，旧一点的渲染器也认
const edgeShadow = (id, [dy, blur, color, opacity]) =>
  `    <filter id="${id}" x="-20%" y="-20%" width="140%" height="140%" color-interpolation-filters="sRGB">\n` +
  `      <feGaussianBlur in="SourceAlpha" stdDeviation="${blur}"/>\n` +
  `      <feOffset dy="${dy}" result="offset"/>\n` +
  `      <feFlood flood-color="${color}" flood-opacity="${opacity}"/>\n` +
  `      <feComposite in2="offset" operator="in"/>\n` +
  `      <feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge>\n` +
  `    </filter>`;
const rr = (x, y, w, h, r, attrs) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" ${attrs}/>`;

const SKY = '#87CEEB', AMBER = '#FFB300', LINE = '#D3D9E2';

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

const C = { x: 232, y: 156, w: 560, h: 712, r: 64 }; // 截图
const T = { x: 176, y: 306, w: 672, h: 144, rot: -7 }; // 胶带，两头探出截图
const L = C.x + 64; // 文字行左边
const M = { x: L, y: 560, w: 384, h: 92, r: 20 }; // 虚线框
const PAPER_EDGE = 5, TAPE_EDGE = 5; // 截图底边、胶带上下沿那道边的宽度

const defs = [
  linear('bg', [0, 0, 0, 1024], [[0, '#1F2029'], [1, '#17181F']]),
  linear('paper', [0, C.y, 0, C.y + C.h], [[0, '#FFFFFF'], [1 - PAPER_EDGE / C.h, '#FFFFFF'], [1, '#DDE3EB']]),
  linear('tapeEdge', [0, T.y, 0, T.y + T.h], [
    [0, '#FFFFFF', 0.45], [TAPE_EDGE / T.h, '#FFFFFF', 0],
    [1 - TAPE_EDGE / T.h, '#1C76A8', 0], [1, '#1C76A8', 0.35],
  ]),
  edgeShadow('paperShadow', [3, 4, '#000', 0.3]),
  edgeShadow('tapeShadow', [3, 3, '#0A3A5C', 0.28]),
];
const tp = tape(T.x, T.y, T.w, T.h, 7, 15);
const body = [
  `  <rect width="1024" height="1024" fill="url(#bg)"/>`,
  `  <!-- 截图 -->`,
  `  ${rr(C.x, C.y, C.w, C.h, C.r, 'fill="url(#paper)" filter="url(#paperShadow)"')}`,
  `  ${rr(L, C.y + 64, 208, 44, 22, 'fill="#BAC3CF"')}`,
  `  <!-- 琥珀虚线框和类型标签：识别到了，等你确认。标签的左下角顺着框内侧的圆角收进去，和框连成一块 -->`,
  `  <path d="M${M.x - 6} ${M.y + M.r}V${M.y - 30}a10 10 0 0 1 10 -10H${M.x + 98}a10 10 0 0 1 10 10V${M.y + 6}H${M.x + M.r}A${M.r - 6} ${M.r - 6} 0 0 0 ${M.x + 6} ${M.y + M.r}Z" fill="${AMBER}"/>`,
  `  ${marquee({ ...M, dash: 30, gap: 17, sw: 12, color: AMBER, tab: 108 - M.r })}`,
  `  ${rr(M.x + 30, M.y + 24, M.w - 60, 44, 22, `fill="${LINE}"`)}`,
  `  ${rr(L, 708, 400, 44, 22, `fill="${LINE}"`)}`,
  `  ${rr(L, 788, 268, 44, 22, `fill="${LINE}"`)}`,
  `  <!-- 天蓝胶带：已经打上的码 -->`,
  `  <g transform="rotate(${T.rot} ${T.x + T.w / 2} ${T.y + T.h / 2})">`,
  `    <path d="${tp}" fill="${SKY}" filter="url(#tapeShadow)"/>`,
  `    <path d="${tp}" fill="url(#tapeEdge)"/>`,
  `  </g>`,
];

mkdirSync(OUT, { recursive: true });
writeFileSync(`${OUT}icon.svg`,
  `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">\n` +
  `  <title>有码 Yomark</title>\n  <defs>\n${defs.join('\n')}\n  </defs>\n${body.join('\n')}\n</svg>\n`);
console.log('docs/icon/icon.svg');
