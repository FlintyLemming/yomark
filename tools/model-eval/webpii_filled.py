"""WebPII 的输入框条目按截图上有没有字筛一遍：empty、partial_* 变体的截图里输入框是空的，标注里却有值。

用法：python webpii_filled.py <truth目录> <输出目录> <ocr目录> [<ocr目录> ...]
带 "input": true 的条目，在任何一份 OCR 结果里都找不到（去空格、不分大小写，最像的一段编辑距离不超过三成）就当没填，
挪进 neutral（模型、规则认出来也不算多认）；partial_* 变体里有的框只打了开头几个字（「Paul Gu」），
有一整行 OCR 正好是它的开头（至少 4 个字）就把真值换成截图上那几个字。其他条目原样留下。给几份 OCR（比如 v5、v6s 各一份）取并集，
免得某一份读错就把真值删了。
"""
import json, os, sys
from score_ocr import semi_global


def present(text, lines):
    t = text.replace(" ", "").lower()
    return any(semi_global(t, l.replace(" ", "").lower()) <= 0.3 * len(t) for l in lines)


def typed_prefix(text, lines):
    t = text.replace(" ", "").lower()
    return next((l.strip() for l in lines if len(l.replace(" ", "")) >= 4 and t.startswith(l.replace(" ", "").lower())), None)


def main(truth_dir, out_dir, ocr_dirs):
    os.makedirs(out_dir, exist_ok=True)
    kept = dropped = 0
    for fn in sorted(os.listdir(truth_dir)):
        truth = json.load(open(os.path.join(truth_dir, fn), encoding="utf-8"))
        lines = []
        for d in ocr_dirs:
            p = os.path.join(d, fn)
            if os.path.exists(p):
                lines += [l["text"] for l in json.load(open(p, encoding="utf-8"))["lines"]]
        find = []
        for item in truth["find"]:
            if item.get("input") and not present(item["text"], lines):
                prefix = typed_prefix(item["text"], lines)
                truth["neutral"].append(item["text"])
                if prefix:
                    find.append({**item, "text": prefix})
                    kept += 1
                else:
                    dropped += 1
            else:
                find.append(item)
                kept += 1
        truth["find"] = find
        json.dump(truth, open(os.path.join(out_dir, fn), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"kept {kept}, dropped {dropped} unfilled input items -> {out_dir}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
