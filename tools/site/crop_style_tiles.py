"""把 README 的样式对照图 docs/readme/styles.png 裁成六张小图，给落地页（site/）用。

    python3 tools/site/crop_style_tiles.py   # 需要 Pillow

小图是 ReadmeScreenshots 在 Robolectric 上拍的编辑器截图，即应用的真实渲染结果。
重拍 styles.png（见 docs/readme/src/render.cjs）之后再跑一遍这个脚本。
裁切位置照 docs/readme/src/styles.html 的排版算：改了那边的布局，这里的数也要跟着改。
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'docs/readme/styles.png'
OUT = ROOT / 'site/img'

SCALE = 2                    # styles.png 按 2 倍渲染
K = 0.527                    # styles.html 的 --k
TILE_W, TILE_H = 1040 * K, 230 * K
CAPTION = 26 + 10            # 小标题高度 + 下边距
ROW_GAP, COL_GAP = 30, 56
LEFT, TOP = 64, 54           # body 的 padding
INSET = 4                    # 往里收几个像素，去掉卡片的描边和抗锯齿

# 网格按列排：左列色块、表情、抹除，右列马赛克、模糊、马克笔
COLUMNS = [['solid', 'emoji', 'erase'], ['pixelate', 'blur', 'marker']]


def main():
    sheet = Image.open(SRC).convert('RGB')
    OUT.mkdir(parents=True, exist_ok=True)
    for col, names in enumerate(COLUMNS):
        x = LEFT + col * (TILE_W + COL_GAP)
        for row, name in enumerate(names):
            y = TOP + CAPTION + row * (CAPTION + TILE_H + ROW_GAP)
            box = (
                int(x * SCALE) + INSET,
                int(y * SCALE) + INSET,
                int((x + TILE_W) * SCALE) - INSET + 1,
                int((y + TILE_H) * SCALE) - INSET + 1,
            )
            out = OUT / f'style-{name}.webp'
            sheet.crop(box).save(out, 'WEBP', quality=88, method=6)
            print(out.relative_to(ROOT), box)


if __name__ == '__main__':
    main()
