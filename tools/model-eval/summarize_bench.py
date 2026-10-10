"""名址基准的汇总表：每个结果文件一行，人名、地址的召回，加上误报。

用法：python summarize_bench.py <truth目录> <规则结果.json> [--veto-brands=<品牌表>] [--veto-places=<地名表>] <模型结果.json> [...]
  --veto-brands  整段就是一个品牌名（NSI 品牌表）的不要；
  --veto-places  模型的地址类整段就是一个大城市、国家名的不要（只到城市一级，认不出是谁）；
  --addr-gate=<ocr目录>  模型的地址类只留像地址的：有字母词又有数字（「Plano, TX 75093」「12047 Berlin」），或者至少两个词
                 （「Ikeja, Lagos」），或者整段是邮编、新加坡的门牌（「E8 4RP」「51094-4870」「600020」「#08-1234」）。
                 不像的（状态栏的「5G 82%」「9:41」、单个词「Town」「Mobile」「Manchester」）只在紧挨着的上一行或下一行
                 有像地址的一段时才留：多行地址块里单独一行的城市。要 OCR 目录来知道哪段在哪一行。

每个模型结果算两行：
  单独        模型认出的人名、地址（ner-name、ner-address）；
  规则 + 模型  规则的打码一档 + 模型的人名、地址。模型那部分是直接打码还是只圈出来让用户点，召回、误报的数都一样，
              差别在误报的代价：打码是多遮，圈出是多一个要用户看的框。
「误报」只算 avoid（品牌、店名、公开地名等）；「另外」是真值里既不在 find 也不在 avoid、neutral 的多认（状态栏时间、单独的城市名等），
分开列，因为里面混着出题人漏标的真隐私。模型的电话、邮箱、证件号（ner-other）不计：那几类交给规则。
"""
import json, os, re, sys
from score_en_rules import score

FACTORY_SKIP = {"en-address", "en-name", "en-name-weak", "en-title", "en-phone", "en-below-label",
                "gliner", "ner", "ner-name", "ner-address", "ner-other"}


def load(p):
    return json.load(open(p, encoding="utf-8"))


def veto(xs, brands):
    if not brands:
        return xs
    return [x for x in xs if x.strip().lower() not in brands and x.strip().lower().removesuffix("'s") not in brands]


def tally(truth_dir, pages):
    """pages: {页名: 找到的文字列表} → (人名命中, 人名总数, 地址命中, 地址总数, 误报数, 另外数)"""
    nh = nt = ah = at = fp = other = 0
    for page, found in pages.items():
        tp = os.path.join(truth_dir, page + ".json")
        if not os.path.exists(tp):
            continue
        truth = load(tp)
        hit, f, o = score(found, truth)
        for item, h in zip(truth["find"], hit):
            if item["kind"] in ("name", "人名"):
                nh += h; nt += 1
            elif item["kind"] in ("address", "地址"):
                ah += h; at += 1
        fp += len(f); other += len(o)
    return nh, nt, ah, at, fp, other


def row(label, t):
    nh, nt, ah, at, fp, other = t
    return f"| {label} | {nh}/{nt} ({nh / max(nt, 1):.0%}) | {ah}/{at} ({ah / max(at, 1):.0%}) | {fp} | {other} |"


def drop_places(xs, places):
    if not places:
        return xs
    return [x for x in xs if x.strip(" .,").lower() not in places]


POSTCODE = re.compile(r"^(?:[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}|[A-Z]\d[A-Z]\s?\d[A-Z]\d|\d{5}(?:-\d{4})?|\d{6}|#\d{1,3}-\d{1,5})$", re.I)


def address_like(x):
    x = x.strip(" ,.")
    words = re.findall(r"[A-Za-z][A-Za-z'.-]+", x)
    return bool(POSTCODE.match(x)) or (any(c.isdigit() for c in x) and any(len(w) >= 3 for w in words)) or len(words) >= 2


def gated(pieces, lines):
    """地址块门槛：像地址的留下；不像的，上一行或下一行有像地址的一段才留。"""
    def line_of(x):
        return next((i for i, l in enumerate(lines) if x in l), None)
    good = {line_of(x) for x in pieces if address_like(x)} - {None}
    return [x for x in pieces if address_like(x) or (line_of(x) is not None and {line_of(x) - 1, line_of(x) + 1} & good)]


def main(truth_dir, rules_path, model_paths, brands, places=None, gate=None):
    rules = load(rules_path)
    strong = {p: [x for k, xs in v.items() if k not in ("en-name-weak",) for x in xs] for p, v in rules.items()}
    weak = {p: veto(v.get("en-name-weak", []), brands) for p, v in rules.items()}
    print("| 认法 | 人名 | 地址 | 误报 | 另外 |")
    print("|---|---|---|---|---|")
    print(row("规则（打码一档）", tally(truth_dir, strong)))
    print(row("规则（打码 + 圈出）", tally(truth_dir, {p: strong[p] + weak.get(p, []) for p in strong})))
    for mp in model_paths:
        m = load(mp)
        tag = os.path.basename(mp)[:-5]
        mod = {}
        for p, v in m.items():
            addr = drop_places(v.get("ner-address", []), places)
            if gate:
                ocr = os.path.join(gate, p + ".json")
                lines = [l["text"] for l in load(ocr)["lines"]] if os.path.exists(ocr) else []
                addr = gated(addr, lines)
            mod[p] = veto(v.get("ner-name", []) + addr, brands)
        print(row(f"{tag} 单独", tally(truth_dir, mod)))
        print(row(f"规则 + {tag}", tally(truth_dir, {p: strong.get(p, []) + mod.get(p, []) for p in set(strong) | set(mod)})))


if __name__ == "__main__":
    args = sys.argv[1:]
    bf = next((a.split("=", 1)[1] for a in args if a.startswith("--veto-brands=")), None)
    brands = {l.strip().lower() for l in open(bf, encoding="utf-8") if l.strip()} if bf else None
    pf = next((a.split("=", 1)[1] for a in args if a.startswith("--veto-places=")), None)
    places = {l.strip().lower() for l in open(pf, encoding="utf-8") if l.strip()} if pf else None
    rest = [a for a in args if not a.startswith("--")]
    gate = next((a.split("=", 1)[1] for a in args if a.startswith("--addr-gate=")), None)
    main(rest[0], rest[1], rest[2:], brands, places, gate)
