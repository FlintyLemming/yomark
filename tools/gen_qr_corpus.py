"""生成 M3 二维码评测语料（自造数据）。

计划 05 Task 33 Step 4 允许的素材来源之一就是「自己生成的二维码」。
这里刻意做出多样性：尺寸、纠错级别、旋转、对比度、周边杂物、缩放模糊，
让「二维码 100%」这个指标不是在一张理想图上刷出来的。

跑法（需要 segno 与 Pillow，只是开发机工具，不进 app 依赖）：

    python3 -m venv /tmp/qrvenv && /tmp/qrvenv/bin/pip install segno Pillow
    /tmp/qrvenv/bin/python tools/gen_qr_corpus.py

输出到 app/src/androidTest/assets/qrcodes/，随机种子写死，重跑得到同一批图。

**这批合成图替代不了真实收款码截图**——它没有屏幕摩尔纹、没有反光、
没有深色模式下的反色二维码。真实素材按 docs/eval-sample-set.md 的隐私要求采集。
"""

import io, math, random, os
import segno
from PIL import Image, ImageDraw, ImageFilter, ImageFont

random.seed(20260903)
OUT = "app/src/androidTest/assets/qrcodes"
os.makedirs(OUT, exist_ok=True)

PAYLOADS = [
    "https://example.com/pay/ORDER-{}",
    "WIFI:S=guest-{};T=WPA;P=hunter2;;",
    "BEGIN:VCARD\nVERSION:3.0\nN:Doe;Jane\nTEL:+1-555-01{:02d}\nEND:VCARD",
    "{}",
    "https://example.org/r/{}",
]

def qr_png(text, err, scale, border, dark, light):
    buf = io.BytesIO()
    segno.make(text, error=err).save(buf, kind="png", scale=scale, border=border, dark=dark, light=light)
    buf.seek(0)
    return Image.open(buf).convert("RGB")

def clutter(draw, w, h, font):
    for _ in range(random.randint(1, 4)):
        x, y = random.randint(0, w - 60), random.randint(0, h - 20)
        draw.text((x, y), random.choice(["订单号 88213", "Scan to pay", "amount 12.50",
                                         "收款码", "ref 4429-118", "Table 7"]),
                  fill=(random.randint(0, 90),) * 3, font=font)

def make(i):
    err = random.choice("lmqh")
    scale = random.choice([4, 5, 6, 8, 10])
    border = random.choice([2, 3, 4, 6])
    payload = random.choice(PAYLOADS).format(random.randint(1000, 99999))
    dark = random.choice(["black", "#101820", "#1b3a5c", "#2d2d2d"])
    light = random.choice(["white", "#f4f4f0", "#eef3ff"])
    qr = qr_png(payload, err, scale, border, dark, light)

    bg_shade = random.randint(225, 255)
    pad = random.randint(30, 140)
    cw, ch = qr.width + pad * 2, qr.height + pad * 2
    canvas = Image.new("RGB", (cw, ch), (bg_shade, bg_shade, bg_shade))
    angle = random.choice([0, 0, 0, 7, -11, 20, -25, 33])
    layer = qr.rotate(angle, expand=True, resample=Image.BICUBIC, fillcolor=(bg_shade, bg_shade, bg_shade))
    canvas.paste(layer, ((cw - layer.width) // 2, (ch - layer.height) // 2))

    draw = ImageDraw.Draw(canvas)
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 18)
    except OSError:
        font = ImageFont.load_default()
    clutter(draw, cw, ch, font)

    if random.random() < 0.3:
        canvas = canvas.filter(ImageFilter.GaussianBlur(radius=random.uniform(0.4, 0.9)))
    if random.random() < 0.35:
        f = random.uniform(0.55, 0.85)
        canvas = canvas.resize((int(cw * f), int(ch * f)), Image.LANCZOS)

    name = f"{OUT}/qr-{i:02d}-{err}-r{angle}.jpg" if random.random() < 0.5 else f"{OUT}/qr-{i:02d}-{err}-r{angle}.png"
    if name.endswith(".jpg"):
        canvas.save(name, quality=random.randint(70, 92))
    else:
        canvas.save(name)
    return name

names = [make(i) for i in range(1, 37)]
print(len(names), "images")
