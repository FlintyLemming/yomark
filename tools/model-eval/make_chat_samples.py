"""合成聊天截图测试集（全是编的假数据）。

每张图一个同名 .json 真值：
  is_chat   这张是不是聊天记录页
  app/theme/group  皮肤、深浅色、群聊还是单聊
  boxes     要打码的框 [{kind, x0,y0,x1,y1, text?, role?}]
            kind = avatar（头像，role=other/self/header）| name（发言者昵称，role=above/inbubble/system）| title（聊天标题）
用法：python3 make_chat_samples.py [输出目录]，默认脚本旁边的 out-chat/。
      python3 make_chat_samples.py --fixtures app/src/test/resources/chat
          另外挑出一份缩到 540 宽的子集给 app 的单测（ChatLayoutTest）：存小图省地方，版面规则按屏宽的比例量，图大图小一样认。
          微信长截图再存一张原尺寸的到旁边的 chat-native/（ChatDetectorTest 拿真 OCR 按原图读，截图进识别引擎时不缩）。
"""
import json, math, os, random, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
FIXTURES = sys.argv[sys.argv.index("--fixtures") + 1] if "--fixtures" in sys.argv else None
if FIXTURES:
    ARGS.remove(FIXTURES)
OUT = ARGS[0] if ARGS else os.path.join(os.path.dirname(os.path.abspath(__file__)), "out-chat")
os.makedirs(OUT, exist_ok=True)
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
_fonts = {}


def font(px):
    px = max(8, int(px))
    if px not in _fonts:
        _fonts[px] = ImageFont.truetype(FONT, px)
    return _fonts[px]


SURNAMES = "王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾萧田董袁潘于蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤"
GIVEN = "伟芳娜敏静丽强磊军洋勇艳杰娟涛明超秀霞平刚桂英华玉兰萍红鹏辉建国晓峰宇浩然欣怡子轩梓涵一诺雨桐思远佳琪俊杰"
NICKS = ["小鱼儿", "Momo", "阿杰", "大熊", "Coco", "Kevin", "番茄炒蛋", "Lily", "老王", "不吃香菜", "Sunny", "七七", "阿May",
         "Jason Liu", "木子", "橘子汽水", "Tony", "风一样的男子", "小太阳", "Echo", "胖虎", "Zoe", "安安", "晴天"]
EN_NAMES = ["Emily Carter", "Rob Fletcher", "Sophie Lambert", "Marcus Lee", "Priya Shah", "Tom Becker", "Ana Souza", "Jess"]
GROUPS = ["周末羽毛球群", "302宿舍", "相亲相爱一家人", "产品部-需求对接", "小区业主群（3期）", "高三(2)班家长群", "徒步爱好者", "项目X攻坚组",
          "Family", "Book Club", "Weekend Hikers", "Design Team"]
ZH_MSGS = ["周六来我家吃饭吧", "好嘞，几点到合适？", "中午十二点，到了给我打电话", "收到", "哈哈哈哈哈", "明天的会改到下午三点了",
           "顺路帮我取个快递，取件码 5-1-0307", "我已经到楼下了", "你看下这个方案行不行，有问题随时说", "好的没问题",
           "今晚加班，晚点回", "记得带伞，下午有雨", "上次聚会的照片我发群里了", "周报我晚点交", "这个周末有空吗？想约你去爬山",
           "可以可以", "刚开完会，怎么了", "麻烦大家把材料在周五前交给我，谢谢", "已转账", "地址：长江路88号阳光新村5幢302室",
           "我手机快没电了，等下回你", "明白", "群里统计一下，下周五聚餐能来的扣1", "1", "+1", "我也去",
           "那就这么定了，周日早上八点小区门口集合", "有人知道物业电话吗", "孩子明天要带彩笔和剪刀"]
EN_MSGS = ["Are we still on for Saturday?", "Yes! See you at 7", "Running 10 min late, sorry", "No worries",
           "Can you send me the file?", "Sent", "Happy birthday!!", "lol", "Who's bringing snacks?", "I can bring chips",
           "Meeting moved to 3pm", "Thanks everyone", "Sounds good to me", "On my way"]
TIMES = ["昨天 21:46", "今天 09:12", "上午 10:24", "下午 3:05", "10月12日 晚上8:30", "星期六 14:02", "18:47"]
EN_TIMES = ["Yesterday 9:46 PM", "Today 9:12 AM", "Sat 2:02 PM", "10:24 AM"]

PALETTE = [(247, 168, 98), (120, 170, 245), (104, 196, 160), (232, 120, 140), (160, 130, 230), (250, 200, 80),
           (90, 90, 110), (200, 200, 200), (60, 140, 200), (230, 90, 60), (40, 40, 40), (250, 250, 250)]


def person():
    r = random.random()
    if r < 0.45:
        return random.choice(SURNAMES) + "".join(random.choice(GIVEN) for _ in range(random.choice([1, 2])))
    if r < 0.85:
        return random.choice(NICKS)
    return random.choice(EN_NAMES)


def avatar_img(size, shape, seed):
    """几种头像：纯色+字、渐变、几何图形拼的「照片」、风景色块、近白（和底色接近的难例）。"""
    rnd = random.Random(seed)
    s = size * 2
    im = Image.new("RGB", (s, s))
    d = ImageDraw.Draw(im)
    kind = rnd.choice(["letter", "gradient", "photo", "photo", "landscape", "pale"])
    c1, c2 = rnd.choice(PALETTE), rnd.choice(PALETTE)
    if kind == "letter":
        d.rectangle((0, 0, s, s), fill=c1)
        d.text((s / 2, s / 2), rnd.choice(SURNAMES + "ABCDEFGHJK"), font=font(s * 0.5), fill="white", anchor="mm")
    elif kind == "gradient":
        for y in range(s):
            t = y / s
            d.line((0, y, s, y), fill=tuple(int(a + (b - a) * t) for a, b in zip(c1, c2)))
    elif kind == "photo":
        d.rectangle((0, 0, s, s), fill=c1)
        for _ in range(rnd.randint(6, 14)):
            x, y, r = rnd.uniform(0, s), rnd.uniform(0, s), rnd.uniform(s * .08, s * .4)
            d.ellipse((x - r, y - r, x + r, y + r), fill=rnd.choice(PALETTE))
        im = im.filter(ImageFilter.GaussianBlur(s * 0.03))
    elif kind == "landscape":
        d.rectangle((0, 0, s, s * .55), fill=(130 + rnd.randint(0, 80), 180 + rnd.randint(0, 60), 240))
        d.polygon([(0, s * .7), (s * .4, s * .35), (s * .8, s * .65), (s, s * .5), (s, s), (0, s)], fill=(70, 120, 80))
    else:
        d.rectangle((0, 0, s, s), fill=(238, 238, 238))
        d.ellipse((s * .3, s * .18, s * .7, s * .58), fill=(205, 205, 205))
        d.ellipse((s * .12, s * .62, s * .88, s * 1.3), fill=(205, 205, 205))
    im = im.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size * 4, size * 4), 0)
    md = ImageDraw.Draw(mask)
    if shape == "circle":
        md.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    else:
        md.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=size * 4 * 0.1, fill=255)
    return im, mask.resize((size, size), Image.LANCZOS)


class RecDraw(ImageDraw.ImageDraw):
    """记下每一处写在图上的字（框 + 原文），当作 OCR 读出来的行。"""

    def __init__(self, im):
        super().__init__(im)
        self.lines = []

    def text(self, xy, text, fill=None, font=None, anchor=None, **kw):
        if text.strip():
            b = self.textbbox(xy, text, font=font, anchor=anchor)
            self.lines.append([int(b[0]), int(b[1]), int(b[2]), int(b[3]), text.strip()])
        return super().text(xy, text, fill=fill, font=font, anchor=anchor, **kw)


def wrap(text, f, maxw, d):
    lines, cur = [], ""
    for ch in text:
        if d.textlength(cur + ch, font=f) > maxw and cur:
            lines.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        lines.append(cur)
    return lines


# 皮肤：颜色单位 RGB，尺寸按 1080 宽设计，再乘缩放
SKINS = {
    "wechat": dict(
        light=dict(bg=(237, 237, 237), bar=(237, 237, 237), ink=(25, 25, 25), sub=(150, 150, 150), other=(255, 255, 255), self=(149, 236, 105), self_ink=(25, 25, 25), input=(247, 247, 247)),
        dark=dict(bg=(17, 17, 17), bar=(17, 17, 17), ink=(220, 220, 220), sub=(120, 120, 120), other=(44, 44, 44), self=(62, 181, 117), self_ink=(20, 20, 20), input=(30, 30, 30)),
        avatar=88, shape="square", title_align="center", names="above", self_avatar=True, header_avatar=False, avatar_valign="top",
        input_hint="", send="发送", count=True),
    "qq": dict(
        light=dict(bg=(241, 242, 246), bar=(255, 255, 255), ink=(20, 20, 20), sub=(140, 140, 150), other=(255, 255, 255), self=(0, 153, 255), self_ink=(255, 255, 255), input=(255, 255, 255)),
        dark=dict(bg=(16, 16, 20), bar=(26, 26, 30), ink=(225, 225, 230), sub=(120, 120, 130), other=(38, 38, 44), self=(0, 120, 210), self_ink=(255, 255, 255), input=(30, 30, 34)),
        avatar=88, shape="circle", title_align="center", names="above", self_avatar=True, header_avatar=False, avatar_valign="top",
        input_hint="", send="发送", count=True),
    "telegram": dict(
        light=dict(bg=None, bar=(255, 255, 255), ink=(20, 20, 20), sub=(140, 150, 160), other=(255, 255, 255), self=(225, 255, 199), self_ink=(20, 20, 20), input=(255, 255, 255)),
        dark=dict(bg=None, bar=(33, 43, 54), ink=(240, 240, 240), sub=(120, 140, 160), other=(24, 37, 51), self=(43, 82, 120), self_ink=(240, 240, 240), input=(24, 34, 45)),
        avatar=76, shape="circle", title_align="left", names="inbubble", self_avatar=False, header_avatar=True, avatar_valign="bottom",
        input_hint="Message", send=None, count=False),
    "whatsapp": dict(
        light=dict(bg=None, bar=(0, 128, 105), bar_ink=(255, 255, 255), ink=(20, 20, 20), sub=(130, 140, 140), other=(255, 255, 255), self=(217, 253, 211), self_ink=(20, 20, 20), input=(255, 255, 255)),
        dark=dict(bg=None, bar=(31, 44, 52), ink=(233, 237, 239), sub=(134, 150, 160), other=(32, 44, 51), self=(0, 92, 75), self_ink=(233, 237, 239), input=(31, 44, 52)),
        avatar=64, shape="circle", title_align="left", names="inbubble", self_avatar=False, header_avatar=True, avatar_valign="top",
        input_hint="Message", send=None, count=False),
    "imessage": dict(
        light=dict(bg=(255, 255, 255), bar=(246, 246, 246), ink=(0, 0, 0), sub=(142, 142, 147), other=(233, 233, 235), self=(10, 132, 255), self_ink=(255, 255, 255), input=(255, 255, 255)),
        dark=dict(bg=(0, 0, 0), bar=(22, 22, 22), ink=(255, 255, 255), sub=(142, 142, 147), other=(38, 38, 40), self=(10, 132, 255), self_ink=(255, 255, 255), input=(0, 0, 0)),
        avatar=64, shape="circle", title_align="under_avatar", names="above_small", self_avatar=False, header_avatar=True, avatar_valign="bottom",
        input_hint="iMessage", send=None, count=False),
    "line": dict(
        light=dict(bg=(140, 171, 216), bar=(140, 171, 216), ink=(20, 20, 20), sub=(60, 70, 90), other=(255, 255, 255), self=(133, 227, 115), self_ink=(20, 20, 20), input=(255, 255, 255)),
        dark=dict(bg=(30, 32, 38), bar=(30, 32, 38), ink=(235, 235, 235), sub=(150, 150, 160), other=(54, 56, 64), self=(64, 160, 80), self_ink=(240, 240, 240), input=(30, 32, 38)),
        avatar=80, shape="circle", title_align="center", names="above", self_avatar=False, header_avatar=False, avatar_valign="top",
        input_hint="Aa", send=None, count=True, names_in_single=True),
    "messenger": dict(
        light=dict(bg=(255, 255, 255), bar=(255, 255, 255), ink=(5, 5, 5), sub=(101, 103, 107), other=(240, 240, 240), self=(0, 132, 255), self_ink=(255, 255, 255), input=(255, 255, 255)),
        dark=dict(bg=(0, 0, 0), bar=(0, 0, 0), ink=(228, 230, 235), sub=(176, 179, 184), other=(48, 48, 48), self=(0, 132, 255), self_ink=(255, 255, 255), input=(0, 0, 0)),
        avatar=56, shape="circle", title_align="left", names="above_small", self_avatar=False, header_avatar=True, avatar_valign="bottom",
        input_hint="Aa", send=None, count=False, single_avatar=True),
}
NAME_COLORS = [(220, 80, 80), (60, 140, 220), (90, 170, 90), (200, 120, 40), (150, 90, 210), (40, 160, 170)]


def wallpaper(w, h, dark, rnd):
    im = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(im)
    a = (15, 24, 32) if dark else (rnd.randint(190, 225), rnd.randint(205, 230), rnd.randint(160, 200))
    b = (25, 38, 50) if dark else (rnd.randint(220, 245), rnd.randint(215, 235), rnd.randint(190, 215))
    for y in range(h):
        t = y / h
        d.line((0, y, w, y), fill=tuple(int(p + (q - p) * t) for p, q in zip(a, b)))
    ink = tuple(min(255, c + 12) for c in a) if dark else tuple(max(0, c - 18) for c in a)
    for _ in range(int(w * h / 9000)):   # 涂鸦小图案
        x, y, r = rnd.uniform(0, w), rnd.uniform(0, h), rnd.uniform(8, 22)
        k = rnd.random()
        if k < .4:
            d.ellipse((x - r, y - r, x + r, y + r), outline=ink, width=3)
        elif k < .7:
            d.line((x - r, y, x + r, y + r * .6), fill=ink, width=3)
        else:
            d.rectangle((x - r * .6, y - r * .6, x + r * .6, y + r * .6), outline=ink, width=3)
    return im


def chat_page(idx, app, theme, group, W=1080, H=2340, long=False, lang="zh"):
    rnd = random.Random(idx * 7919 + sum(map(ord, app)))
    random.seed(idx)
    sk, c = SKINS[app], SKINS[app][theme]
    S = W / 1080
    if long:
        H = int(H * 2.2)
    boxes = []
    img = wallpaper(W, H, theme == "dark", rnd) if c["bg"] is None else Image.new("RGB", (W, H), c["bg"])
    d = RecDraw(img)
    ink, sub = c["ink"], c["sub"]

    # 状态栏
    sb = int(100 * S)
    bar_bg = c["bar"]
    d.rectangle((0, 0, W, sb), fill=bar_bg)
    bar_ink = c.get("bar_ink", ink)
    d.text((int(60 * S), sb // 2), rnd.choice(["10:24", "21:03", "9:41", "14:30"]), font=font(40 * S), fill=bar_ink, anchor="lm")
    d.rounded_rectangle((W - 120 * S, sb / 2 - 14 * S, W - 60 * S, sb / 2 + 14 * S), radius=6 * S, outline=bar_ink, width=max(2, int(3 * S)))
    # 顶栏
    tb = int((200 if sk["title_align"] == "under_avatar" else 130) * S)
    d.rectangle((0, sb, W, sb + tb), fill=bar_bg)
    d.line((0, sb + tb, W, sb + tb), fill=tuple(max(0, x - 15) for x in bar_bg), width=2)
    members = rnd.randint(3, 480)
    title = rnd.choice(GROUPS) if group else person()
    if lang == "en" and not group:
        title = rnd.choice(EN_NAMES)
    cy = sb + tb // 2
    d.line((50 * S, cy, 72 * S, cy - 22 * S), fill=bar_ink, width=int(5 * S))
    d.line((50 * S, cy, 72 * S, cy + 22 * S), fill=bar_ink, width=int(5 * S))
    tf = font((44 if sk["title_align"] != "under_avatar" else 32) * S)
    ttext = title + (f"({members})" if group and sk["count"] else "")
    if sk["title_align"] == "center":
        bb = d.textbbox((W / 2, cy), ttext, font=tf, anchor="mm")
        d.text((W / 2, cy), ttext, font=tf, fill=bar_ink, anchor="mm")
        for k in range(3):
            d.ellipse((W - (100 - k * 22) * S, cy - 6 * S, W - (88 - k * 22) * S, cy + 6 * S), fill=bar_ink)
    elif sk["title_align"] == "left":
        av = int(84 * S)
        ax = int(100 * S)
        a, m = avatar_img(av, "circle", rnd.random())
        img.paste(a, (ax, cy - av // 2), m)
        boxes.append(dict(kind="avatar", role="header", x0=ax, y0=cy - av // 2, x1=ax + av, y1=cy - av // 2 + av))
        tx = ax + av + int(28 * S)
        bb = d.textbbox((tx, cy - 22 * S), title, font=tf, anchor="lm")
        d.text((tx, cy - 22 * S), title, font=tf, fill=bar_ink, anchor="lm")
        stat = (f"{members} members" if group else "last seen recently") if lang == "en" else (f"{members} 位成员" if group else "最近在线")
        d.text((tx, cy + 26 * S), stat, font=font(30 * S), fill=sub if "bar_ink" not in c else (200, 230, 220), anchor="lm")
    else:  # iMessage：顶栏中间小头像 + 下面名字
        av = int(90 * S)
        ax, ay = W // 2 - av // 2, sb + int(18 * S)
        a, m = avatar_img(av, "circle", rnd.random())
        img.paste(a, (ax, ay), m)
        boxes.append(dict(kind="avatar", role="header", x0=ax, y0=ay, x1=ax + av, y1=ay + av))
        bb = d.textbbox((W / 2, ay + av + 30 * S), title + " ›", font=tf, anchor="mm")
        d.text((W / 2, ay + av + 30 * S), title + " ›", font=tf, fill=ink, anchor="mm")
    boxes.append(dict(kind="title", text=title, x0=bb[0], y0=bb[1], x1=bb[2], y1=bb[3]))

    # 输入栏
    ib = int(150 * S)
    d.rectangle((0, H - ib, W, H), fill=c["input"])
    d.rounded_rectangle((130 * S, H - ib + 30 * S, W - (220 if sk["send"] else 130) * S, H - 30 * S), radius=40 * S,
                        fill=tuple(max(0, x - 12) if theme == "light" else min(255, x + 18) for x in c["input"]))
    if sk["input_hint"]:
        d.text((170 * S, H - ib / 2), sk["input_hint"], font=font(36 * S), fill=sub, anchor="lm")
    d.ellipse((40 * S, H - ib / 2 - 32 * S, 104 * S, H - ib / 2 + 32 * S), outline=sub, width=int(4 * S))
    if sk["send"]:
        d.rounded_rectangle((W - 190 * S, H - ib + 30 * S, W - 40 * S, H - 30 * S), radius=40 * S, fill=c["self"])
        d.text((W - 115 * S, H - ib / 2), sk["send"], font=font(36 * S), fill=c["self_ink"], anchor="mm")

    # 发言人
    people = [person() for _ in range(rnd.randint(3, 6) if group else 1)]
    if lang == "en":
        people = rnd.sample(EN_NAMES, len(people))
    seeds = {p: rnd.random() for p in people}
    self_seed = rnd.random()
    msgs = EN_MSGS if lang == "en" else ZH_MSGS
    times = EN_TIMES if lang == "en" else TIMES

    AV = int(sk["avatar"] * S)
    margin = int(24 * S)
    gap = int(18 * S)
    y = sb + tb + int(40 * S)
    bottom = H - ib - int(30 * S)
    mf = font(38 * S)
    nf = font(26 * S)
    last_speaker = None
    while True:
        r = rnd.random()
        if r < 0.12:   # 时间戳
            t = rnd.choice(times)
            if y + 60 * S > bottom:
                break
            d.text((W / 2, y + 22 * S), t, font=font(28 * S), fill=sub, anchor="mm")
            y += int(70 * S)
            last_speaker = None
            continue
        if r < 0.16 and group:   # 系统消息，里面带名字
            p = rnd.choice(people)
            tail = rnd.choice(["撤回了一条消息", "加入了群聊", "拍了拍我"]) if lang == "zh" else rnd.choice([" joined", " left"])
            msg = f"\"{p}\" {tail}" if lang == "zh" else p + tail
            if y + 60 * S > bottom:
                break
            sf = font(28 * S)
            tw = d.textlength(msg, font=sf)
            x0 = W / 2 - tw / 2
            d.rounded_rectangle((x0 - 16 * S, y, x0 + tw + 16 * S, y + 50 * S), radius=10 * S,
                                fill=(218, 218, 218) if theme == "light" and c["bg"] else (0, 0, 0, 0) if False else tuple(max(0, v - 25) for v in (c["bg"] or (180, 190, 170))))
            d.text((W / 2, y + 25 * S), msg, font=sf, fill=sub if c["bg"] else (250, 250, 250), anchor="mm")
            pre = msg[:msg.index(p)]
            nx0 = x0 + d.textlength(pre, font=sf)
            boxes.append(dict(kind="name", role="system", text=p, x0=int(nx0), y0=int(y + 8 * S), x1=int(nx0 + d.textlength(p, font=sf)), y1=int(y + 44 * S)))
            y += int(80 * S)
            last_speaker = None
            continue
        mine = rnd.random() < 0.35
        speaker = "__self__" if mine else rnd.choice(people)
        kind = rnd.random()
        text = rnd.choice(msgs)
        # 气泡尺寸
        maxw = int(W * 0.62)
        if kind < 0.1:      # 图片消息
            bw, bh = int(rnd.uniform(300, 480) * S), int(rnd.uniform(260, 520) * S)
            lines = None
        elif kind < 0.16:   # 语音
            bw, bh = int(rnd.uniform(180, 420) * S), int(84 * S)
            lines = [" " + f"{rnd.randint(2, 40)}\""]
        else:
            lines = wrap(text, mf, maxw - int(56 * S), d)
            bw = int(max(d.textlength(t, font=mf) for t in lines) + 56 * S)
            bh = int(len(lines) * 52 * S + 36 * S)
        show_name = (group or sk.get("names_in_single")) and not mine and speaker != last_speaker
        name_h = 0
        if show_name and sk["names"] in ("above", "above_small"):
            name_h = int(40 * S)
        inb = show_name and sk["names"] == "inbubble" and lines is not None
        if inb:
            bh += int(44 * S)
            bw = max(bw, int(d.textlength(speaker, font=font(30 * S)) + 56 * S))
        need = name_h + bh + int(26 * S)
        if y + need > bottom:
            break
        has_avatar_col = (not mine and (group or app in ("wechat", "qq", "line") or sk.get("single_avatar"))) or (mine and sk["self_avatar"])
        if mine:
            ax = W - margin - AV
            bx1 = (ax - gap) if sk["self_avatar"] else W - margin
            bx0 = bx1 - bw
        else:
            ax = margin
            bx0 = (ax + AV + gap) if has_avatar_col else margin
            bx1 = bx0 + bw
        by = y + name_h
        if name_h:
            nm = speaker
            nfnt = nf
            d.text((bx0 + 4 * S, y + 4 * S), nm, font=nfnt, fill=sub)
            nb = d.textbbox((bx0 + 4 * S, y + 4 * S), nm, font=nfnt)
            boxes.append(dict(kind="name", role="above", text=speaker, x0=nb[0], y0=nb[1], x1=nb[2], y1=nb[3]))
        fill = c["self"] if mine else c["other"]
        if lines is None:
            pic = avatar_img(max(bw, bh), "square", rnd.random())[0].resize((bw, bh))
            pm = Image.new("L", (bw, bh), 0)
            ImageDraw.Draw(pm).rounded_rectangle((0, 0, bw - 1, bh - 1), radius=int(16 * S), fill=255)
            img.paste(pic, (bx0, by), pm)
        else:
            d.rounded_rectangle((bx0, by, bx1, by + bh), radius=int(18 * S), fill=fill)
            ty = by + int(18 * S)
            if inb:
                col = NAME_COLORS[people.index(speaker) % len(NAME_COLORS)]
                d.text((bx0 + 28 * S, ty), speaker, font=font(30 * S), fill=col)
                nb = d.textbbox((bx0 + 28 * S, ty), speaker, font=font(30 * S))
                boxes.append(dict(kind="name", role="inbubble", text=speaker, x0=nb[0], y0=nb[1], x1=nb[2], y1=nb[3]))
                ty += int(44 * S)
            for t in lines:
                d.text((bx0 + 28 * S, ty), t, font=mf, fill=c["self_ink"] if mine else ink)
                ty += int(52 * S)
        # 头像：底对齐的皮肤（Telegram、iMessage）只在一串的最后一条上画；这里简化成每条都画、一半概率省略
        if has_avatar_col:
            if sk["avatar_valign"] == "top":
                ay = y + (0 if not name_h else 0)
                draw = True
            else:
                ay = by + bh - AV
                draw = rnd.random() < 0.6
            if draw:
                a, m = avatar_img(AV, sk["shape"], self_seed if mine else seeds[speaker])
                img.paste(a, (ax, ay), m)
                boxes.append(dict(kind="avatar", role="self" if mine else "other", x0=ax, y0=ay, x1=ax + AV, y1=ay + AV))
        y += need
        last_speaker = speaker
    return img, dict(is_chat=True, app=app, theme=theme, group=group, lang=lang, size=[W, H], long=long, boxes=boxes, lines=d.lines)


def discord_page(idx, theme, W=1080, H=2340):
    """单列列表式：所有消息靠左、没有气泡；每组第一条左边一个圆头像，右边一行「名字 + 时间」。"""
    rnd = random.Random(idx * 131)
    random.seed(idx)
    dark = theme == "dark"
    bg, ink, sub = ((49, 51, 56), (220, 221, 222), (148, 155, 164)) if dark else ((255, 255, 255), (6, 6, 7), (92, 94, 102))
    img = Image.new("RGB", (W, H), bg)
    d = RecDraw(img)
    S = W / 1080
    d.text((60 * S, 50 * S), "10:24", font=font(40 * S), fill=ink, anchor="lm")
    cy = 165 * S
    d.line((50 * S, cy, 72 * S, cy - 22 * S), fill=ink, width=int(5 * S))
    d.line((50 * S, cy, 72 * S, cy + 22 * S), fill=ink, width=int(5 * S))
    title = "# " + rnd.choice(["general", "闲聊", "dev", "announcements"])
    tb = d.textbbox((110 * S, cy), title, font=font(44 * S), anchor="lm")
    d.text((110 * S, cy), title, font=font(44 * S), fill=ink, anchor="lm")
    boxes = [dict(kind="title", text=title[2:], x0=tb[0], y0=tb[1], x1=tb[2], y1=tb[3])]
    d.line((0, 230 * S, W, 230 * S), fill=(30, 31, 34) if dark else (225, 225, 228), width=2)
    people = rnd.sample(EN_NAMES, 4)
    seeds = {p: rnd.random() for p in people}
    AV = int(84 * S)
    y = int(270 * S)
    bottom = H - int(180 * S)
    d.rounded_rectangle((30 * S, H - 140 * S, W - 30 * S, H - 50 * S), radius=20 * S, fill=(56, 58, 64) if dark else (235, 237, 240))
    d.text((70 * S, H - 95 * S), "Message #" + title[2:], font=font(34 * S), fill=sub, anchor="lm")
    while True:
        p = rnd.choice(people)
        n_msgs = rnd.randint(1, 3)
        need = int((60 + 56 * n_msgs + 40) * S)
        if y + need > bottom:
            break
        a, m = avatar_img(AV, "circle", seeds[p])
        img.paste(a, (int(30 * S), y), m)
        boxes.append(dict(kind="avatar", role="other", x0=int(30 * S), y0=y, x1=int(30 * S) + AV, y1=y + AV))
        nx = int(140 * S)
        col = NAME_COLORS[people.index(p) % len(NAME_COLORS)]
        d.text((nx, y), p, font=font(36 * S), fill=col)
        nb = d.textbbox((nx, y), p, font=font(36 * S))
        boxes.append(dict(kind="name", role="header", text=p, x0=nb[0], y0=nb[1], x1=nb[2], y1=nb[3]))
        d.text((nb[2] + 20 * S, y + 6 * S), rnd.choice(["Today at 10:24 AM", "今天 09:12", "Yesterday at 9:46 PM"]), font=font(26 * S), fill=sub)
        ty = y + int(56 * S)
        for _ in range(n_msgs):
            d.text((nx, ty), rnd.choice(EN_MSGS + ZH_MSGS), font=font(36 * S), fill=ink)
            ty += int(56 * S)
        y = ty + int(40 * S)
    return img, dict(is_chat=True, app="discord", theme=theme, group=True, lang="en", size=[W, H], long=False, boxes=boxes, lines=d.lines)


def negative_page(idx, kind, W=1080, H=2340):
    """不是聊天页的难例：通讯录 / 会话列表（有头像有名字，但不是聊天记录）、设置页、商品列表、文章。"""
    rnd = random.Random(idx * 31 + 5)
    random.seed(idx + 1000)
    S = W / 1080
    img = Image.new("RGB", (W, H), (255, 255, 255))
    d = RecDraw(img)
    d.text((60 * S, 50 * S), "10:24", font=font(40 * S), fill=(20, 20, 20), anchor="lm")
    titles = dict(contacts="通讯录", chatlist="微信", settings="设置", shop="猜你喜欢", article="阅读")
    d.text((W / 2, 165 * S), titles[kind], font=font(44 * S), fill=(20, 20, 20), anchor="mm")
    y = int(260 * S)
    boxes = []
    if kind in ("contacts", "chatlist", "settings", "shop"):
        while y < H - 300 * S:
            av = int((96 if kind != "settings" else 64) * S)
            if kind == "shop":
                a, m = avatar_img(int(300 * S), "square", rnd.random())
                for col in range(2):
                    x = int((60 + col * 500) * S)
                    img.paste(a, (x, y))
                    d.text((x, y + 320 * S), rnd.choice(["保温杯 500ml", "无线耳机", "纯棉T恤", "儿童绘本"]), font=font(34 * S), fill=(30, 30, 30))
                    d.text((x, y + 370 * S), f"¥{rnd.randint(9, 399)}", font=font(36 * S), fill=(240, 80, 40))
                y += int(460 * S)
                continue
            if kind == "settings":
                d.rounded_rectangle((40 * S, y, 40 * S + av, y + av), radius=14 * S, fill=rnd.choice(PALETTE))
                d.text((140 * S, y + av / 2), rnd.choice(["通知", "隐私", "通用", "存储空间", "关于手机", "账号与安全", "显示与亮度"]), font=font(38 * S), fill=(20, 20, 20), anchor="lm")
            else:
                a, m = avatar_img(av, "square", rnd.random())
                img.paste(a, (int(40 * S), y), m)
                nm = person() if kind == "contacts" or rnd.random() < .6 else rnd.choice(GROUPS)
                d.text((160 * S, y + (av / 2 if kind == "contacts" else 28 * S)), nm, font=font(38 * S), fill=(20, 20, 20), anchor="lm")
                if kind == "chatlist":
                    d.text((160 * S, y + 74 * S), rnd.choice(ZH_MSGS)[:16], font=font(30 * S), fill=(150, 150, 150), anchor="lm")
                    d.text((W - 60 * S, y + 28 * S), rnd.choice(["10:24", "昨天", "星期六"]), font=font(28 * S), fill=(170, 170, 170), anchor="rm")
            y += int(av + 40 * S)
            d.line((140 * S, y - 20 * S, W, y - 20 * S), fill=(235, 235, 235), width=2)
    else:
        para = "".join(rnd.choice(ZH_MSGS) + "，" for _ in range(60))
        for ln in wrap(para, font(40 * S), W - 120 * S, d):
            if y > H - 100 * S:
                break
            d.text((60 * S, y), ln, font=font(40 * S), fill=(30, 30, 30))
            y += int(64 * S)
    return img, dict(is_chat=False, app=kind, theme="light", group=False, lang="zh", size=[W, H], long=False, boxes=boxes, lines=d.lines)


SAVED = []


def save(name, img, truth, jpeg=None):
    SAVED.append((name, img, json.loads(json.dumps(truth))))
    _save(OUT, name, img, truth, jpeg)


def _save(out, name, img, truth, jpeg=None):
    OUT = out
    path = os.path.join(OUT, name + (".jpg" if jpeg else ".png"))
    if jpeg:
        img.save(path, quality=jpeg)
    else:
        img.save(path, optimize=True)
    truth["file"] = os.path.basename(path)
    with open(os.path.join(OUT, name + ".json"), "w") as f:
        json.dump(truth, f, ensure_ascii=False, indent=1)
    # 同一份真值的制表符格式，给 app 的单测读（不用 JSON 库）：
    #   chat <0|1> / line x0 y0 x1 y1 原文（模拟 OCR 行）/ avatar x0 y0 x1 y1 / name x0 y0 x1 y1 原文 / title x0 y0 x1 y1 原文
    with open(os.path.join(OUT, name + ".tsv"), "w") as f:
        f.write(f"chat\t{int(truth['is_chat'])}\n")
        for ln in truth["lines"]:
            f.write("line\t" + "\t".join(str(int(v)) for v in ln[:4]) + "\t" + ln[4].replace("\t", " ") + "\n")
        for b in truth["boxes"]:
            f.write(b["kind"] + "\t" + "\t".join(str(int(b[k])) for k in ("x0", "y0", "x1", "y1")) +
                    ("\t" + b["text"] if "text" in b else "") + "\n")


def main():
    n = 0
    for app in SKINS:
        for theme in ("light", "dark"):
            for group in (False, True):
                for rep in range(2):
                    n += 1
                    big = rep == 1 and app == "imessage"
                    W, H = (1170, 2532) if big else (1080, 2340)
                    lang = "en" if app in ("telegram", "whatsapp", "imessage") and rep == 1 else "zh"
                    img, t = chat_page(n, app, theme, group, W, H, lang=lang)
                    jpeg = 70 if rep == 1 and app in ("wechat", "qq") else None
                    if rep == 1 and app == "qq":   # 发出去又存下来的缩小图
                        img = img.resize((720, int(img.height * 720 / img.width)), Image.LANCZOS)
                        k = 720 / t["size"][0]
                        t["size"] = [img.width, img.height]
                        for b in t["boxes"]:
                            for key in ("x0", "y0", "x1", "y1"):
                                b[key] = round(b[key] * k)
                        t["lines"] = [[round(v * k) for v in ln[:4]] + ln[4:] for ln in t["lines"]]
                    save(f"chat-{n:03d}-{app}-{theme}-{'group' if group else 'single'}", img, t, jpeg)
    for app in ("wechat", "qq"):   # 长截图
        n += 1
        img, t = chat_page(n, app, "light", True, long=True)
        save(f"chat-{n:03d}-{app}-light-group-long", img, t)
    for theme in ("light", "dark"):   # 单列列表式（Discord / Slack）
        n += 1
        img, t = discord_page(n, theme)
        save(f"chat-{n:03d}-discord-{theme}-group", img, t)
    for i, kind in enumerate(["contacts", "chatlist", "settings", "shop", "article"]):
        img, t = negative_page(i, kind)
        save(f"neg-{i + 1:03d}-{kind}", img, t)
    print("wrote", n, "chat pages + 5 negatives to", OUT)
    if FIXTURES:
        export_fixtures(FIXTURES)


def export_fixtures(dst):
    """每种皮肤 × 深浅色 × 单聊群聊各取一张（各组的第一张，原尺寸、PNG），加上 Discord、长截图和非聊天页，缩到 540 宽。
    微信长截图另存一张原尺寸的到 dst 旁边的 chat-native/。"""
    os.makedirs(dst, exist_ok=True)
    native = os.path.join(os.path.dirname(os.path.abspath(dst)), "chat-native")
    seen = set()
    for name, img, t in SAVED:
        key = (t["app"], t["theme"], t["group"], t.get("long"))
        if key in seen:
            continue
        seen.add(key)
        if t.get("long") and t["app"] == "wechat":
            os.makedirs(native, exist_ok=True)
            _save(native, name, img, json.loads(json.dumps(t)))
            os.remove(os.path.join(native, name + ".json"))
        k = 540 / img.width
        small = img.resize((540, round(img.height * k)), Image.LANCZOS)
        for b in t["boxes"]:
            for c in ("x0", "y0", "x1", "y1"):
                b[c] = round(b[c] * k)
        t["lines"] = [[round(v * k) for v in ln[:4]] + ln[4:] for ln in t["lines"]]
        t["size"] = [small.width, small.height]
        _save(dst, name, small, t)
        os.remove(os.path.join(dst, name + ".json"))
    print("wrote", len(seen), "fixtures to", dst)


if __name__ == "__main__":
    main()
