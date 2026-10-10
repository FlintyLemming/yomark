"""英文合成测试页（全是编的假数据），格式与 make_samples.py 相同：每页一张 PNG + 同名真值 JSON。

  find  = 必须找出的隐私（类型 + 原文）。多行的英文地址按行各算一处：规则逐行跑，每一行都要遮到；
  avoid = 不该被当成隐私的文字（品牌、商品、店名、职位）。

字体用 Inter（接近 Roboto / SF 的界面无衬线体），运单用 DejaVu Sans Mono。
输出到 $MODEL_EVAL_OUT（默认 out-en/）下的 samples/ 和 truth/。
"""
import json, os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.environ.get("MODEL_EVAL_OUT", os.path.join(os.path.dirname(os.path.abspath(__file__)), "out-en"))
OUT, TRUTH = ROOT + "/samples", ROOT + "/truth"
os.makedirs(OUT, exist_ok=True)
os.makedirs(TRUTH, exist_ok=True)
INTER = "/usr/share/fonts/opentype/inter/Inter-Regular.otf"
INTER_SEMI = "/usr/share/fonts/opentype/inter/Inter-SemiBold.otf"
MONO = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"
W, H = 1080, 2340
GREY, DARK, BLUE, GREEN = (110, 110, 115), (20, 20, 22), (10, 100, 230), (0, 140, 70)


def font(size, path=INTER):
    return ImageFont.truetype(path, size)


def page(name, rows, find, avoid, bubbles=()):
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    for (x, y, w, h, fill) in bubbles:
        d.rounded_rectangle((x, y, x + w, y + h), radius=28, fill=fill)
    for row in rows:
        x, y, text, size, color = row[:5]
        d.text((x, y), text, font=font(size, row[5] if len(row) > 5 else INTER), fill=color)
    img.save(f"{OUT}/{name}.png")
    with open(f"{TRUTH}/{name}.json", "w") as f:
        json.dump({"find": find, "avoid": avoid, "rows": [r[2] for r in rows]}, f, ensure_ascii=False, indent=1)


def item(kind, text, **kw):
    return {"kind": kind, "text": text, **kw}


# A. 美国电商订单：「Shipping Address」单独一行在上，名字、两行地址、电话各占一行
page("en-order", [
    (60, 40, "9:41", 40, DARK), (880, 40, "5G 82%", 36, DARK),
    (60, 170, "<  Order Details", 46, DARK, INTER_SEMI),
    (60, 290, "Order placed  October 3, 2026", 38, GREY),
    (60, 350, "Order # 112-4839201-5573012", 38, GREY),
    (60, 490, "Shipping Address", 44, DARK, INTER_SEMI),
    (60, 570, "Jennifer Walsh", 40, DARK),
    (60, 630, "2847 Maple Grove Dr, Apt 12C", 40, DARK),
    (60, 690, "Columbus, OH 43215", 40, DARK),
    (60, 750, "United States", 40, DARK),
    (60, 810, "Phone: (614) 293-7781", 40, DARK),
    (60, 960, "Payment Method", 44, DARK, INTER_SEMI),
    (60, 1040, "Visa ending in 4417", 40, DARK),
    (60, 1190, "Arriving Thursday, Oct 9", 44, GREEN, INTER_SEMI),
    (60, 1270, "Anker 737 Power Bank (PowerCore 24K)", 38, DARK),
    (60, 1325, "Sold by: AnkerDirect", 34, GREY), (860, 1270, "$89.99", 40, DARK),
    (60, 1420, "Kindle Paperwhite Signature Edition", 38, DARK),
    (60, 1475, "Sold by: Amazon.com Services LLC", 34, GREY), (860, 1420, "$189.99", 40, DARK),
], find=[item("人名", "Jennifer Walsh"), item("地址", "2847 Maple Grove Dr, Apt 12C"),
         item("地址", "Columbus, OH 43215"), item("电话", "(614) 293-7781")],
   avoid=["Anker 737 Power Bank", "AnkerDirect", "Kindle Paperwhite", "Amazon.com Services", "$89.99", "PowerCore"])

# B. 聊天：人名、地址都在句子里；顶栏是对方名字
bub = [(60, 300, 860, 120, (233, 233, 235)), (420, 460, 600, 120, (10, 132, 255)),
       (60, 620, 900, 175, (233, 233, 235)), (60, 835, 900, 175, (233, 233, 235)),
       (300, 1050, 720, 120, (10, 132, 255))]
WHITE = (255, 255, 255)
page("en-chat", [
    (60, 40, "21:03", 40, DARK), (880, 40, "LTE 64%", 36, DARK),
    (60, 160, "<  Emily Carter", 46, DARK, INTER_SEMI),
    (95, 335, "Are you coming on Saturday?", 38, DARK),
    (455, 495, "Yes! What's the address?", 38, WHITE),
    (95, 640, "418 Westbrook Ave, Unit 3,", 38, DARK),
    (95, 700, "Brooklyn, NY 11216", 38, DARK),
    (95, 855, "Call Michael Chen if you get lost:", 38, DARK),
    (95, 915, "(347) 286-1190", 38, DARK),
    (335, 1085, "Thanks, see you at Starbucks", 38, WHITE),
], bubbles=bub,
   find=[item("人名", "Emily Carter"), item("地址", "418 Westbrook Ave, Unit 3"), item("地址", "Brooklyn, NY 11216"),
         item("人名", "Michael Chen"), item("电话", "(347) 286-1190")],
   avoid=["Saturday", "Starbucks"])

# C. 邮件：发件人行带邮箱，正文里提到另一个人，签名档里有公司地址和手机
page("en-email", [
    (60, 40, "10:12", 40, DARK), (880, 40, "Wi-Fi 77%", 36, DARK),
    (60, 170, "Re: Contract renewal for Q4", 50, DARK, INTER_SEMI),
    (60, 300, "Sarah Johnson <sarah.johnson@northwind.io>", 36, DARK, INTER_SEMI),
    (60, 355, "to me", 34, GREY), (860, 300, "Oct 7", 34, GREY),
    (60, 470, "Hi David,", 40, DARK),
    (60, 560, "Please find the signed agreement attached.", 38, DARK),
    (60, 615, "Let me know if Mark Thompson needs anything", 38, DARK),
    (60, 670, "else from our side before Friday.", 38, DARK),
    (60, 790, "Best regards,", 38, DARK),
    (60, 850, "Sarah Johnson", 38, DARK),
    (60, 905, "Senior Account Manager, Northwind Traders", 36, GREY),
    (60, 960, "500 Howard St, Suite 300", 36, GREY),
    (60, 1015, "San Francisco, CA 94105", 36, GREY),
    (60, 1070, "Mobile: +1 415 902 3318", 36, GREY),
], find=[item("人名", "Sarah Johnson"), item("邮箱", "sarah.johnson@northwind.io"), item("人名", "David"), item("人名", "Mark Thompson"),
         item("地址", "500 Howard St, Suite 300"), item("地址", "San Francisco, CA 94105"),
         item("电话", "+1 415 902 3318")],
   avoid=["Northwind Traders", "Senior Account Manager", "Contract renewal", "Friday"])

# D. 酒店订单：字段名在左、值在右；住客是拼音名；酒店地址是公开信息，不算
page("en-hotel", [
    (60, 40, "09:41", 40, DARK), (880, 40, "5G 91%", 36, DARK),
    (60, 160, "<  Booking confirmed", 46, DARK, INTER_SEMI),
    (60, 280, "The Hoxton, Shoreditch", 56, DARK, INTER_SEMI),
    (60, 370, "Double Room · 2 nights · Breakfast included", 36, GREY),
    (60, 430, "Fri 17 Oct - Sun 19 Oct 2026", 36, GREY),
    (60, 580, "Guest name", 38, GREY), (420, 580, "Wei Zhang", 40, DARK),
    (60, 660, "Email", 38, GREY), (420, 660, "zhangwei.travel@gmail.com", 40, DARK),
    (60, 740, "Phone", 38, GREY), (420, 740, "+86 186 1234 5678", 40, DARK),
    (60, 820, "Confirmation", 38, GREY), (420, 820, "4021.338.917", 40, DARK),
    (60, 960, "Total price", 38, GREY), (800, 960, "£412.00", 48, DARK, INTER_SEMI),
], find=[item("人名", "Wei Zhang"), item("邮箱", "zhangwei.travel@gmail.com"), item("电话", "+86 186 1234 5678")],
   avoid=["The Hoxton", "Shoreditch", "Double Room", "£412.00"])

# E. 英国快递：「Delivering to」单独一行，下面是名字和英国地址（含邮编）
page("en-uk-delivery", [
    (60, 40, "14:20", 40, DARK), (880, 40, "4G 58%", 36, DARK),
    (60, 170, "Your parcel is on its way", 52, DARK, INTER_SEMI),
    (60, 270, "Estimated delivery 10:15 - 11:15", 38, GREY),
    (60, 400, "Delivering to", 38, GREY),
    (60, 460, "Olivia Bennett", 42, DARK, INTER_SEMI),
    (60, 525, "Flat 4, 27 Kingsley Road", 40, DARK),
    (60, 585, "Manchester M14 6PL", 40, DARK),
    (60, 730, "Your driver today is Tom", 38, DARK),
    (60, 790, "Parcel from ASOS", 38, DARK),
    (60, 920, "Mobile  07700 900123", 38, DARK),
], find=[item("人名", "Olivia Bennett"), item("地址", "Flat 4, 27 Kingsley Road"), item("地址", "Manchester M14 6PL"),
         item("电话", "07700 900123")],
   avoid=["ASOS", "Estimated delivery"])

# F. 美国运单：全大写、等宽体
page("en-label", [
    (60, 120, "USPS PRIORITY MAIL", 52, DARK, MONO),
    (60, 300, "FROM: ACME SUPPLY CO", 38, DARK, MONO),
    (60, 350, "1200 INDUSTRIAL PKWY", 38, DARK, MONO),
    (60, 400, "RENO NV 89502", 38, DARK, MONO),
    (60, 560, "SHIP TO:", 44, DARK, MONO),
    (60, 630, "MARIA GARCIA", 48, DARK, MONO),
    (60, 700, "4567 OAK AVE STE 210", 48, DARK, MONO),
    (60, 770, "LOS ANGELES CA 90012-3456", 48, DARK, MONO),
    (60, 960, "USPS TRACKING # EP", 36, DARK, MONO),
    (60, 1010, "9405 5112 0620 1234 5678 90", 40, DARK, MONO),
], find=[item("人名", "MARIA GARCIA"), item("地址", "4567 OAK AVE STE 210"), item("地址", "LOS ANGELES CA 90012-3456")],
   avoid=["ACME SUPPLY CO", "PRIORITY MAIL"])

# G. 拼音地址：国际站的收货地址，中国用户最常见的「英文」地址
page("en-pinyin", [
    (60, 40, "20:15", 40, DARK), (880, 40, "5G 80%", 36, DARK),
    (60, 160, "<  Shipping address", 46, DARK, INTER_SEMI),
    (60, 300, "Liu Yang", 42, DARK, INTER_SEMI), (500, 306, "+86 138 1234 6612", 38, GREY),
    (60, 370, "Room 1204, Building 2, No. 18 Zhongshan Road", 36, DARK),
    (60, 425, "Xuanwu District, Nanjing, Jiangsu 210018", 36, DARK),
    (60, 480, "China", 36, DARK),
    (60, 620, "Default", 34, BLUE), (840, 620, "Edit", 36, BLUE),
    (60, 760, "Order total  US $46.20", 40, DARK),
    (60, 830, "Baseus 65W GaN Charger", 38, DARK),
], find=[item("人名", "Liu Yang"), item("电话", "+86 138 1234 6612"),
         item("地址", "Room 1204, Building 2, No. 18 Zhongshan Road"),
         item("地址", "Xuanwu District, Nanjing, Jiangsu 210018")],
   avoid=["Baseus", "US $46.20"])

# H. 账户资料页：字段名在上、值在下
page("en-profile", [
    (60, 40, "11:08", 40, DARK), (880, 40, "5G 66%", 36, DARK),
    (60, 160, "<  Personal info", 46, DARK, INTER_SEMI),
    (60, 300, "Full name", 34, GREY), (60, 350, "Christopher Nguyen", 42, DARK),
    (60, 460, "Date of birth", 34, GREY), (60, 510, "March 14, 1991", 42, DARK),
    (60, 620, "Email", 34, GREY), (60, 670, "chris.nguyen@outlook.com", 42, DARK),
    (60, 780, "Home address", 34, GREY), (60, 830, "1520 Lakeview Blvd", 42, DARK),
    (60, 890, "Seattle, WA 98109", 42, DARK),
    (60, 1000, "Language", 34, GREY), (60, 1050, "English (United States)", 42, DARK),
], find=[item("人名", "Christopher Nguyen"), item("邮箱", "chris.nguyen@outlook.com"),
         item("地址", "1520 Lakeview Blvd"), item("地址", "Seattle, WA 98109")],
   avoid=["English (United States)", "Personal info"])

# I. 小字号：收件箱列表。12sp 上下（1080 宽的屏上约 30px，降采样后只剩 15px），浅灰摘要
LIGHT = (140, 140, 145)
inbox = [("Rachel Kim", "Lease renewal - 2B Garden Court", "Hi, attaching the renewal for 2B Garden Court, 14 Elm St"),
         ("Amazon.com", "Your order has shipped", "Arriving tomorrow. Track your package with UPS 1Z999AA1"),
         ("Thomas Becker", "Re: Dinner on Friday?", "Sounds good! Tell Anna we will pick her up at 7"),
         ("LinkedIn", "You appeared in 12 searches", "See who's looking at your profile this week"),
         ("Priya Patel", "Invoice #2026-118", "Please send payment to 77 Harbor Rd, Portland, ME 04101")]
rows = [(60, 40, "8:02", 34, DARK), (900, 40, "5G 71%", 30, DARK), (60, 150, "Inbox", 54, DARK, INTER_SEMI)]
for i, (who, subj, snip) in enumerate(inbox):
    y = 280 + i * 190
    rows += [(60, y, who, 32, DARK, INTER_SEMI), (900, y + 2, "Oct %d" % (8 - i), 28, LIGHT),
             (60, y + 48, subj, 30, DARK), (60, y + 92, snip, 28, LIGHT)]
page("en-inbox", rows,
     find=[item("人名", "Rachel Kim"), item("地址", "2B Garden Court, 14 Elm St"), item("人名", "Thomas Becker"),
           item("人名", "Priya Patel"), item("地址", "77 Harbor Rd, Portland, ME 04101")],
     avoid=["Amazon.com", "LinkedIn", "Lease renewal", "Dinner on Friday"])

# J. 小字号：打车收据，上下车地点是家和公司
page("en-receipt", [
    (60, 40, "18:47", 34, DARK), (900, 40, "LTE 40%", 30, DARK),
    (60, 150, "Thanks for riding, Kevin", 48, DARK, INTER_SEMI),
    (60, 240, "Total", 34, DARK, INTER_SEMI), (880, 240, "$23.18", 34, DARK, INTER_SEMI),
    (60, 300, "Trip fare", 30, LIGHT), (890, 300, "$18.40", 30, LIGHT),
    (60, 345, "Booking fee", 30, LIGHT), (900, 345, "$2.75", 30, LIGHT),
    (60, 390, "Tip", 30, LIGHT), (900, 390, "$2.03", 30, LIGHT),
    (60, 500, "Pickup 5:52 PM", 28, LIGHT),
    (60, 540, "2210 Fillmore St, San Francisco, CA 94115", 30, DARK),
    (60, 620, "Dropoff 6:14 PM", 28, LIGHT),
    (60, 660, "1 Market St, San Francisco, CA 94105", 30, DARK),
    (60, 780, "You rode with Carlos M.", 30, DARK),
    (60, 830, "UberX  ·  4.2 mi  ·  22 min", 28, LIGHT),
    (60, 900, "Paid with Visa ••••4417", 28, LIGHT),
], find=[item("人名", "Kevin"), item("地址", "2210 Fillmore St, San Francisco, CA 94115"),
         item("地址", "1 Market St, San Francisco, CA 94105")],
   avoid=["UberX", "$23.18", "Trip fare", "Booking fee"])

# ---------- 留出页（en-h-）：原型规则写完之后才画的，没有照着它们调过，用来看原型是不是只会做练习题 ----------

# K. 通讯录联系人：字段名是小写的 mobile / home / email，值在下一行
page("en-h-contact", [
    (60, 40, "16:20", 40, DARK), (880, 40, "5G 77%", 36, DARK),
    (60, 160, "<  Contacts", 44, BLUE),
    (330, 300, "Daniel Ortiz", 60, DARK, INTER_SEMI),
    (60, 480, "mobile", 32, GREY), (60, 525, "+1 (512) 448-2290", 40, BLUE),
    (60, 640, "home", 32, GREY), (60, 685, "3300 Red River St", 40, BLUE),
    (60, 740, "Austin, TX 78705", 40, BLUE),
    (60, 860, "email", 32, GREY), (60, 905, "d.ortiz@fastmail.com", 40, BLUE),
    (60, 1020, "notes", 32, GREY), (60, 1065, "Met at SXSW 2025", 40, DARK),
], find=[item("人名", "Daniel Ortiz"), item("电话", "+1 (512) 448-2290"), item("地址", "3300 Red River St"),
         item("地址", "Austin, TX 78705"), item("邮箱", "d.ortiz@fastmail.com")],
   avoid=["SXSW", "Contacts"])

# L. 结账表单：德国地址，字段名在上、值在下，名和姓左右并排
page("en-h-checkout", [
    (60, 40, "12:05", 40, DARK), (880, 40, "Wi-Fi 90%", 36, DARK),
    (60, 160, "Contact information", 44, DARK, INTER_SEMI),
    (60, 250, "Email", 30, GREY), (60, 290, "nina.hoffmann@web.de", 38, DARK),
    (60, 420, "Shipping address", 44, DARK, INTER_SEMI),
    (60, 510, "First name", 30, GREY), (560, 510, "Last name", 30, GREY),
    (60, 550, "Nina", 38, DARK), (560, 550, "Hoffmann", 38, DARK),
    (60, 650, "Address", 30, GREY), (60, 690, "Torstrasse 112", 38, DARK),
    (60, 790, "Postal code", 30, GREY), (560, 790, "City", 30, GREY),
    (60, 830, "10119", 38, DARK), (560, 830, "Berlin", 38, DARK),
    (60, 960, "Continue to shipping", 40, BLUE, INTER_SEMI),
], find=[item("邮箱", "nina.hoffmann@web.de"), item("人名", "Nina"), item("人名", "Hoffmann"),
         item("地址", "Torstrasse 112"), item("地址", "10119")],
   avoid=["Continue to shipping", "Contact information"])

# M. 群聊：地址、人名都在句子里
bub = [(60, 300, 920, 175, (233, 233, 235)), (60, 515, 700, 120, (233, 233, 235)),
       (380, 675, 640, 120, (37, 211, 102))]
page("en-h-group", [
    (60, 40, "19:31", 40, DARK), (880, 40, "4G 52%", 36, DARK),
    (60, 160, "<  Family", 46, DARK, INTER_SEMI),
    (95, 315, "Grandma moved! New address is", 38, DARK),
    (95, 370, "52 Willow Lane, Bath BA1 5LT", 38, DARK),
    (95, 530, "~ Rob Fletcher", 30, GREEN),
    (95, 570, "I can drive her on Sunday", 38, DARK),
    (415, 710, "Thanks Rob, you're a star", 38, WHITE),
    (200, 860, "Sophie Lambert joined using an invite link", 30, GREY),
], find=[item("地址", "52 Willow Lane, Bath BA1 5LT"), item("人名", "Rob Fletcher"),
         item("人名", "Sophie Lambert")],
   avoid=["Family", "Sunday"])

# N. 机票：航司格式的「姓/名 称谓」全大写
page("en-h-flight", [
    (60, 40, "07:12", 40, DARK), (880, 40, "5G 63%", 36, DARK),
    (60, 160, "<  Trip details", 46, DARK, INTER_SEMI),
    (60, 280, "PVG  →  LHR", 60, DARK, INTER_SEMI),
    (60, 380, "Thu 23 Oct · VS251 · Economy", 36, GREY),
    (60, 520, "Passenger", 32, GREY), (60, 565, "ZHANG/WEI MR", 42, DARK, INTER_SEMI),
    (60, 660, "Booking reference", 32, GREY), (60, 705, "QX7K2M", 42, DARK),
    (60, 800, "Seat", 32, GREY), (60, 845, "32A", 42, DARK),
    (60, 940, "Emergency contact", 32, GREY), (60, 985, "Laura Mills  +44 7911 123456", 40, DARK),
], find=[item("人名", "ZHANG/WEI MR"), item("人名", "Laura Mills"), item("电话", "+44 7911 123456")],
   avoid=["PVG", "LHR", "Economy", "VS251"])

# O. 外卖：骑手名、送达地址、门牌备注
page("en-h-food", [
    (60, 40, "13:48", 40, DARK), (880, 40, "5G 88%", 36, DARK),
    (60, 170, "Your Dasher, Marcus, is on", 46, DARK, INTER_SEMI),
    (60, 230, "the way", 46, DARK, INTER_SEMI),
    (60, 340, "Arrives 2:05 - 2:15 PM", 36, GREY),
    (60, 480, "Deliver to", 32, GREY),
    (60, 525, "1188 Mission St Apt 1504", 38, DARK),
    (60, 580, "San Francisco, CA 94103", 38, DARK),
    (60, 680, "Leave it at my door · Gate code 4471#", 34, GREY),
    (60, 820, "Order from Chipotle Mexican Grill", 38, DARK),
    (60, 880, "2 items · $27.40", 34, GREY),
], find=[item("地址", "1188 Mission St Apt 1504"), item("地址", "San Francisco, CA 94103"), item("人名", "Marcus")],
   avoid=["Chipotle", "$27.40"])

print("samples:", sorted(os.listdir(OUT)))
