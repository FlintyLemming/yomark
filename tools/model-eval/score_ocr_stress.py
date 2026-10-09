"""给压力测试页打分（make_ocr_stress.py 的页面），几个 OCR 配置并排比。

用法：python score_ocr_stress.py <truth目录> <名字=ocr目录> [<名字=ocr目录> ...]

每个配置按文种（en / zh）给：
  CER         字符错误率（去掉空格算；每行原文对 OCR 输出里最像的一段）；
  行全对       一字不差，含空格（规则靠空格分词，空格错了也算错）；
  关键片段     人名、地址、邮箱、电话、编号在 OCR 输出里一字不差地出现（空格按单个空格比；这是规则能不能认出它的前提）；
再按条件（主题、字号档、字体、JPEG、屏幕）分组给 CER，看哪种截图最伤。
"""
import json, os, sys
from collections import defaultdict
from score_ocr import semi_global


def squash(s):
    return " ".join(s.split())


def page_metrics(truth, lines):
    rows = [squash(r) for r in truth["rows"] if len(r.strip()) >= 2]
    nospace = [l.replace(" ", "") for l in lines]
    sq = [squash(l) for l in lines]
    dist = chars = exact = 0
    for r in rows:
        ns = r.replace(" ", "")
        dist += min((semi_global(ns, l) for l in nospace), default=len(ns))
        chars += len(ns)
        exact += any(r in l for l in sq)
    ents = defaultdict(lambda: [0, 0])
    for e in truth.get("entities", []):
        t = squash(e["text"])
        ents[e["kind"]][0] += any(t in l for l in sq)
        ents[e["kind"]][1] += 1
    return dist, chars, exact, len(rows), ents


def main(truth_dir, configs):
    pages = sorted(f[:-5] for f in os.listdir(truth_dir) if f.endswith(".json"))
    truths = {p: json.load(open(os.path.join(truth_dir, p + ".json"), encoding="utf-8")) for p in pages}
    table, by_cond = {}, defaultdict(dict)
    for name, d in configs:
        agg = {g: {"dist": 0, "chars": 0, "exact": 0, "rows": 0, "ents": defaultdict(lambda: [0, 0])} for g in ("en", "zh")}
        cond_agg = defaultdict(lambda: [0, 0])
        for p in pages:
            path = os.path.join(d, p + ".json")
            if not os.path.exists(path):
                continue
            t = truths[p]
            lines = [l["text"] for l in json.load(open(path, encoding="utf-8"))["lines"]]
            dist, chars, exact, rows, ents = page_metrics(t, lines)
            g = agg[t["cond"]["lang"]]
            g["dist"] += dist; g["chars"] += chars; g["exact"] += exact; g["rows"] += rows
            for k, (h, n) in ents.items():
                g["ents"][k][0] += h; g["ents"][k][1] += n
            c = t["cond"]
            for key in ("theme", "size", "font", "screen"):
                v = c[key].split("-")[0] if key == "font" else c[key]
                cond_agg[(c["lang"], key, v)][0] += dist; cond_agg[(c["lang"], key, v)][1] += chars
            jk = (c["lang"], "jpeg", "jpeg" if c["jpeg"] else "png")
            cond_agg[jk][0] += dist; cond_agg[jk][1] += chars
        table[name] = agg
        for k, (dist, chars) in cond_agg.items():
            by_cond[k][name] = dist / max(chars, 1)

    for g in ("en", "zh"):
        kinds = sorted({k for a in table.values() for k in a[g]["ents"]})
        print(f"\n### {g}")
        print("| 配置 | CER | 行全对 | " + " | ".join(kinds) + " | 关键片段合计 |")
        print("|---|---|---|" + "---|" * (len(kinds) + 1))
        for name, agg in table.items():
            a = agg[g]
            if not a["rows"]:
                continue
            ent_cells = [f"{a['ents'][k][0]}/{a['ents'][k][1]}" for k in kinds]
            tot_h = sum(a["ents"][k][0] for k in kinds); tot_n = sum(a["ents"][k][1] for k in kinds)
            print(f"| {name} | {a['dist'] / max(a['chars'], 1):.2%} | {a['exact']}/{a['rows']} | " + " | ".join(ent_cells) +
                  f" | {tot_h}/{tot_n} ({tot_h / max(tot_n, 1):.1%}) |")
    print("\n### 按条件的 CER")
    names = list(table)
    print("| 文种 | 条件 | 值 | " + " | ".join(names) + " |")
    print("|---|---|---|" + "---|" * len(names))
    for k in sorted(by_cond):
        print(f"| {k[0]} | {k[1]} | {k[2]} | " + " | ".join(f"{by_cond[k].get(n, float('nan')):.2%}" for n in names) + " |")


if __name__ == "__main__":
    main(sys.argv[1], [a.split("=", 1) for a in sys.argv[2:]])
