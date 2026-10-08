"""疑似条码像素判断（app 里的 TwoInks）的离线评测：阈值是在这里定的，改阈值前先跑一遍。

一边是**必须保留**的码——丢了就是漏打码：
  - androidTest 的 36 张二维码评测图；
  - 合成的二维码 / DataMatrix / Aztec / PDF417 / Code128 / EAN-13，加模糊、缩小、旋转、透视、
    JPEG、反色、彩色墨、带 logo、光照不匀，只留 zxing-cpp 或微信扫码引擎读得出的；
  - 清晰的小码再按分析图的 2×/4× 降采样（导出的是原图，所以这些一律必须保留）；
  - 暗模块渐变色的码、带彩色摩尔纹的码（拍屏幕），同样只留读得出的。
另一边是照片：scikit-image 自带的彩色样图（CC0 / 公有领域）里随机取的窗口，
以及 --photos 指定目录里的图（整张缩到 200 像素宽当一个框，模拟截图里的缩略图）。

判定逐行对应 app/src/main/java/moe/flinty/yomark/engine/mlkit/TwoInks.kt：像素中心落在四边形里、
又不在中心 logo 区里才算，整数亮度、256 档 Otsu、两类逐通道中位色连线、离线距离与占比、η 后路。
阈值改了两边一起改。

跑法（开发机工具，不进 app 依赖）：

    python3 -m venv /tmp/twoinks && /tmp/twoinks/bin/pip install numpy pillow "opencv-contrib-python-headless<5" zxing-cpp scikit-image
    /tmp/twoinks/bin/python tools/barcode-eval/two_inks_eval.py [--photos 目录] [--wechat 模型目录]

--wechat 指向微信扫码引擎的四个模型文件（detect/sr 的 prototxt 与 caffemodel，
见 github.com/WeChatCV/opencv_3rdparty 的 wechat_qrcode 分支）。不给就只用 zxing-cpp 判「读得出」，
必须保留的集合会小一些（微信引擎读得出、zxing 读不出的码进不来）。

随机种子写死，重跑得到同一批样本。
"""
import argparse
import glob
import io
import os
import random

import cv2
import numpy as np
import zxingcpp
from PIL import Image

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
F = zxingcpp.BarcodeFormat

# ---- 与 TwoInks.kt 一致 -------------------------------------------------------------------------
MIN_PIXELS = 64
MIN_SEPARATION = 32.0
OFF_LINE_DISTANCE = 0.15
MAX_OFF_LINE_SHARE = 0.15
TWO_TONE_ETA = 0.80
LOGO_ZONE = 0.4


def inside(h, w, quad):
    ys, xs = np.mgrid[0:h, 0:w]
    px, py = xs + 0.5, ys + 0.5
    mask = np.zeros((h, w), bool)
    q = np.asarray(quad, np.float64)
    j = 3
    for i in range(4):
        (ax, ay), (bx, by) = q[i], q[j]
        cross = (ay > py) != (by > py)
        with np.errstate(divide="ignore", invalid="ignore"):
            xint = (bx - ax) * (py - ay) / (by - ay) + ax
        mask ^= cross & (px < xint)
        j = i
    return mask


def sample(rgb, quad):
    """四边形里、中心 logo 区以外的像素。"""
    quad = np.asarray(quad, np.float64)
    c = quad.mean(0)
    h, w = rgb.shape[:2]
    return rgb[inside(h, w, quad) & ~inside(h, w, c + (quad - c) * LOGO_ZONE)]


def lower_median(a):
    return np.sort(a, axis=0)[(len(a) - 1) // 2].astype(np.float64)


def measure(rgb, quad):
    px = sample(rgb, quad).astype(np.int64)
    n = len(px)
    if n < MIN_PIXELS:
        return None
    lum = (px[:, 0] * 299 + px[:, 1] * 587 + px[:, 2] * 114 + 500) // 1000
    hist = np.bincount(lum, minlength=256).astype(np.float64)
    levels = np.arange(256)
    w0 = np.cumsum(hist)
    s0 = np.cumsum(hist * levels)
    w1 = n - w0
    with np.errstate(divide="ignore", invalid="ignore"):
        between = w0 * w1 * (s0 / w0 - (s0[-1] - s0) / w1) ** 2
    between[(w0 == 0) | (w1 == 0)] = -1
    t = int(np.argmax(between))
    if between[t] < 0:
        return dict(eta=0.0, sep=0.0, off=0.0)
    var_total = (hist * (levels - s0[-1] / n) ** 2).sum()
    eta = between[t] / n / var_total if var_total > 0 else 0.0
    dark = lum <= t
    ink, paper = lower_median(px[dark]), lower_median(px[~dark])
    d = paper - ink
    sep = float(np.sqrt((d * d).sum()))
    if sep < MIN_SEPARATION:
        return dict(eta=float(eta), sep=sep, off=0.0)
    v = px - ink
    along = v @ (d / sep)
    perp2 = (v * v).sum(1) - along * along
    off = float((perp2 > (OFF_LINE_DISTANCE * sep) ** 2).mean())
    return dict(eta=float(eta), sep=sep, off=off)


def looks_printed(m):
    if m is None or m["sep"] < MIN_SEPARATION:
        return True
    return not (m["off"] >= MAX_OFF_LINE_SHARE and m["eta"] < TWO_TONE_ETA)


# ---- 读得出吗 -----------------------------------------------------------------------------------
_wechat = None


def readable(rgb, quad):
    x0, y0 = np.floor(quad.min(0)).astype(int)
    x1, y1 = np.ceil(quad.max(0)).astype(int)
    m = int(0.3 * max(x1 - x0, y1 - y0)) + 8
    crop = rgb[max(0, y0 - m):y1 + m, max(0, x0 - m):x1 + m]
    if zxingcpp.read_barcodes(crop):
        return True
    if zxingcpp.read_barcodes(cv2.resize(crop, None, fx=3, fy=3, interpolation=cv2.INTER_CUBIC)):
        return True
    if _wechat is not None:
        found, _ = _wechat.detectAndDecode(cv2.cvtColor(crop, cv2.COLOR_RGB2BGR))
        return bool(found)
    return False


# ---- 必须保留的码 -------------------------------------------------------------------------------
def jpeg(rgb, q):
    buf = io.BytesIO()
    Image.fromarray(rgb).save(buf, "JPEG", quality=q)
    return np.array(Image.open(io.BytesIO(buf.getvalue())).convert("RGB"))


TEXT = {
    F.QRCode: lambda r: f"https://example.com/pay/{r.randint(10**6, 10**9)}?u={r.randint(1, 99999)}",
    F.DataMatrix: lambda r: f"SN{r.randint(10**8, 10**9)}-{r.randint(100, 999)}",
    F.Aztec: lambda r: f"M1DOE/JANE E{r.randint(100000, 999999)} PEKSHAMU 1234",
    F.PDF417: lambda r: f"M1DOE/JANE EABC123 PEKSHAMU 1234 {r.randint(100, 999)}Y012A0001 100",
    F.Code128: lambda r: f"SF{r.randint(10**11, 10**12)}",
    F.EAN13: lambda r: str(r.randint(10**11, 10**12 - 1)),
}
LINEAR = {F.Code128, F.EAN13}
FORMATS = [F.QRCode, F.QRCode, F.QRCode, F.DataMatrix, F.Aztec, F.PDF417, F.Code128, F.EAN13]


def render(r, fmt, module, rot=0.0, persp=0.0, blur=0.0, jq=None, invert=False, ink=None, logo=False, light=0.0):
    """画一个码，返回 (图, 码区四角)。先在 8 倍分辨率上画，再透视变换到目标模块尺寸。"""
    img = np.array(zxingcpp.create_barcode(TEXT[fmt](r), fmt).to_image(scale=1, add_quiet_zones=False))
    dark = (img < 128).astype(np.uint8)
    if fmt in LINEAR:
        row = dark[dark.shape[0] // 2]
        dark = np.tile(row, (max(20, int(len(row) * r.uniform(0.25, 0.6))), 1))
        big = np.kron(dark, np.ones((1, 8), np.uint8))
    else:
        big = np.kron(dark, np.ones((8, 8), np.uint8))
    bh, bw = big.shape
    ink = np.array(ink if ink is not None else (r.randint(0, 40),) * 3, np.float64)
    paper = np.array([r.randint(235, 255) for _ in range(3)], np.float64)
    if invert:
        ink, paper = paper, np.array((r.randint(10, 40),) * 3, np.float64)
    code = np.where(big[..., None] == 1, ink, paper)
    if logo and fmt not in LINEAR:
        side = int(min(bh, bw) * r.uniform(0.18, 0.28))
        cy, cx = bh // 2, bw // 2
        code[cy - side // 2 - 8:cy + side // 2 + 8, cx - side // 2 - 8:cx + side // 2 + 8] = paper
        code[cy - side // 2:cy + side // 2, cx - side // 2:cx + side // 2] = [r.randint(0, 255) for _ in range(3)]
    qz = 32
    code = cv2.copyMakeBorder(code, qz, qz, qz, qz, cv2.BORDER_CONSTANT, value=paper.tolist())
    scale = module / 8
    tw, th = bw * scale, bh * (scale if fmt not in LINEAR else 1.0)
    a = np.deg2rad(rot)
    corners = np.array([[-tw / 2, -th / 2], [tw / 2, -th / 2], [tw / 2, th / 2], [-tw / 2, th / 2]])
    corners = corners @ np.array([[np.cos(a), np.sin(a)], [-np.sin(a), np.cos(a)]])
    corners += np.array([[r.uniform(-persp, persp) for _ in range(2)] for _ in range(4)]) * max(tw, th)
    corners -= corners.min(0) - (60 + r.randint(0, 60))
    W, H = (corners.max(0) + 60 + r.randint(0, 60)).astype(int)
    src = np.array([[qz, qz], [qz + bw, qz], [qz + bw, qz + bh], [qz, qz + bh]], np.float32)
    M = cv2.getPerspectiveTransform(src, corners.astype(np.float32))
    pre = cv2.GaussianBlur(code, (0, 0), 0.45 / scale) if scale < 1 else code
    warped = cv2.warpPerspective(pre, M, (W, H), flags=cv2.INTER_LINEAR, borderValue=(-1, -1, -1))
    cover = np.clip(cv2.warpPerspective(np.ones(code.shape[:2], np.float32), M, (W, H)), 0, 1)[..., None]
    out = np.full((H, W, 3), float(r.randint(235, 255))) * (1 - cover) + np.maximum(warped, 0) * cover
    if light:
        out *= np.linspace(1 - light, 1, W)[None, :, None] * np.linspace(1, 1 - light / 2, H)[:, None, None]
    if blur:
        out = cv2.GaussianBlur(out, (0, 0), blur)
    out = np.clip(out, 0, 255).astype(np.uint8)
    return (jpeg(out, jq) if jq else out), corners


def loosen(r, q):
    """检测器报的框不会正好贴着码：整体放大 −8%…+18%，四角各抖 5%。"""
    c = q.mean(0)
    size = np.linalg.norm(q[2] - q[0])
    q = c + (q - c) * (1 + r.uniform(-0.08, 0.18))
    return q + np.array([[r.uniform(-0.05, 0.05) * size for _ in range(2)] for _ in range(4)])


def must_keep():
    out = []
    for path in sorted(glob.glob(f"{REPO}/app/src/androidTest/assets/qrcodes/*")):
        rgb = np.array(Image.open(path).convert("RGB"))
        found = zxingcpp.read_barcodes(rgb)
        if found:
            p = found[0].position
            q = np.array([[p.top_left.x, p.top_left.y], [p.top_right.x, p.top_right.y],
                          [p.bottom_right.x, p.bottom_right.y], [p.bottom_left.x, p.bottom_left.y]], float)
        else:   # 解不出的两张（被字压住）：拿整张图当框，最坏情况
            h, w = rgb.shape[:2]
            q = np.array([[0, 0], [w, 0], [w, h], [0, h]], float)
        out.append(("corpus/" + os.path.basename(path), rgb, q))

    r = random.Random(7)
    for i in range(500):
        fmt = r.choice(FORMATS)
        img, q = render(r, fmt, r.choice([1.5, 2, 2, 2.5, 3, 4, 5, 6]), rot=r.choice([0, 0, 0, r.uniform(-45, 45)]),
                        persp=r.choice([0, 0, 0, 0.04, 0.08]), blur=r.choice([0, 0, 0.5, 0.8, 1.0, 1.3]),
                        jq=r.choice([None, None, 90, 75, 55]), invert=r.random() < 0.12,
                        ink=r.choice([None] * 6 + [(16, 24, 32), (27, 58, 92), (20, 90, 40), (90, 40, 20), (60, 0, 90)]),
                        logo=r.random() < 0.25, light=r.choice([0, 0, 0, 0.25, 0.45]))
        q = loosen(r, q)
        if readable(img, q):
            out.append((f"degraded/{i:03d}-{fmt.name}", img, q))

    r = random.Random(17)
    for i in range(250):
        fmt = r.choice(FORMATS)
        img, q = render(r, fmt, r.choice([2, 3, 3, 4, 5, 6]), rot=r.choice([0, 0, 0, r.uniform(-30, 30)]),
                        invert=r.random() < 0.1, ink=r.choice([None] * 6 + [(16, 24, 32), (27, 58, 92), (20, 90, 40)]),
                        logo=r.random() < 0.25)
        k = r.choice([2, 2, 2, 4])
        small = (cv2.resize(img, (img.shape[1] // k, img.shape[0] // k), interpolation=cv2.INTER_AREA)
                 if r.random() < 0.5 else img[::k, ::k].copy())
        if r.random() < 0.3:
            small = jpeg(small, r.choice([75, 90]))
        out.append((f"downsampled/{i:03d}-{fmt.name}-x{k}", small, loosen(r, q / k)))

    r = random.Random(23)
    for i in range(80):
        fmt = r.choice([F.QRCode, F.QRCode, F.DataMatrix, F.Aztec])
        img, q = render(r, fmt, r.choice([2, 3, 4, 6]), rot=r.choice([0, 0, 15]), blur=r.choice([0, 0.5, 0.9]), ink=(0, 0, 0))
        h, w = img.shape[:2]
        a, b = np.array(r.choice([[(20, 60, 200), (150, 20, 160)], [(0, 120, 60), (0, 60, 160)], [(200, 40, 40), (90, 20, 150)]]), float)
        t = np.linspace(0, 1, w)[None, :, None]
        paperness = img.astype(np.float64).mean(-1, keepdims=True) / 255.0
        img = np.clip((a * (1 - t) + b * t) * (1 - paperness) + 245 * paperness, 0, 255).astype(np.uint8)
        q = loosen(r, q)
        if readable(img, q):
            out.append((f"gradient/{i:02d}-{fmt.name}", img, q))

    r = random.Random(29)
    for i in range(80):
        fmt = r.choice([F.QRCode, F.QRCode, F.Code128, F.DataMatrix])
        img, q = render(r, fmt, r.choice([3, 4, 6]), rot=r.choice([0, 8, -12]), persp=r.choice([0, 0.04]), blur=r.choice([0.5, 0.9]))
        h, w = img.shape[:2]
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
        ang, f = r.uniform(0, np.pi), r.uniform(0.08, 0.25)
        u = xx * np.cos(ang) + yy * np.sin(ang)
        moire = np.stack([np.sin(2 * np.pi * f * u + ph) for ph in (0, 2.1, 4.2)], -1) * r.choice([15, 25, 40])
        img = jpeg(np.clip(img + moire * (0.3 + 0.7 * img / 255.0), 0, 255).astype(np.uint8), 85)
        q = loosen(r, q)
        if readable(img, q):
            out.append((f"moire/{i:02d}-{fmt.name}", img, q))
    return out


# ---- 照片 ---------------------------------------------------------------------------------------
COLOUR_SAMPLES = ["astronaut", "coffee", "chelsea", "rocket", "cat", "colorwheel", "immunohistochemistry", "retina", "logo"]


def photos(extra_dir):
    from skimage import data
    out = []
    r = random.Random(11)
    for name in COLOUR_SAMPLES:
        a = getattr(data, name)()
        if a.shape[-1] == 4:
            a = a[..., :3]
        if a.dtype != np.uint8:
            a = (255 * (a - a.min()) / max(1e-9, a.max() - a.min())).astype(np.uint8)
        h, w = a.shape[:2]
        for i in range(20):
            if r.random() < 0.5:
                s = r.randint(40, min(h, w, 300))
                ww, hh = s, s
            else:
                ww = r.randint(60, min(w, 400))
                hh = min(h, max(20, int(ww * r.uniform(0.2, 0.6))))
            x0, y0 = r.randint(0, w - ww), r.randint(0, h - hh)
            out.append((f"skimage/{name}-{i}", a, np.array([[x0, y0], [x0 + ww, y0], [x0 + ww, y0 + hh], [x0, y0 + hh]], float)))
    extra = sorted(glob.glob(os.path.join(extra_dir, "*"))) if extra_dir else []
    for path in extra:
        try:
            a = np.array(Image.open(path).convert("RGB"))
        except Exception:  # noqa: BLE001 —— 不是图片就跳过
            continue
        a = cv2.resize(a, None, fx=200 / a.shape[1], fy=200 / a.shape[1], interpolation=cv2.INTER_AREA)
        h, w = a.shape[:2]
        out.append((f"photos/{os.path.basename(path)}", a, np.array([[0, 0], [w, 0], [w, h], [0, h]], float)))
    return out


def main():
    global _wechat
    ap = argparse.ArgumentParser()
    ap.add_argument("--photos", help="额外的照片目录，每张整图缩到 200 像素宽当一个框")
    ap.add_argument("--wechat", help="微信扫码引擎模型目录（detect.prototxt 等四个文件）")
    args = ap.parse_args()
    if args.wechat:
        d = args.wechat
        _wechat = cv2.wechat_qrcode_WeChatQRCode(f"{d}/detect.prototxt", f"{d}/detect.caffemodel",
                                                 f"{d}/sr.prototxt", f"{d}/sr.caffemodel")

    codes = [(n, measure(img, q)) for n, img, q in must_keep()]
    lost = [(n, m) for n, m in codes if not looks_printed(m)]
    worst = sorted(((m["off"], m["eta"], n) for n, m in codes if m), reverse=True)[:5]
    print(f"必须保留的码 {len(codes)} 个，被判丢 {len(lost)} 个")
    for n, m in lost:
        print(f"  丢了  {n}  off={m['off']:.3f} eta={m['eta']:.3f}")
    print("  离线占比最高的几个：" + "，".join(f"{n} {o:.3f}(η {e:.2f})" for o, e, n in worst))

    shots = [(n, measure(img, q)) for n, img, q in photos(args.photos)]
    groups = {}
    for n, m in shots:
        group = n.split("-")[0] if n.startswith("skimage/") else "photos"
        groups.setdefault(group, []).append(not looks_printed(m))
    print(f"照片 {len(shots)} 个框，判丢 {sum(not looks_printed(m) for _, m in shots)} 个")
    for g, flags in sorted(groups.items()):
        print(f"  {g:32s} {sum(flags):3d}/{len(flags)}")
    if args.photos:
        for n, m in shots:
            if n.startswith("photos/"):
                print(f"  {n:40s} off={m['off']:.3f} eta={m['eta']:.3f} -> {'留' if looks_printed(m) else '丢'}")


if __name__ == "__main__":
    main()
