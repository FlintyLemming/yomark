"""把页面描述（JSON）画成截图，给英文人名、地址识别做基准。

用法：python make_ner_bench.py <页面描述.json> [<页面描述.json> ...]

页面描述由几个互不知情的代理按 app 类别分头写、再由另一个代理逐页核对真值（见 docs/english-recognition-research.md 第二轮），
写的人没看过原型规则，避免「照着规则出题」。格式：[{group, pages: [{name, app, region, theme, rows: [{x, y, size, role, text}],
find: [{kind, text}], avoid: [str], neutral: [str]}]}]。

每页写出：
  samples/en-n-<组>-<名>.png    1080×2340 截图（Roboto，三成 Inter）；
  truth/<同名>.json             find / avoid / neutral / rows，格式同 make_en_samples.py（neutral：遮不遮都行的公开信息）；
  oracle/<同名>.json            「完美 OCR」：每行原文 + 框，按设备口径（降采样到 540 宽）给坐标、同一排的几段拼成一行，
                                格式同 OcrBench 的输出——只比较认人名地址的本事，不掺 OCR 的错。
真值里找不到出处的条目（不是任何一行的子串）丢掉并打印出来。输出到 $MODEL_EVAL_OUT（默认 out-ner/）。
"""
import json, os, random, sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.environ.get("MODEL_EVAL_OUT", os.path.join(os.path.dirname(os.path.abspath(__file__)), "out-ner"))
FONTS = os.environ.get("FONTS_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "fonts"))
for d in ("samples", "truth", "oracle"):
    os.makedirs(f"{ROOT}/{d}", exist_ok=True)
W, H = 1080, 2340
THEMES = {
    "light": {"bg": (255, 255, 255), "primary": (28, 28, 30), "secondary": (110, 110, 115), "label": (110, 110, 115),
              "link": (0, 122, 255), "button": (0, 122, 255), "title": (28, 28, 30),
              "bubble-in": ((233, 233, 235), (28, 28, 30)), "bubble-out": ((10, 132, 255), (255, 255, 255))},
    "dark": {"bg": (0, 0, 0), "primary": (245, 245, 247), "secondary": (152, 152, 159), "label": (152, 152, 159),
             "link": (10, 132, 255), "button": (10, 132, 255), "title": (245, 245, 247),
             "bubble-in": ((38, 38, 41), (245, 245, 247)), "bubble-out": ((10, 132, 255), (255, 255, 255))},
}
rnd = random.Random(7)


def load_font(family, size, bold):
    if family == "inter":
        return ImageFont.truetype(f"/usr/share/fonts/opentype/inter/Inter-{'SemiBold' if bold else 'Regular'}.otf", size)
    f = ImageFont.truetype(f"{FONTS}/Roboto.ttf", size)
    f.set_variation_by_name("Medium" if bold else "Regular")
    return f


def group_rows(boxes):
    """与 app 的 RowGrouper 同一个口径：竖直重叠过半、水平间距不超过 3 个行高的几段拼成一行。"""
    rows = []
    for b in sorted(boxes, key=lambda b: b["l"]):
        for r in rows:
            last = r[-1]
            overlap = min(last["b"], b["b"]) - max(last["t"], b["t"])
            if overlap >= 0.5 * min(last["b"] - last["t"], b["b"] - b["t"]) and b["l"] - max(x["r"] for x in r) <= 3 * (b["b"] - b["t"]):
                r.append(b)
                break
        else:
            rows.append([b])
    out = []
    for r in rows:
        r.sort(key=lambda b: b["l"])
        out.append({"text": " ".join(b["text"] for b in r), "conf": 0.99,
                    "box": [min(b["l"] for b in r), min(b["t"] for b in r), max(b["r"] for b in r), max(b["b"] for b in r)]})
    return sorted(out, key=lambda o: (o["box"][1], o["box"][0]))


def render(group, page):
    name = f"en-n-{group}-{page['name']}"[:80]
    th = THEMES.get(page.get("theme"), THEMES["light"])
    family = "inter" if rnd.random() < 0.3 else "roboto"
    img = Image.new("RGB", (W, H), th["bg"])
    d = ImageDraw.Draw(img)
    placed, boxes, texts = [], [], []
    shift = 0
    for row in sorted(page["rows"], key=lambda r: (r["y"], r["x"])):
        text = row["text"]
        if not text.strip():
            continue
        size = max(24, min(64, int(row["size"])))
        role = row.get("role", "primary")
        bold = role in ("title", "button")
        x = max(24, min(W - 80, int(row["x"])))
        f = load_font(family, size, bold)
        while d.textlength(text, font=f) > W - x - 24 and size > 22:
            size -= 2
            f = load_font(family, size, bold)
        tw = d.textlength(text, font=f)
        y = int(row["y"]) + shift
        # 与已经画上去的行重叠就整体往下推
        for (pl, pt, pr, pb) in placed:
            if not (x + tw < pl or x > pr) and y < pb + 6 and y + size * 1.2 > pt:
                delta = int(pb + 6 - y)
                shift += delta
                y += delta
        if y + size * 1.3 > H - 20:
            break
        if role in ("bubble-in", "bubble-out"):
            bg, fg = th[role]
            d.rounded_rectangle((x - 22, y - 14, x + tw + 22, y + size * 1.25 + 10), radius=28, fill=bg)
            color = fg
        else:
            color = th.get(role, th["primary"])
        d.text((x, y), text, font=f, fill=color)
        placed.append((x - 22, y - 14, x + tw + 22, y + size * 1.3))
        asc, desc = f.getmetrics()
        boxes.append({"text": text, "l": x / 2, "t": y / 2, "r": (x + tw) / 2, "b": (y + asc + desc) / 2})
        texts.append(text)
    img.save(f"{ROOT}/samples/{name}.png")
    dropped = []

    def keep(s):
        ok = any(s in t for t in texts)
        if not ok:
            dropped.append(s)
        return ok
    find = [i for i in page["find"] if keep(i["text"])]
    avoid = [a for a in page.get("avoid", []) if keep(a)]
    neutral = [a for a in page.get("neutral", []) if keep(a)]
    with open(f"{ROOT}/truth/{name}.json", "w") as fo:
        json.dump({"find": find, "avoid": avoid, "neutral": neutral, "rows": texts,
                   "meta": {"group": group, "app": page.get("app"), "region": page.get("region"), "font": family}},
                  fo, ensure_ascii=False, indent=1)
    with open(f"{ROOT}/oracle/{name}.json", "w") as fo:
        # 紧凑格式：EnRules 按 OcrBench 的写法（冒号、逗号后面没有空格）用正则读
        json.dump({"analysis": [W // 2, H // 2], "ms": 0, "lines": group_rows(boxes)}, fo, ensure_ascii=False, separators=(",", ":"))
    return name, len(find), dropped


total = 0
for path in sys.argv[1:]:
    for g in json.load(open(path, encoding="utf-8")):
        for p in g["pages"]:
            name, n, dropped = render(g["group"], p)
            total += n
            if dropped:
                print(f"{name}: 丢掉找不到出处的真值 {dropped}")
print(f"{len(os.listdir(ROOT + '/samples'))} pages, {total} find items -> {ROOT}")
