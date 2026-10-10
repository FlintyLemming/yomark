"""名址基准的汇总表：每个结果文件一行，人名、地址的召回，加上误报。

用法：python summarize_bench.py <truth目录> <规则结果.json> [--veto-brands=<品牌表>] <模型结果.json> [...]

每个模型结果算三行：
  单独        模型认出的人名、地址（ner-name、ner-address）都打码；
  规则+模型   规则的打码一档 + 模型的人名、地址，都打码；
  规则打码、模型圈出  打码的只有规则那一档，召回按「打码 + 圈出」合起来算（圈出也会让用户看到、点一下就能打码）。
「误报」只算 avoid（品牌、店名、公开地名等）；「另外」是真值里既不在 find 也不在 avoid、neutral 的多认（状态栏时间、单独的城市名等），
分开列，因为里面混着出题人漏标的真隐私。模型的电话、邮箱、证件号（ner-other）不计：那几类交给规则。
"""
import json, os, sys
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


def main(truth_dir, rules_path, model_paths, brands):
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
        mod = {p: veto(v.get("ner-name", []) + v.get("ner-address", []), brands) for p, v in m.items()}
        print(row(f"{tag} 单独", tally(truth_dir, mod)))
        print(row(f"规则 + {tag}", tally(truth_dir, {p: strong.get(p, []) + mod.get(p, []) for p in set(strong) | set(mod)})))


if __name__ == "__main__":
    args = sys.argv[1:]
    bf = next((a.split("=", 1)[1] for a in args if a.startswith("--veto-brands=")), None)
    brands = {l.strip().lower() for l in open(bf, encoding="utf-8") if l.strip()} if bf else None
    rest = [a for a in args if not a.startswith("--")]
    main(rest[0], rest[1], rest[2:], brands)
