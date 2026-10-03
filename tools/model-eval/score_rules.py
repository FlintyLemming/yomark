"""给规则原型打分：读 RuleProto 写出的 JSON，按与模型评测同一个口径算命中与误报。

用法：python score_rules.py out/rule-proto.json
"""
import json, sys
from score_eval import score
from run_eval import HERE, SAMPLES

d = json.load(open(sys.argv[1], encoding="utf-8"))
combos = {"出厂规则": ["now"], "规则 + 补强": ["now", "proto"], "只有 HanLP 人名": ["hanlp"],
          "规则 + 补强 + HanLP": ["now", "proto", "hanlp"]}
for name, keys in combos.items():
    hit = total = 0
    fp, missed = [], []
    for s in SAMPLES:
        truth = json.load(open(f"{HERE}/truth/{s}.json", encoding="utf-8"))
        sc = score([x for k in keys for x in d[s][k]], truth)
        hit += sc["hit"]; total += sc["total"]
        fp += [f"{s}:{x}" for x in sc["fp"]]; missed += [f"{s}:{x}" for x in sc["missed"]]
    print(f"{name}: {hit}/{total}  误报 {fp}  漏 {missed}")
