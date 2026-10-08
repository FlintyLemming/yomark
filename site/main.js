// 有码落地页的脚本：首屏演示的扫光与落码、点标记切换，以及把下载按钮换成最新版本的 APK 直链。
// 不依赖任何库，也不请求本站以外的地址。
(() => {
  'use strict';

  // ---------- 最新版本 ----------
  // release.json 由 .github/workflows/pages.yml 在部署时写入：{ tag, published, apk: { url, size } }。
  // 本地直接打开、或者还没发过版时没有这个文件，下载按钮就停在 Releases 页面。
  fetch('release.json', { cache: 'no-cache' })
    .then((res) => (res.ok ? res.json() : null))
    .then((rel) => {
      if (!rel || !rel.apk || !rel.apk.url) return;
      for (const a of document.querySelectorAll('[data-apk]')) a.href = rel.apk.url;
      const size = rel.apk.size ? ` · ${Math.round(rel.apk.size / 1e6)} MB` : '';
      for (const el of document.querySelectorAll('[data-release]')) el.textContent = `${rel.tag}${size} · `;
    })
    .catch(() => {});

  // ---------- 首屏演示 ----------
  const demo = document.querySelector('[data-demo]');
  if (!demo) return;

  const shot = demo.querySelector('[data-shot]');
  const chip = demo.querySelector('[data-chip]');
  const replay = demo.querySelector('[data-replay]');
  const maskedCount = demo.querySelector('[data-count="masked"]');
  const outlinedCount = demo.querySelector('[data-count="outlined"]');
  const marks = Array.from(demo.querySelectorAll('.m'));
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');

  // 节奏和光的形状照编辑器里的扫光（app/src/main/java/moe/flinty/yomark/ui/canvas/ScanEffect.kt），
  // 识别那一趟比应用里短一点，网页上没人等得了两秒。
  const PASS_MS = 1700; // 识别中，光从顶扫到底一趟
  const REVEAL_MS = 1400; // 落码那一遍
  const TRAIL = 140; // 光的中心往上拖的长尾（px），与 style.css 里 .shot 的 --trail 一致
  const LEAD = 36; // 往下的前沿（px），与 --lead 一致
  const FEATHER = 110; // 码块从开始显现到盖实，光要走过的距离（px）
  const MASK_INSET = 5; // 色块的上沿在按钮里的位置，见 style.css 的 .m::after

  const CHIP = { idle: '原图', scanning: '识别中…', done: '识别后' };
  let phase = 'done';
  let raf = 0;

  const ease = (p) => 0.5 - Math.cos(Math.PI * Math.min(Math.max(p, 0), 1)) / 2;
  const clamp01 = (v) => Math.min(Math.max(v, 0), 1);

  function setMark(m, state) {
    m.dataset.state = state;
    m.setAttribute('aria-pressed', String(state === 'masked'));
  }

  function updateCounts() {
    const masked = marks.filter((m) => m.dataset.state === 'masked').length;
    maskedCount.textContent = masked;
    outlinedCount.textContent = marks.length - masked;
  }

  function setPhase(next) {
    phase = next;
    demo.classList.toggle('is-raw', next === 'idle');
    demo.classList.toggle('is-scanning', next === 'scanning');
    chip.textContent = CHIP[next];
  }

  function finish() {
    cancelAnimationFrame(raf);
    for (const m of marks) m.style.removeProperty('--a');
    setPhase('done');
  }

  // 先扫一趟（识别中），再扫一趟落码：暗幕跟着光揭开，码块在光后面从半透明盖实
  function play() {
    cancelAnimationFrame(raf);
    for (const m of marks) {
      setMark(m, m.dataset.initial);
      m.style.setProperty('--a', '0');
    }
    updateCounts();
    if (reduceMotion.matches) {
      finish();
      return;
    }
    shot.style.setProperty('--lift', '0px');
    setPhase('scanning');

    const top = shot.getBoundingClientRect().top;
    const tops = marks.map((m) => m.getBoundingClientRect().top - top + MASK_INSET);
    const span = LEAD + shot.clientHeight + TRAIL; // 整道光从完全藏在上方走到完全离开下方
    const start = performance.now();

    const frame = (now) => {
      const t = now - start;
      if (t >= PASS_MS + REVEAL_MS) {
        finish();
        return;
      }
      const revealing = t >= PASS_MS;
      const y = -LEAD + ease(revealing ? (t - PASS_MS) / REVEAL_MS : t / PASS_MS) * span;
      shot.style.setProperty('--beam', `${y}px`);
      if (revealing) {
        shot.style.setProperty('--lift', `${Math.max(0, y)}px`);
        marks.forEach((m, i) => m.style.setProperty('--a', String(clamp01((y - tops[i]) / FEATHER))));
      }
      raf = requestAnimationFrame(frame);
    };
    raf = requestAnimationFrame(frame);
  }

  for (const m of marks) {
    m.dataset.initial = m.dataset.state;
    m.setAttribute('aria-label', `打码：${m.dataset.kind}`);
    setMark(m, m.dataset.state);
    m.addEventListener('click', () => {
      // 动画还没放完时点一下，直接跳到识别后的样子
      if (phase !== 'done') {
        finish();
        return;
      }
      setMark(m, m.dataset.state === 'masked' ? 'outlined' : 'masked');
      updateCounts();
    });
  }
  updateCounts();

  replay.hidden = false;
  replay.addEventListener('click', play);

  // 演示进入视野后再放，滚到别处打开页面时不会白放一遍
  if (!reduceMotion.matches && 'IntersectionObserver' in window) {
    setPhase('idle');
    const io = new IntersectionObserver(
      (entries) => {
        if (!entries.some((e) => e.isIntersecting)) return;
        io.disconnect();
        setTimeout(() => {
          if (phase === 'idle') play();
        }, 500);
      },
      { threshold: 0.4 },
    );
    io.observe(shot);
  }
})();
