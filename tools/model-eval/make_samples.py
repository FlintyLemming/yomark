"""合成测试页（全是编的假数据）+ 条码探针图。

每张页 1080x2340，文泉驿正黑渲染；同名 .json 记真值：
  find  = 必须找出的隐私（类型 + 原文）
  avoid = 不该被当成隐私的文字（店名、站名、商品名、价格…）
"""
import json, os, random
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import segno

ROOT = os.environ.get("MODEL_EVAL_OUT", os.path.join(os.path.dirname(os.path.abspath(__file__)), "out"))
OUT = ROOT + "/samples"
TRUTH = ROOT + "/truth"
os.makedirs(TRUTH, exist_ok=True)
os.makedirs(OUT, exist_ok=True)
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
W, H = 1080, 2340
GREY, DARK, BLUE, ORANGE = (120, 120, 120), (25, 25, 25), (40, 100, 220), (255, 100, 30)


def font(size):
    return ImageFont.truetype(FONT, size)


def page(name, rows, find, avoid, bubbles=()):
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    for (x, y, w, h, fill) in bubbles:
        d.rounded_rectangle((x, y, x + w, y + h), radius=18, fill=fill)
    for (x, y, text, size, color) in rows:
        d.text((x, y), text, font=font(size), fill=color)
    img.save(f"{OUT}/{name}.png")
    with open(f"{TRUTH}/{name}.json", "w") as f:
        json.dump({"find": find, "avoid": avoid}, f, ensure_ascii=False, indent=1)


# A. 火车票：名字没有字段名，证件号打了星
page("ticket", [
    (60, 40, "20:15", 40, DARK), (900, 40, "5G 80", 36, DARK),
    (60, 160, "<  订单详情", 44, DARK),
    (60, 280, "出票成功", 72, DARK),
    (60, 420, "G7032   南京南 → 上海虹桥", 52, DARK),
    (60, 510, "10月12日 周日  08:20开  09:58到", 40, GREY),
    (60, 590, "二等座  05车12F号", 44, DARK),
    (60, 760, "使用电子客票 刷证进站", 48, DARK),
    (60, 880, "李思雨", 50, DARK), (260, 888, "成人票", 34, BLUE),
    (60, 950, "3201**********1234", 38, GREY),
    (820, 900, "¥139.5", 48, ORANGE),
    (60, 1120, "检票口 A12", 44, DARK),
    (60, 1220, "温馨提示：请携带购票时使用的有效身份证件原件", 34, GREY),
], find=[{"kind": "人名", "text": "李思雨"}, {"kind": "号码", "text": "3201**********1234"}],
   avoid=["南京南", "上海虹桥", "G7032", "二等座", "¥139.5", "A12"])

# B. 聊天：人名、地址都在句子里
bub = [(160, 330, 820, 110, (240, 240, 240)), (380, 480, 600, 90, (149, 236, 105)),
       (160, 610, 860, 150, (240, 240, 240)), (160, 800, 820, 110, (240, 240, 240)),
       (300, 950, 680, 90, (149, 236, 105))]
page("chat", [
    (60, 40, "21:03", 40, DARK), (900, 40, "4G 62", 36, DARK),
    (60, 160, "<  陈晓峰", 48, DARK),
    (60, 340, "明", 40, BLUE),
    (190, 350, "明天上午帮我去菜鸟驿站拿个快递", 38, DARK),
    (190, 395, "取件码 6-3-2208", 38, DARK),
    (410, 505, "好的，拿了放哪儿？", 38, DARK),
    (190, 625, "送到梅园新村12栋3单元501，", 38, DARK),
    (190, 680, "到了打我电话13912345678", 38, DARK),
    (190, 820, "对了，转告周振宇周三的会", 38, DARK),
    (190, 865, "改到下午三点", 38, DARK),
    (330, 975, "收到，我跟王经理说一声", 38, DARK),
], bubbles=bub,
   find=[{"kind": "人名", "text": "陈晓峰"}, {"kind": "人名", "text": "周振宇"},
         {"kind": "地址", "text": "梅园新村12栋3单元501"}, {"kind": "电话", "text": "13912345678"},
         {"kind": "号码", "text": "6-3-2208"}, {"kind": "人名", "text": "王经理"}],
   avoid=["菜鸟驿站", "周三", "下午三点"])

# C. 订单：有字段名，外加一堆店名商品名当干扰
page("order", [
    (60, 40, "14:30", 40, DARK), (900, 40, "5G 77", 36, DARK),
    (60, 160, "<  订单详情", 44, DARK),
    (60, 260, "交易成功", 64, DARK),
    (60, 400, "收货人：刘洋  138****6612", 42, DARK),
    (60, 470, "收货地址：江苏省南京市玄武区中山路18号2栋1204室", 36, DARK),
    (60, 620, "优衣库官方旗舰店 >", 44, DARK),
    (60, 700, "男装 圆领短袖T恤 白色 L", 40, DARK), (860, 700, "¥79.00", 40, DARK),
    (60, 780, "实付款", 40, GREY), (860, 780, "¥79.00", 40, ORANGE),
    (60, 920, "订单编号  4302918877160512345", 36, GREY), (900, 920, "复制", 36, BLUE),
    (60, 980, "支付宝交易号  2026093022001412345678901234", 34, GREY),
    (60, 1040, "创建时间  2026-09-30 14:22:05", 36, GREY),
    (60, 1160, "快递员 赵师傅 已为您签收", 40, DARK),
], find=[{"kind": "人名", "text": "刘洋", "alias": ["文洋"]}, {"kind": "电话", "text": "138****6612"},
         {"kind": "地址", "text": "江苏省南京市玄武区中山路18号2栋1204室"},
         {"kind": "号码", "text": "4302918877160512345"},
         {"kind": "号码", "text": "2026093022001412345678901234"}, {"kind": "人名", "text": "赵师傅"}],
   avoid=["优衣库官方旗舰店", "圆领短袖T恤", "¥79.00", "2026-09-30 14:22:05"])

# D. 酒店订单：「入住人」不在规则的字段名表里；酒店名、房型是干扰
page("hotel", [
    (60, 40, "09:41", 40, DARK), (900, 40, "5G 91", 36, DARK),
    (60, 160, "<  订单已确认", 44, DARK),
    (60, 280, "南京金陵饭店", 60, DARK),
    (60, 380, "豪华大床房 · 1间 · 含双早", 40, GREY),
    (60, 450, "10月12日 - 10月13日  共1晚", 40, GREY),
    (60, 600, "入住人", 40, GREY), (300, 600, "张伟", 44, DARK),
    (60, 680, "联系手机", 40, GREY), (300, 680, "+86 186 1234 5678", 40, DARK),
    (60, 760, "酒店地址", 40, GREY), (300, 760, "南京市鼓楼区汉中路2号", 40, DARK),
    (60, 900, "在线付", 40, GREY), (860, 900, "¥868", 48, ORANGE),
], find=[{"kind": "人名", "text": "张伟"}, {"kind": "电话", "text": "+86 186 1234 5678"}],
   avoid=["南京金陵饭店", "豪华大床房", "¥868"])

# ---------- 条码探针 ----------
PROBE = ROOT + "/probes"
os.makedirs(PROBE, exist_ok=True)

L = ["0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011"]
G = ["0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101", "0010001", "0001001", "0010111"]
R = ["1110010", "1100110", "1101100", "1000010", "1011100", "1001110", "1010000", "1000100", "1001000", "1110100"]
PARITY = ["LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"]


def ean13(digits12):
    d = [int(c) for c in digits12]
    check = (10 - (sum(d[i] * (3 if i % 2 else 1) for i in range(12)) % 10)) % 10
    d.append(check)
    bits = "101"
    for i, p in enumerate(PARITY[d[0]]):
        bits += (L if p == "L" else G)[d[i + 1]]
    bits += "01010"
    for i in range(7, 13):
        bits += R[d[i]]
    bits += "101"
    m = 4
    img = Image.new("RGB", ((len(bits) + 22) * m, 260), "white")
    dr = ImageDraw.Draw(img)
    for i, b in enumerate(bits):
        if b == "1":
            dr.rectangle(((i + 11) * m, 20, (i + 12) * m - 1, 200), fill="black")
    dr.text((40, 205), "".join(map(str, d)), font=font(40), fill="black")
    return img


ean13("690123456789").save(f"{PROBE}/pos-ean13.png")
segno.make("https://example.com/pay/ORDER-20261003", error="m").save(f"{PROBE}/pos-qr.png", scale=8, border=4)
qr = Image.open(f"{PROBE}/pos-qr.png").convert("RGB")
canvas = Image.new("RGB", (qr.width + 200, qr.height + 200), "white")
canvas.paste(qr.rotate(25, expand=True, fillcolor="white").resize((qr.width, qr.height)), (100, 100))
canvas.filter(ImageFilter.GaussianBlur(1.2)).save(f"{PROBE}/pos-qr-tilted-blurred.png")

random.seed(7)
plaid = Image.new("RGB", (600, 400), (200, 40, 40))
pd = ImageDraw.Draw(plaid)
for x in range(0, 600, 60):
    pd.rectangle((x, 0, x + 18, 400), fill=(30, 30, 90))
    pd.rectangle((x + 30, 0, x + 34, 400), fill=(240, 220, 120))
for y in range(0, 400, 60):
    pd.rectangle((0, y, 600, y + 18), fill=(30, 30, 90))
plaid.filter(ImageFilter.GaussianBlur(0.8)).save(f"{PROBE}/neg-plaid.png")

stripes = Image.new("RGB", (600, 400), "white")
sd = ImageDraw.Draw(stripes)
for x in range(0, 600, 14):
    sd.rectangle((x, 0, x + 6 + (x // 14) % 3, 400), fill=(20, 20, 20))
stripes.save(f"{PROBE}/neg-stripes-shirt.png")
# 仓库里现成的两张：PP-OCR 单测用的合成快递页、条码单测用的真二维码
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../.."))
Image.open(f"{REPO}/app/src/test/resources/ppocr/logistics-page.png").save(f"{OUT}/logistics-page.png")
with open(f"{TRUTH}/logistics-page.json", "w") as f:
    json.dump({"find": [{"kind": "人名", "text": "王小明"}, {"kind": "电话", "text": "86-139****5678"},
                        {"kind": "地址", "text": "长江路88号阳光新村5幢302室"}, {"kind": "号码", "text": "3-2-1234"}],
               "avoid": ["申通快递", "天猫", "乐事旗舰店", "薯片多口味", "¥99.9", "09-29 12:30", "号码保护中"]},
              f, ensure_ascii=False, indent=1)
Image.open(f"{REPO}/app/src/androidTest/assets/barcodes/qr-sample.png").convert("RGB").save(f"{PROBE}/pos-qr-repo-sample.png")
print("samples:", sorted(os.listdir(OUT)))
print("probes:", sorted(os.listdir(PROBE)))
