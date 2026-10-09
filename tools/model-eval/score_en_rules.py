"""给 EnRules 的输出打分：出厂规则、出厂 + 英文原型，各认出几处、误报几处。

用法：python score_en_rules.py <truth目录> <结果.json> [<结果.json> ...]
  几个结果文件（EnRules、run_gliner 的输出）按页合并后一起打分，看两种认法合用的效果。

命中口径与 run_eval.hits 相同：去掉空格后互相包含，且找到的不比真值长出一倍以上（整行照抄不算）；
另外，几段找到的文字合起来盖住真值的 80% 以上也算（英文地址常被分成街道、城市、邮编几段）。
误报只算落在 avoid 上的；落在 neutral（遮不遮都行的公开信息）上的不计；其余多认出来的列在「另外」里，人工看是不是真误报。
"""
import json, os, re, sys

# 出厂规则以外的认法：EnProto 的几种，加上 GLiNER
EN_KEYS = {"en-address", "en-name", "en-title", "en-phone", "en-below-label", "gliner", "ner"}


def norm(s):
    s = re.sub(r"\s+", "", s)
    s = re.sub(r"[*＊•●xX]+", "*", s)
    return s.replace("￥", "¥")


def hits(found_text, item):
    f = norm(found_text)
    for g in [item["text"]] + item.get("alias", []):
        g = norm(g)
        short, long_ = (f, g) if len(f) <= len(g) else (g, f)
        if len(short) >= 2 and short in long_ and len(short) >= 0.5 * len(g) and len(f) <= max(2 * len(g), len(g) + 6):
            return True
    return False


def covered(found, item):
    """真值被几段找到的文字拼起来盖住了多少：英文地址常被认成「街道」「城市」「邮编」几段，合起来照样遮住了。"""
    g = norm(item["text"])
    mask = [False] * len(g)
    for t in found:
        f = norm(t)
        at = g.find(f) if len(f) >= 2 else -1
        if at >= 0:
            mask[at:at + len(f)] = [True] * len(f)
    return sum(mask) / max(len(g), 1)


def score(found, truth):
    hit = [any(hits(t, i) for t in found) or covered(found, i) >= 0.8 for i in truth["find"]]
    fp, other = [], []
    for t in found:
        if any(hits(t, i) or (len(norm(t)) >= 2 and norm(t) in norm(i["text"])) for i in truth["find"]):
            continue
        if any(norm(a) in norm(t) or norm(t) in norm(a) for a in truth["avoid"]):
            fp.append(t)
        elif not any(norm(a) in norm(t) or norm(t) in norm(a) for a in truth.get("neutral", [])):
            other.append(t)
    return hit, sorted(set(fp)), sorted(set(other))


def main(truth_dir, paths):
    d = {}
    for p in paths:
        for page, v in json.load(open(p, encoding="utf-8")).items():
            d.setdefault(page, {}).update(v)
    combos = {"出厂规则": lambda k: k not in EN_KEYS, "全部（出厂 + 原型 / GLiNER）": lambda k: True}
    for name, keep in combos.items():
        by_kind, fps, others, missed = {}, [], [], []
        for page in sorted(d):
            tp = os.path.join(truth_dir, page + ".json")
            if not os.path.exists(tp):
                continue
            truth = json.load(open(tp, encoding="utf-8"))
            found = [x for k, xs in d[page].items() if keep(k) for x in xs]
            hit, fp, other = score(found, truth)
            for i, h in zip(truth["find"], hit):
                k = by_kind.setdefault(i["kind"], [0, 0])
                k[0] += h; k[1] += 1
                if not h:
                    missed.append(f"{page}:{i['text']}")
            fps += [f"{page}:{x}" for x in fp]
            others += [f"{page}:{x}" for x in other]
        total = sum(v[0] for v in by_kind.values()), sum(v[1] for v in by_kind.values())
        kinds = "  ".join(f"{k} {v[0]}/{v[1]}" for k, v in by_kind.items())
        print(f"## {name}: {total[0]}/{total[1]}   {kinds}")
        print(f"   误报 {len(fps)}: {fps}")
        print(f"   另外 {len(others)}: {others}")
        print(f"   漏 {len(missed)}: {missed}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
