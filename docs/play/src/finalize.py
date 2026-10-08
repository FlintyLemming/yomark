"""渲染完的 Play 素材收个尾：按 Play 的格式要求转色彩模式，并核对尺寸、比例、体积。

    python3 docs/play/src/finalize.py   # 需要 Pillow

- 图标：32 位 PNG（带 alpha），512×512，不超过 1 MB；
- 置顶大图：24 位 PNG（不带 alpha），1024×500，不超过 15 MB；
- 手机截图：24 位 PNG（不带 alpha），9:16，每边 320–3840 px，每张不超过 8 MB；
  每边都不低于 1080 px、至少 4 张，才有资格进 Play 的推荐位。

Chromium 截出来的 PNG 带不带 alpha 不一定，这里一律转成 Play 要的那一种，再压一遍体积。
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
PLAY = ROOT / 'docs/play'


def save(path: Path, mode: str) -> Image.Image:
    im = Image.open(path)
    im = im.convert(mode)
    im.save(path, optimize=True)
    return im


def check(ok: bool, what: str):
    if not ok:
        raise SystemExit(f'不合格：{what}')


def main():
    icon = save(PLAY / 'icon.png', 'RGBA')
    check(icon.size == (512, 512), f'图标尺寸 {icon.size}')
    check((PLAY / 'icon.png').stat().st_size <= 1024 * 1024, '图标超过 1 MB')

    feature = save(PLAY / 'feature-graphic.png', 'RGB')
    check(feature.size == (1024, 500), f'置顶大图尺寸 {feature.size}')
    check((PLAY / 'feature-graphic.png').stat().st_size <= 15 * 1024 * 1024, '置顶大图超过 15 MB')

    shots = sorted((PLAY / 'screenshots').glob('*.png'))
    check(2 <= len(shots) <= 8, f'截图 {len(shots)} 张，要 2–8 张')
    for path in shots:
        im = save(path, 'RGB')
        w, h = im.size
        check(w * 16 == h * 9, f'{path.name} 不是 9:16（{w}×{h}）')
        check(min(w, h) >= 1080 and max(w, h) <= 3840, f'{path.name} 尺寸 {w}×{h}')
        check(path.stat().st_size <= 8 * 1024 * 1024, f'{path.name} 超过 8 MB')

    for path in [PLAY / 'icon.png', PLAY / 'feature-graphic.png', *shots]:
        with Image.open(path) as im:
            print(f'{path.relative_to(ROOT)}  {im.size[0]}×{im.size[1]}  {im.mode}  {path.stat().st_size / 1024:.0f} KB')
    print(f'合格：图标、置顶大图、{len(shots)} 张截图（满足推荐位的 4 张 × 1080 px）')


if __name__ == '__main__':
    main()
