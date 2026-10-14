// 应用图标。一份几何，生成所有要用到图标的矢量文件：
//
//   node docs/icon/src/icons.mjs
//
//   docs/icon/icon.svg                                 母版，1024×1024 满版方形，带投影
//   app/src/main/res/drawable/ic_launcher_background.xml   自适应图标的背景层：底板和截图
//   app/src/main/res/drawable/ic_launcher_foreground.xml   自适应图标的前景层：只有胶带
//   app/src/main/res/drawable/ic_launcher_monochrome.xml   Android 13 起的主题图标：单色剪影
//   docs/icon/icon-monochrome.svg                      上面那个剪影的预览，只给人看
//   site/favicon.svg                                    官网的网页图标和品牌标，圆角方形
//
// 要位图的地方从母版渲染：官网的 apple-touch-icon 见 docs/icon/src/render.cjs，Play 商店图标见
// docs/play/src/icon.html，README 头图见 docs/readme/src/hero.html。
// 导出水印上那个不带底板的小图标（export/BrandGlyph.kt）抄的是这里的几何，改了这里要跟着改，
// BrandGlyphTest 会拿母版核对。
//
// 画面：墨色底板上一张截图，一条天蓝的胶带斜着贴住其中一行，下面一行套着琥珀虚线框和类型标签，
// 和编辑器里「已打码」「待确认」两种标记对应。颜色也取自编辑器：打码块的天蓝 #87CEEB、
// 待确认框的琥珀 #FFB300。面上都是平涂，光影只留在边上：胶带上沿一道亮边、下沿一道暗边，
// 截图底边一道浅灰，再各带一圈贴边的投影。
//
// 坐标都按母版的 1024 算，1024 对应自适应图标露出来的 72 dp。主体收在中间 66 dp 的安全圆里，
// 裁成圆形也不会切到。矢量图标的画布是 108 dp，四周各多出 18 dp（256）给系统做视差。
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../../', import.meta.url));
const f = (n) => +n.toFixed(2);
const off = (n) => +n.toFixed(4); // 渐变的位置：边只有几像素宽，两位小数不够

// ── 几何 ─────────────────────────────────────────────────────────────────────────────
const SKY = '#87CEEB', AMBER = '#FFB300', LINE = '#D3D9E2', TITLE = '#BAC3CF';
const BG = ['#1F2029', '#17181F'];
const C = { x: 232, y: 156, w: 560, h: 712, r: 64 }; // 截图
const T = { x: 176, y: 306, w: 672, h: 144, rot: -7 }; // 胶带，两头探出截图
const PIVOT = [T.x + T.w / 2, T.y + T.h / 2];
const L = C.x + 64; // 文字行左边
const M = { x: L, y: 560, w: 384, h: 92, r: 20, sw: 12 }; // 虚线框
const LINES = [
  [M.x + 30, M.y + 24, M.w - 60], // 虚线框里那一行
  [L, 708, 400],
  [L, 788, 268],
];
const PAPER_EDGE = 5, TAPE_EDGE = 5; // 截图底边、胶带上下沿那道边的宽度

const rrPath = (x, y, w, h, r) =>
  `M${x + r} ${y}H${x + w - r}A${r} ${r} 0 0 1 ${x + w} ${y + r}V${y + h - r}A${r} ${r} 0 0 1 ${x + w - r} ${y + h}` +
  `H${x + r}A${r} ${r} 0 0 1 ${x} ${y + h - r}V${y + r}A${r} ${r} 0 0 1 ${x + r} ${y}Z`;
const linePath = ([x, y, w]) => rrPath(x, y, w, 44, 22);
const titlePath = rrPath(L, C.y + 64, 208, 44, 22);

// 圆角虚线框：四个角是带腿的圆弧，竖边只留一道缺口，横边上的短划均匀排开。
// tab 是左上角那条腿的长度，标签压在这一段实线上，标签底下就不会露出一截截的虚线
function marqueePath({ x, y, w, h, r }, dash, gap, tab) {
  const legV = (h - 2 * r - gap) / 2, legH = dash / 2;
  const d = [
    `M${x} ${f(y + r + legV)}V${y + r}A${r} ${r} 0 0 1 ${x + r} ${y}H${f(x + r + tab)}`,
    `M${f(x + w - r - legH)} ${y}H${x + w - r}A${r} ${r} 0 0 1 ${x + w} ${y + r}V${f(y + r + legV)}`,
    `M${x + w} ${f(y + h - r - legV)}V${y + h - r}A${r} ${r} 0 0 1 ${x + w - r} ${y + h}H${f(x + w - r - legH)}`,
    `M${f(x + r + legH)} ${y + h}H${x + r}A${r} ${r} 0 0 1 ${x} ${y + h - r}V${f(y + h - r - legV)}`,
  ];
  // 横边上两条腿之间：n 段短划，n+1 道缺口，短划长度微调到正好排满
  const run = (x0, x1, yy) => {
    const len = x1 - x0, n = Math.max(1, Math.round((len - gap) / (dash + gap)));
    const seg = (len - (n + 1) * gap) / n;
    for (let i = 0; i < n; i++) d.push(`M${f(x0 + gap + i * (seg + gap))} ${yy}h${f(seg)}`);
  };
  run(x + r + tab, x + w - r - legH, y);
  run(x + r + legH, x + w - r - legH, y + h);
  return d.join('');
}
const TAG_W = 108; // 标签宽，从虚线框外沿算起
const DASH = 30, DASH_GAP = 17;
const marquee = marqueePath(M, DASH, DASH_GAP, TAG_W - M.r);

// 同一个虚线框，每一段描成闭合的轮廓：主题图标只有一种颜色，虚线框要在截图上挖出来，挖洞得用闭合形状。
// 左上角那段和标签连成一块，免得两块洞叠在一起（奇偶填充下叠两次就又填上了）
function marqueeHoles({ x, y, w, h: H, r }, dash, gap, tab, sw) {
  const k = sw / 2, ro = r + k, ri = r - k;
  const legV = (H - 2 * r - gap) / 2, legH = dash / 2;
  const d = [
    `M${x - k} ${f(y + r + legV)}V${y - 30}a10 10 0 0 1 10 -10H${x - k + TAG_W - 10}a10 10 0 0 1 10 10V${y - k}` +
      `H${f(x + r + tab)}V${y + k}H${x + r}A${ri} ${ri} 0 0 0 ${x + k} ${y + r}V${f(y + r + legV)}Z`,
    `M${f(x + w - r - legH)} ${y - k}H${x + w - r}A${ro} ${ro} 0 0 1 ${x + w + k} ${y + r}V${f(y + r + legV)}` +
      `H${x + w - k}V${y + r}A${ri} ${ri} 0 0 0 ${x + w - r} ${y + k}H${f(x + w - r - legH)}Z`,
    `M${x + w + k} ${f(y + H - r - legV)}V${y + H - r}A${ro} ${ro} 0 0 1 ${x + w - r} ${y + H + k}H${f(x + w - r - legH)}` +
      `V${y + H - k}H${x + w - r}A${ri} ${ri} 0 0 0 ${x + w - k} ${y + H - r}V${f(y + H - r - legV)}Z`,
    `M${f(x + r + legH)} ${y + H + k}H${x + r}A${ro} ${ro} 0 0 1 ${x - k} ${y + H - r}V${f(y + H - r - legV)}` +
      `H${x + k}V${y + H - r}A${ri} ${ri} 0 0 0 ${x + r} ${y + H - k}H${f(x + r + legH)}Z`,
  ];
  const run = (x0, x1, yy) => {
    const len = x1 - x0, n = Math.max(1, Math.round((len - gap) / (dash + gap)));
    const seg = (len - (n + 1) * gap) / n;
    for (let i = 0; i < n; i++) d.push(`M${f(x0 + gap + i * (seg + gap))} ${yy - k}h${f(seg)}v${sw}h${f(-seg)}Z`);
  };
  run(x + r + tab, x + w - r - legH, y);
  run(x + r + legH, x + w - r - legH, y + H);
  return d.join('');
}
// 标签：左下角顺着虚线框内侧的圆角收进去，和框连成一块
const half = M.sw / 2;
const tagPath =
  `M${M.x - half} ${M.y + M.r}V${M.y - 30}a10 10 0 0 1 10 -10H${M.x - half + TAG_W - 10}a10 10 0 0 1 10 10` +
  `V${M.y + half}H${M.x + M.r}A${M.r - half} ${M.r - half} 0 0 0 ${M.x + half} ${M.y + M.r}Z`;

// 胶带：两头撕口是不太规则的锯齿，固定种子，每次生成都一样
function tapePath(x0, y0, w, h, teeth, depth, seed = 7) {
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
const tape = tapePath(T.x, T.y, T.w, T.h, 7, 15);

// ── 画面：一棵很小的场景树，SVG 和 Android 矢量图各自照着输出 ───────────────────────────
// path: { d, fill, rule, stroke, width, shadow, note }；group: { children, rotate, scale, translate, clip, note }
// fill 是颜色（可带透明度 [颜色, 0~1]）或线性渐变 { from: [x, y], to: [x, y], stops: [[位置, 颜色, 透明度]] }
const paper = {
  d: rrPath(C.x, C.y, C.w, C.h, C.r), note: '截图',
  fill: { from: [0, C.y], to: [0, C.y + C.h], stops: [[0, '#FFFFFF'], [1 - PAPER_EDGE / C.h, '#FFFFFF'], [1, '#DDE3EB']] },
  shadow: { svg: [3, 4, '#000000', 0.3] },
};
const flagged = [
  { d: tagPath, fill: AMBER, note: '琥珀虚线框和类型标签：识别到了，等你确认' },
  { d: marquee, stroke: AMBER, width: M.sw },
];
const lines = [
  { d: titlePath, fill: TITLE },
  { d: LINES.map(linePath).join(''), fill: LINE },
];
const tapeGroup = {
  note: '天蓝胶带：已经打上的码', rotate: [T.rot, ...PIVOT],
  children: [
    // 贴边的投影。Android 矢量图没有模糊，用两层错开一点的半透明胶带代替
    { d: tape, fill: SKY, shadow: { svg: [3, 3, '#0A3A5C', 0.28], vector: [[2, 0.16], [4, 0.1]], color: '#0A3A5C' } },
    {
      d: tape,
      fill: {
        from: [0, T.y], to: [0, T.y + T.h], stops: [
          [0, '#FFFFFF', 0.45], [TAPE_EDGE / T.h, '#FFFFFF', 0],
          [1 - TAPE_EDGE / T.h, '#1C76A8', 0], [1, '#1C76A8', 0.35],
        ],
      },
    },
  ],
};
const background = { d: 'M-256 -256H1280V1280H-256Z', fill: { from: [0, 0], to: [0, 1024], stops: [[0, BG[0]], [1, BG[1]]] } };
const screenshot = [paper, ...lines.slice(0, 1), ...flagged, ...lines.slice(1)];
const foreground = [...screenshot, tapeGroup];

// 主题图标：系统只看形状、自己上色，所以截图挖掉几行字和虚线框，胶带两边各让出一道缝，整体缩小一点。
// 虚线框比彩色版粗一点（16 对 12），缩到桌面图标那么小还看得出一段一段
const GAP = 26, MONO_DASH_WIDTH = 16;
const holes = rrPath(C.x, C.y, C.w, C.h, C.r) + titlePath + LINES.map(linePath).join('') +
  marqueeHoles(M, DASH, DASH_GAP, TAG_W - M.r, MONO_DASH_WIDTH);
const half_ = (clip) => ({
  rotate: [T.rot, ...PIVOT], clip,
  children: [{ rotate: [-T.rot, ...PIVOT], children: [{ d: holes, fill: '#FFFFFF', rule: 'evenodd' }] }],
});
const monochrome = [{
  scale: [0.8, 512, 512], note: '截图分成胶带上下两块，中间留缝，胶带压在缝里',
  children: [
    half_(`M-1024 -1024H2048V${T.y - GAP}H-1024Z`),
    half_(`M-1024 ${T.y + T.h + GAP}H2048V2048H-1024Z`),
    { rotate: [T.rot, ...PIVOT], children: [{ d: tape, fill: '#FFFFFF' }] },
  ],
}];

// ── 输出：SVG ────────────────────────────────────────────────────────────────────────────
function toSvg(nodes, { shadows = true, clip } = {}) {
  const defs = [];
  let id = 0;
  const color = (c) => (Array.isArray(c) ? [c[0], c[1]] : [c, 1]);
  const paint = (p) => {
    if (typeof p === 'string' || Array.isArray(p)) {
      const [c, a] = color(p);
      return `fill="${c}"${a === 1 ? '' : ` fill-opacity="${a}"`}`;
    }
    const gid = `g${id++}`;
    defs.push(`    <linearGradient id="${gid}" x1="${p.from[0]}" y1="${p.from[1]}" x2="${p.to[0]}" y2="${p.to[1]}" gradientUnits="userSpaceOnUse">\n` +
      p.stops.map(([o, c, a = 1]) => `      <stop offset="${off(o)}" stop-color="${c}"${a === 1 ? '' : ` stop-opacity="${a}"`}/>`).join('\n') +
      `\n    </linearGradient>`);
    return `fill="url(#${gid})"`;
  };
  // 不用 SVG 2 才有的 feDropShadow，拆成 SVG 1.1 的模糊、位移、上色三步，旧一点的渲染器也认
  const shadow = ([dy, blur, c, a]) => {
    const sid = `s${id++}`;
    defs.push(`    <filter id="${sid}" x="-20%" y="-20%" width="140%" height="140%" color-interpolation-filters="sRGB">\n` +
      `      <feGaussianBlur in="SourceAlpha" stdDeviation="${blur}"/>\n      <feOffset dy="${dy}" result="offset"/>\n` +
      `      <feFlood flood-color="${c}" flood-opacity="${a}"/>\n      <feComposite in2="offset" operator="in"/>\n` +
      `      <feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge>\n    </filter>`);
    return ` filter="url(#${sid})"`;
  };
  const walk = (n, ind) => {
    const note = n.note ? `${ind}<!-- ${n.note} -->\n` : '';
    if (n.children) {
      const t = [];
      if (n.scale) t.push(`translate(${n.scale[1]} ${n.scale[2]}) scale(${n.scale[0]}) translate(${-n.scale[1]} ${-n.scale[2]})`);
      if (n.rotate) t.push(`rotate(${n.rotate.join(' ')})`);
      let attrs = t.length ? ` transform="${t.join(' ')}"` : '';
      if (n.clip) {
        const cid = `c${id++}`;
        defs.push(`    <clipPath id="${cid}"><path d="${n.clip}"/></clipPath>`);
        attrs += ` clip-path="url(#${cid})"`;
      }
      return `${note}${ind}<g${attrs}>\n${n.children.map((c) => walk(c, ind + '  ')).join('')}${ind}</g>\n`;
    }
    const fill = n.stroke ? `fill="none" stroke="${n.stroke}" stroke-width="${n.width}"` : paint(n.fill);
    const rule = n.rule ? ` fill-rule="${n.rule}"` : '';
    const sh = shadows && n.shadow ? shadow(n.shadow.svg) : '';
    return `${note}${ind}<path d="${n.d}" ${fill}${rule}${sh}/>\n`;
  };
  let body = nodes.map((n) => walk(n, '  ')).join('');
  if (clip) {
    defs.push(`    <clipPath id="shape"><path d="${clip}"/></clipPath>`);
    body = `  <g clip-path="url(#shape)">\n${body.replace(/^(?=.)/gm, '  ')}  </g>\n`;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">\n` +
    `  <title>有码 Yomark</title>\n  <defs>\n${defs.join('\n')}\n  </defs>\n${body}</svg>\n`;
}

// ── 输出：Android 矢量图（108 dp 画布，母版居中，四周各让出 256） ────────────────────────
function toVector(nodes) {
  let gradients = false;
  const hex = (c, a = 1) => `#${Math.round(a * 255).toString(16).padStart(2, '0').toUpperCase()}${c.slice(1).toUpperCase()}`;
  const walk = (n, ind) => {
    const note = n.note ? `${ind}<!-- ${n.note} -->\n` : '';
    if (n.children) {
      const a = [];
      if (n.scale) a.push(`android:scaleX="${n.scale[0]}"`, `android:scaleY="${n.scale[0]}"`, `android:pivotX="${n.scale[1]}"`, `android:pivotY="${n.scale[2]}"`);
      if (n.rotate) a.push(`android:rotation="${n.rotate[0]}"`, `android:pivotX="${n.rotate[1]}"`, `android:pivotY="${n.rotate[2]}"`);
      const clip = n.clip ? `${ind}    <clip-path android:pathData="${n.clip}" />\n` : '';
      return `${note}${ind}<group ${a.join(' ')}>\n${clip}${n.children.map((c) => walk(c, ind + '    ')).join('')}${ind}</group>\n`;
    }
    let out = '';
    // 投影：几层错开一点的半透明副本，垫在下面
    if (n.shadow?.vector) {
      for (const [dy, alpha] of [...n.shadow.vector].reverse()) {
        out += `${ind}<group android:translateY="${dy}">\n${ind}    <path android:fillColor="${hex(n.shadow.color, alpha)}" android:pathData="${n.d}" />\n${ind}</group>\n`;
      }
    }
    const rule = n.rule ? ` android:fillType="${n.rule === 'evenodd' ? 'evenOdd' : 'nonZero'}"` : '';
    if (n.stroke) {
      return note + out + `${ind}<path android:strokeColor="${hex(n.stroke)}" android:strokeWidth="${n.width}" android:pathData="${n.d}" />\n`;
    }
    if (typeof n.fill === 'string' || Array.isArray(n.fill)) {
      const [c, a] = Array.isArray(n.fill) ? n.fill : [n.fill, 1];
      return note + out + `${ind}<path android:fillColor="${hex(c, a)}"${rule} android:pathData="${n.d}" />\n`;
    }
    gradients = true;
    const g = n.fill;
    return note + out + `${ind}<path${rule} android:pathData="${n.d}">\n` +
      `${ind}    <aapt:attr name="android:fillColor">\n` +
      `${ind}        <gradient android:type="linear" android:startX="${g.from[0]}" android:startY="${g.from[1]}" android:endX="${g.to[0]}" android:endY="${g.to[1]}">\n` +
      g.stops.map(([o, c, a = 1]) => `${ind}            <item android:offset="${off(o)}" android:color="${hex(c, a)}" />\n`).join('') +
      `${ind}        </gradient>\n${ind}    </aapt:attr>\n${ind}</path>\n`;
  };
  const body = nodes.map((n) => walk(n, '        ')).join('');
  return `<?xml version="1.0" encoding="utf-8"?>\n` +
    `<!-- 由 docs/icon/src/icons.mjs 生成，改图标去改那里再重新生成 -->\n` +
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"` +
    (gradients ? `\n    xmlns:aapt="http://schemas.android.com/aapt"` : '') +
    `\n    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="1536"\n    android:viewportHeight="1536">\n` +
    `    <group android:translateX="256" android:translateY="256">\n${body}    </group>\n</vector>\n`;
}

// ── 写文件 ───────────────────────────────────────────────────────────────────────────────
const write = (rel, s) => {
  const file = ROOT + rel;
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, s);
  console.log(rel);
};
const page = { d: 'M0 0H1024V1024H0Z', fill: background.fill };
write('docs/icon/icon.svg', toSvg([page, ...foreground]));
// 网页图标：圆角方形（圆角 25%，同原来的 favicon），太小了看不出投影，不带
write('site/favicon.svg', toSvg([page, ...foreground], { shadows: false, clip: rrPath(0, 0, 1024, 1024, 256) }));
// 自适应图标只有前后两层，截图放进底板那层，前景只留胶带：启动器的视差和回桌面的动画里胶带单独动，像是贴上去的
write('app/src/main/res/drawable/ic_launcher_background.xml', toVector([background, ...screenshot]));
write('app/src/main/res/drawable/ic_launcher_foreground.xml', toVector([tapeGroup]));
write('app/src/main/res/drawable/ic_launcher_monochrome.xml', toVector(monochrome));
// 主题图标的预览，只给人看，不进应用
write('docs/icon/icon-monochrome.svg', toSvg([{ d: 'M0 0H1024V1024H0Z', fill: '#2B2E3A' }, ...monochrome]));
