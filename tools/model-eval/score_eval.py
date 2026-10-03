"""离线打分：从 results/<model>.json 里的原始回答重新算分，不重跑模型。

文字模式两种口径：
  strict  = app 现在的解析（SemanticPrompt.parse：行号必须对、原文必须在那一行）
  lenient = 原文在任何一行送出去的文字里找得到就算（看模型懂不懂，不看它数行号）
图片模式：类型名宽松（姓名→人名等），格式不对的行把每段都当候选；另记原文能否在 OCR 里找到（落到像素上的前提）。
"""
import json, os, re, sys
from run_eval import HERE, SAMPLES, chunks, sendable, parse, locate, norm, hits, QUOTES

TYPES = {"人名": "人名", "姓名": "人名", "名字": "人名", "地址": "地址", "住址": "地址", "电话": "电话", "手机": "电话",
         "手机号": "电话", "号码": "号码", "证件号": "号码", "身份证": "号码", "身份证号": "号码"}


def score(found, truth):
    hit = [any(hits(t, i) for t in found) for i in truth["find"]]
    fp, other = [], []
    for t in found:
        if any(hits(t, i) for i in truth["find"]):
            continue
        (fp if any(norm(a) in norm(t) or norm(t) in norm(a) for a in truth["avoid"]) else other).append(t)
    return {"hit": sum(hit), "total": len(hit), "missed": [i["text"] for i, h in zip(truth["find"], hit) if not h],
            "fp": sorted(set(fp)), "other": sorted(set(other))}


def lenient_text(replies, sent):
    out = []
    for reply in replies:
        for row in reply.splitlines():
            m = re.search(r"(人名|地址|电话|号码)\s*[|｜]\s*(.+)", row)
            if not m:
                continue
            q = m.group(2).strip().strip(QUOTES)
            if len(q) >= 2 and any(locate(t, q) for t in sent):
                out.append(q)
    return out


def image_items(reply):
    items, bad = [], 0
    for row in reply.splitlines():
        row = re.sub(r"^\s*(?:\d+[.、:：]|[-*•])\s*", "", row.strip())
        m = re.match(r"^(\S{2,5}?)\s*[:：]\s*(.+)$", row)
        if m and m.group(1) in TYPES and "|" not in row and "｜" not in row:
            row = m.group(1) + "|" + m.group(2)
        parts = [p.strip().strip(QUOTES).strip() for p in re.split(r"[|｜]", row)]
        parts = [p for p in parts if p and p != "无"]
        if not parts:
            continue
        if parts[0] in TYPES and len(parts) >= 2:
            items.append("".join(parts[1:]) if len(parts) > 2 else parts[1])
        else:
            bad += 1
            items += [p for p in parts if p not in TYPES]
    return list(dict.fromkeys(i for i in items if len(i) >= 2)), bad


def summarize(model):
    d = json.load(open(os.path.join(HERE, "results", f"{model}.json"), encoding="utf-8"))
    hp = os.path.join(HERE, "results", f"{model}.hybrid.json")
    hy = json.load(open(hp, encoding="utf-8")) if os.path.exists(hp) else None
    rows = {}
    for s in SAMPLES:
        ocr = json.load(open(os.path.join(HERE, "ocr", f"{s}.json"), encoding="utf-8"))
        truth = json.load(open(os.path.join(HERE, "truth", f"{s}.json"), encoding="utf-8"))
        texts = [l["text"] for l in ocr["lines"]]
        sent = [t for t in sendable(ocr["lines"]) if t]
        replies = d["text"][s]["replies"]
        strict = []
        for (prompt, idx), reply in zip(chunks(sendable(ocr["lines"])), replies):
            strict += [f["text"] for f in parse(reply, idx, texts)]
        items, bad = image_items(d["image"][s]["reply"])
        grounded = [q for q in items if any(locate(t, q) for t in texts) or any(norm(q) in norm(t) for t in texts)]
        rows[s] = {
            "text_strict": score(strict, truth),
            "text_lenient": score(lenient_text(replies, sent), truth),
            "image": score(items, truth), "image_bad_rows": bad, "image_grounded": f"{len(grounded)}/{len(items)}",
            "text_perf": d["text"][s]["perf"], "image_perf": d["image"][s]["perf"],
        }
        if hy:
            from run_hybrid import sendable_all
            sent_all = [t for t in sendable_all(ocr["lines"]) if t]
            for mode in ("text_all", "hybrid"):
                hs = []
                for (prompt, idx), reply in zip(chunks(sendable_all(ocr["lines"])), hy[mode][s]["replies"]):
                    hs += [f["text"] for f in parse(reply, idx, texts)]
                rows[s][f"{mode}_strict"] = score(hs, truth)
                rows[s][f"{mode}_lenient"] = score(lenient_text(hy[mode][s]["replies"], sent_all), truth)
                rows[s][f"{mode}_perf"] = hy[mode][s]["perf"]
    bc = d["barcode"]
    return {"model": model, "samples": rows, "peak_rss_mb": d.get("peak_rss_mb"),
            "barcode": {f: v["yes"] for f, v in bc.items()}}


def fmt(sc):
    return f"{sc['hit']}/{sc['total']}" + (f" FP{len(sc['fp'])}" if sc["fp"] else "")


if __name__ == "__main__":
    allres = [summarize(m) for m in sys.argv[1:]]
    for r in allres:
        print(f"\n=== {r['model']}  peak RSS {r['peak_rss_mb']} MB")
        tot = {"text_strict": [0, 0, 0], "text_lenient": [0, 0, 0], "image": [0, 0, 0]}
        extra = "hybrid_strict" in next(iter(r["samples"].values()))
        if extra:
            tot.update({k: [0, 0, 0] for k in ("text_all_strict", "text_all_lenient", "hybrid_strict", "hybrid_lenient")})
        for s, v in r["samples"].items():
            for k in tot:
                tot[k][0] += v[k]["hit"]; tot[k][1] += v[k]["total"]; tot[k][2] += len(v[k]["fp"])
            tp = v["text_perf"][0]; ip = v["image_perf"]
            hyb = (f" | all {fmt(v['text_all_strict'])}/{fmt(v['text_all_lenient'])}"
                   f" | hybrid {fmt(v['hybrid_strict'])}/{fmt(v['hybrid_lenient'])} "
                   f"{v['hybrid_perf'][0]['prompt_n']}tok {v['hybrid_perf'][0]['wall_s']}s") if extra else ""
            print(f"  {s:10s} text strict {fmt(v['text_strict']):8s} lenient {fmt(v['text_lenient']):8s} | "
                  f"image {fmt(v['image']):8s} grounded {v['image_grounded']:5s} badrows {v['image_bad_rows']} | "
                  f"text {tp['prompt_n']}tok {tp['wall_s']}s | image {ip['prompt_n']}tok {ip['wall_s']}s" + hyb)
            for k in ["text_lenient", "image"] + (["text_all_strict", "hybrid_strict"] if extra else []):
                if v[k]["missed"] or v[k]["fp"] or v[k]["other"]:
                    print(f"      {k}: missed={v[k]['missed']} fp={v[k]['fp']} other={v[k]['other']}")
        print("  TOTAL " + "  ".join(f"{k} {a}/{b} FP{c}" for k, (a, b, c) in tot.items()))
        print("  barcode " + " ".join(f"{f.replace('.png','')}={'有' if y else '无'}" for f, y in r["barcode"].items()))
    json.dump(allres, open(os.path.join(HERE, "results", "summary.json"), "w"), ensure_ascii=False, indent=1)
