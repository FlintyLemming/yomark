"""OCR 本身读得准不准：拿合成页上画的每一行原文（truth 里的 rows）去对 OCR 行。

用法：python score_ocr.py <ocr目录> <truth目录> [<truth目录> ...]

每一行原文在 OCR 输出里找最像的一段（OCR 会把同一排的几段拼成一行，所以按子串对齐），记：
  exact   = 一字不差，空格也对；
  nospace = 去掉空格后一字不差（只错在空格上的也算对）；
  CER     = 编辑距离 / 原文长度（去掉空格算，空格单独看）。
按页名前缀 en- 分英文、中文两组汇总。
"""
import json, os, sys


def semi_global(row, line):
    """row 与 line 的任意子串之间的最小编辑距离。"""
    prev = [0] * (len(line) + 1)
    for i, a in enumerate(row, 1):
        cur = [i] + [0] * len(line)
        for j, b in enumerate(line, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a != b))
        prev = cur
    return min(prev)


def best(row, lines):
    ns = row.replace(" ", "")
    exact = any(row in l for l in lines)
    nospace = any(ns in l.replace(" ", "") for l in lines)
    dist = min((semi_global(ns, l.replace(" ", "")) for l in lines), default=len(ns))
    return exact, nospace, dist, len(ns)


def main(ocr_dir, truth_dirs):
    groups = {"en": [0, 0, 0, 0, 0], "zh": [0, 0, 0, 0, 0]}   # rows, exact, nospace, dist, chars
    worst = []
    ms = {"en": [], "zh": []}
    for td in truth_dirs:
        for fn in sorted(os.listdir(td)):
            name = fn[:-5]
            p = os.path.join(ocr_dir, fn)
            if not os.path.exists(p):
                continue
            truth = json.load(open(os.path.join(td, fn), encoding="utf-8"))
            ocr = json.load(open(p, encoding="utf-8"))
            lines = [l["text"] for l in ocr["lines"]]
            g = "en" if name.startswith("en-") else "zh"
            ms[g].append(ocr.get("ms", 0))
            for row in truth.get("rows", []):
                row = " ".join(row.split())   # 排版用的连续空格只算一个
                if len(row.strip()) < 2:
                    continue
                e, n, d, c = best(row, lines)
                s = groups[g]
                s[0] += 1; s[1] += e; s[2] += n; s[3] += d; s[4] += c
                if not e:
                    worst.append((g, name, row, min(lines, key=lambda l: semi_global(row.replace(" ", ""), l.replace(" ", ""))) if lines else ""))
    out = {}
    for g, (rows, e, n, d, c) in groups.items():
        if rows:
            out[g] = {"rows": rows, "exact": e, "nospace": n, "cer": round(d / max(c, 1), 4),
                      "ms_median": sorted(ms[g])[len(ms[g]) // 2] if ms[g] else None}
    return out, worst


if __name__ == "__main__":
    res, worst = main(sys.argv[1], sys.argv[2:])
    print(json.dumps(res, ensure_ascii=False))
    for g, name, row, got in worst:
        print(f"  [{g}] {name}: {row!r} -> {got!r}")
