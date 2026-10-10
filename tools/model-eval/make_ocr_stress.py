"""OCR 压力测试页：比 make_en_samples.py 难得多的截图，用来分出几个 OCR 模型的高下。

make_en_samples.py 的页面字大、底干净、只有一种字体，几个模型都在 99% 以上，分不出好坏。这里随机组合：
  - 字体：Roboto（安卓）、Inter（接近 SF）、Open Sans、Lato、Liberation Sans（Arial）、Source Serif、Roboto Mono；中文 Noto Sans SC、文泉驿；
  - 字号：1080 宽的屏上 26–48px（约 10sp–18sp），降采样之后最小的只剩 13px；
  - 配色：浅色、深色模式、灰色次要文字、蓝色链接、聊天气泡（白字蓝底、深字绿底）、彩色顶栏；
  - 屏幕：720×1600、1080×2340、1080×2400、1170×2532、1284×2778、1440×3200，照设备口径降采样后识别；
  - 画质：六成页面过一遍 JPEG（质量 55–92，聊天软件转发过的截图），一成五再缩放一次（截图的截图）。
文字是 Faker 造的假数据：人名、地址、邮箱、电话、订单号（故意混进 O/0、I/l/1）、价格、日期、短句、按钮文字。

每页写 truth/<名>.json：rows（每一行原文，score_ocr.py 用）、entities（人名、地址、邮箱、电话、编号，按原文记，
score_ocr_stress.py 看这些关键片段是否一字不差地读出来）、cond（这一页的条件，按条件分组看哪里掉分）。
页名 en-s-NNN / zh-s-NNN。输出到 $MODEL_EVAL_OUT（默认 out-stress/）。字体目录 $FONTS_DIR（Roboto 等不在系统里，见 download_en_eval.sh）。
"""
import io, json, os, random
from PIL import Image, ImageDraw, ImageFont
from faker import Faker

ROOT = os.environ.get("MODEL_EVAL_OUT", os.path.join(os.path.dirname(os.path.abspath(__file__)), "out-stress"))
FONTS = os.environ.get("FONTS_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "fonts"))
OUT, TRUTH = ROOT + "/samples", ROOT + "/truth"
os.makedirs(OUT, exist_ok=True)
os.makedirs(TRUTH, exist_ok=True)
N_EN, N_ZH = int(os.environ.get("N_EN", 48)), int(os.environ.get("N_ZH", 24))

SYS = "/usr/share/fonts"
EN_FONTS = [
    ("roboto", f"{FONTS}/Roboto.ttf", ["Regular", "Medium", "Light"]),
    ("inter", f"{SYS}/opentype/inter/Inter-Regular.otf", None),
    ("inter-medium", f"{SYS}/opentype/inter/Inter-Medium.otf", None),
    ("opensans", f"{FONTS}/OpenSans.ttf", ["Regular", "SemiBold"]),
    ("lato", f"{FONTS}/Lato.ttf", None),
    ("arial", f"{SYS}/truetype/liberation/LiberationSans-Regular.ttf", None),
    ("serif", f"{FONTS}/SourceSerif4.ttf", ["Regular"]),
    ("mono", f"{FONTS}/RobotoMono.ttf", ["Regular", "Medium"]),
]
ZH_FONTS = [
    ("notosc", f"{FONTS}/NotoSansSC.ttf", ["Regular", "Medium", "Light"]),
    ("wqy", f"{SYS}/truetype/wqy/wqy-zenhei.ttc", None),
]
SCREENS = [(720, 1600), (1080, 2340), (1080, 2400), (1170, 2532), (1284, 2778), (1440, 3200)]
THEMES = {
    "light": {"bg": (255, 255, 255), "fg": (28, 28, 30), "dim": (142, 142, 147), "link": (0, 122, 255)},
    "dark": {"bg": (0, 0, 0), "fg": (245, 245, 247), "dim": (152, 152, 159), "link": (10, 132, 255)},
    "dark-grey": {"bg": (28, 28, 30), "fg": (235, 235, 245), "dim": (142, 142, 147), "link": (100, 210, 255)},
    "grouped": {"bg": (242, 242, 247), "fg": (28, 28, 30), "dim": (110, 110, 115), "link": (0, 122, 255), "card": (255, 255, 255)},
}
BUBBLES = [((10, 132, 255), (255, 255, 255)), ((233, 233, 235), (28, 28, 30)), ((220, 248, 198), (17, 27, 33)),
           ((37, 211, 102), (255, 255, 255))]

rnd = random.Random(20261009)
fake_en = Faker(["en_US", "en_GB", "en_CA", "en_AU", "en_IN"])
fake_en.seed_instance(20261009)
fake_zh = Faker("zh_CN")
fake_zh.seed_instance(20261009)
CONFUSABLE = "O0QDIl1|S5B8Z2G6"


def font(path, size, variation):
    f = ImageFont.truetype(path, size)
    if variation:
        f.set_variation_by_name(variation)
    return f


def code(n, alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ0123456789"):
    return "".join(rnd.choice(alphabet) for _ in range(n))


def confusable_code():
    """订单号、确认号、优惠码：O/0、I/l/1 混在一起，读错一个字就对不上。"""
    pick = rnd.random()
    if pick < 0.3:
        return f"{rnd.randint(100, 999)}-{rnd.randint(1000000, 9999999)}-{rnd.randint(1000000, 9999999)}"
    if pick < 0.6:
        return code(6, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" + "O0I1" * 3)
    if pick < 0.8:
        return "1Z" + code(16, "0123456789ABCDEFGHJKLMNOPRSTUVWXYZ")
    return "".join(rnd.choice(CONFUSABLE + "ABC345789") for _ in range(rnd.randint(6, 10)))


def en_line():
    """一行英文和它里面的关键片段 [(类别, 原文)]。"""
    f = fake_en
    k = rnd.random()
    if k < 0.12:
        n = f.name()
        style = rnd.random()
        n = n.upper() if style < 0.2 else n
        return n, [("name", n)]
    if k < 0.22:
        a = f.street_address().replace("\n", ", ")
        a = a.upper() if rnd.random() < 0.15 else a
        return a, [("address", a)]
    if k < 0.30:
        c = f"{f.city()}, {f.state_abbr()} {f.postcode()}" if rnd.random() < 0.6 else f"{f.city()} {f.postcode()}"
        return c, [("address", c)]
    if k < 0.37:
        e = f.email()
        lead = rnd.choice(["", "Email: ", "To: ", "From: "])
        return lead + e, [("email", e)]
    if k < 0.44:
        p = f.phone_number()
        lead = rnd.choice(["", "Phone: ", "Mobile ", "Call "])
        return lead + p, [("phone", p)]
    if k < 0.52:
        c = confusable_code()
        lead = rnd.choice(["Order # ", "Confirmation ", "Booking ref ", "Tracking ", "Promo code ", ""])
        return lead + c, [("code", c)]
    if k < 0.60:
        n = f.first_name()
        t = rnd.choice([f"Tell {n} I said hi", f"Hi {n}, thanks for your order", f"Your driver {n} is nearby",
                        f"{n} sent you $" + f"{rnd.randint(5, 900)}.{rnd.randint(0, 99):02d}", f"Dear {f.prefix()} {f.last_name()},"])
        return t, []
    if k < 0.70:
        return rnd.choice([
            f"${rnd.randint(1, 4999):,}.{rnd.randint(0, 99):02d}", f"£{rnd.randint(1, 999)}.{rnd.randint(0, 99):02d}",
            f"€{rnd.randint(1, 999)},{rnd.randint(0, 99):02d}", f"{f.month_name()} {rnd.randint(1, 28)}, 2026",
            f"{f.day_of_week()[:3]}, {f.month_name()[:3]} {rnd.randint(1, 28)} · {rnd.randint(1, 12)}:{rnd.randint(0, 59):02d} PM",
            f"{rnd.randint(1, 99)}% off · {rnd.randint(2, 30)} items", f"Qty: {rnd.randint(1, 9)}  Size: {rnd.choice(['S', 'M', 'L', 'XL'])}",
        ]), []
    if k < 0.80:
        return rnd.choice([
            "Track package", "Order details", "Delivered", "Out for delivery", "Return or replace items", "Write a product review",
            "Buy it again", "View invoice", "Payment method", "Shipping address", "Add a note", "Message seller", "Get help with order",
            "Your account", "Sign out", "Notifications", "Privacy & Security", "Two-factor authentication", "Recently viewed",
        ]), []
    if k < 0.90:
        s = f.sentence(nb_words=rnd.randint(4, 8))
        return s, []
    co = f.company()
    return co if rnd.random() < 0.5 else f"{co} · {f.catch_phrase()}", []


def zh_line():
    f = fake_zh
    k = rnd.random()
    if k < 0.12:
        n = f.name()
        lead = rnd.choice(["", "收货人：", "联系人 ", "寄件人："])
        return lead + n, [("name", n)]
    if k < 0.24:
        a = f.address().split(" ")[0]
        lead = rnd.choice(["", "收货地址：", "送至 "])
        return lead + a, [("address", a)]
    if k < 0.32:
        p = f.phone_number()
        p = p[:3] + "****" + p[7:] if rnd.random() < 0.4 else p
        lead = rnd.choice(["", "手机号 ", "联系电话：", "86-"])
        return lead + p, [("phone", p)]
    if k < 0.40:
        c = rnd.choice([str(rnd.randint(10 ** 15, 10 ** 19)), "SF" + str(rnd.randint(10 ** 11, 10 ** 12)), code(8)])
        lead = rnd.choice(["订单编号 ", "快递单号：", "取件码 ", "交易号 "])
        return lead + c, [("code", c)]
    if k < 0.52:
        return rnd.choice([
            f"¥{rnd.randint(1, 9999)}.{rnd.randint(0, 99):02d}", f"{rnd.randint(1, 12)}月{rnd.randint(1, 28)}日 {rnd.randint(0, 23):02d}:{rnd.randint(0, 59):02d}",
            f"共{rnd.randint(1, 9)}件商品 合计 ¥{rnd.randint(10, 999)}.00", f"已优惠 ¥{rnd.randint(1, 99)}",
        ]), []
    if k < 0.68:
        return rnd.choice([
            "订单详情", "查看物流", "确认收货", "申请售后", "再买一单", "联系商家", "已签收", "派送中", "待取件", "我的订单", "退出登录",
            "隐私设置", "消息通知", "复制", "评价晒单", "开具发票", "退换货", "猜你喜欢", "店铺优惠券", "支付方式",
        ]), []
    if k < 0.88:
        return f.sentence(nb_words=rnd.randint(4, 9)), []
    return f.company(), []


def render(name, lang):
    W, H = rnd.choice(SCREENS)
    s = W / 1080
    theme_key = rnd.choice(list(THEMES))
    th = THEMES[theme_key]
    # 安卓的 Roboto、iOS 风格的 Inter、中文的 Noto Sans SC 是真实截图里最常见的，权重给高
    fonts = EN_FONTS if lang == "en" else ZH_FONTS
    weights = [4, 2, 2, 1, 1, 1, 1, 1] if lang == "en" else [3, 1]
    fname, fpath, variations = rnd.choices(fonts, weights=weights)[0]
    variation = rnd.choice(variations) if variations else None
    lo, hi = rnd.choice([(26, 32), (30, 40), (36, 48)])
    jpeg = rnd.random() < 0.6
    q = rnd.randint(55, 92) if jpeg else None
    rescale = rnd.random() < 0.15
    img = Image.new("RGB", (W, H), th["bg"])
    d = ImageDraw.Draw(img)
    rows, entities = [], []
    y = int(rnd.uniform(110, 170) * s)
    margin = int(48 * s)
    # 顶栏：一半的页面有一条彩色顶栏，白字
    if rnd.random() < 0.5:
        bar = rnd.choice([(0, 122, 255), (255, 153, 0), (230, 0, 18), (7, 193, 96), (88, 86, 214)])
        d.rectangle((0, 0, W, int(200 * s)), fill=bar)
        t = rnd.choice(["Orders", "Messages", "Account", "Inbox", "Checkout"]) if lang == "en" else rnd.choice(["我的订单", "消息", "账户", "购物车"])
        d.text((margin, int(110 * s)), t, font=font(fpath, int(46 * s), variation), fill=(255, 255, 255))
        rows.append(t)
        y = int(260 * s)
    while y < H - 150 * s:
        size = int(rnd.randint(lo, hi) * s)
        f = font(fpath, size, variation)
        text, ents = en_line() if lang == "en" else zh_line()
        # 截断到一行放得下
        while d.textlength(text, font=f) > W - 2 * margin and " " in text.strip():
            text = text.rsplit(" ", 1)[0]
            ents = [(c, e) for c, e in ents if e in text]
        if d.textlength(text, font=f) > W - 2 * margin:
            text = text[: max(4, int(len(text) * (W - 2 * margin) / d.textlength(text, font=f)) - 1)]
            ents = [(c, e) for c, e in ents if e in text]
        style = rnd.random()
        color = th["fg"] if style < 0.6 else th["dim"] if style < 0.85 else th["link"]
        tw = d.textlength(text, font=f)
        if style > 0.9 or (theme_key == "grouped" and rnd.random() < 0.3):
            # 气泡 / 卡片
            bg, fg = rnd.choice(BUBBLES) if style > 0.9 else (th.get("card", th["bg"]), th["fg"])
            x0 = margin if rnd.random() < 0.6 else max(margin, int(W - margin - tw - 40 * s))
            d.rounded_rectangle((x0 - 20 * s, y - 14 * s, x0 + tw + 20 * s, y + size * 1.25 + 10 * s), radius=int(28 * s), fill=bg)
            d.text((x0, y), text, font=f, fill=fg)
        elif rnd.random() < 0.2 and lang == "en":
            # 左右两栏：字段名在左、值在右
            label = rnd.choice(["Name", "Phone", "Email", "Total", "Status", "Date"])
            d.text((margin, y), label, font=f, fill=th["dim"])
            vx = int(W * 0.42)
            while d.textlength(text, font=f) > W - vx - margin and len(text) > 4:
                text = text[:-1]
            ents = [(c, e) for c, e in ents if e in text]
            d.text((vx, y), text, font=f, fill=color)
            rows.append(label)
        else:
            d.text((margin, y), text, font=f, fill=color)
        rows.append(text)
        entities += [{"kind": c, "text": e} for c, e in ents]
        y += int(size * rnd.uniform(1.55, 2.3))
    if rescale:
        k = rnd.uniform(0.7, 0.85)
        img = img.resize((int(W * k), int(H * k)), Image.BILINEAR).resize((W, H), Image.BILINEAR)
    if jpeg:
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=q)
        img = Image.open(io.BytesIO(buf.getvalue())).convert("RGB")
    img.save(f"{OUT}/{name}.png")
    cond = {"lang": lang, "screen": f"{W}x{H}", "theme": theme_key, "font": fname + (f"-{variation}" if variation else ""),
            "size": f"{lo}-{hi}", "jpeg": q, "rescale": rescale}
    with open(f"{TRUTH}/{name}.json", "w") as fo:
        json.dump({"find": [], "avoid": [], "rows": rows, "entities": entities, "cond": cond}, fo, ensure_ascii=False, indent=1)


for i in range(N_EN):
    render(f"en-s-{i:03d}", "en")
for i in range(N_ZH):
    render(f"zh-s-{i:03d}", "zh")
print(f"{N_EN} en + {N_ZH} zh stress pages -> {OUT}")
