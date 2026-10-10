"""两个识别模型读同一批检测框，逐行取置信度高的那个（只在判定为英文的页上）。

用法：python ensemble_ocr.py <主模型ocr目录> <副模型ocr目录> <输出目录> [<汉字占比阈值，默认 0.1>]

两边必须用同一个检测模型跑（框一样），按框的 IoU ≥ 0.6 配对；配不上的行照主模型。
中文页（汉字占比 ≥ 阈值，同 route_ocr.py 的判定）整页照主模型——副模型是英文专用的。
在 app 里这一步只是对英文页的同一批框多跑一遍识别。
"""
import json, os, sys
from route_ocr import han_ratio


def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0


def main(primary, secondary, out, threshold=0.1):
    os.makedirs(out, exist_ok=True)
    swapped = total = 0
    for fn in sorted(os.listdir(primary)):
        if not fn.endswith(".json"):
            continue
        p = json.load(open(os.path.join(primary, fn), encoding="utf-8"))
        sp = os.path.join(secondary, fn)
        if han_ratio(os.path.join(primary, fn)) < threshold and os.path.exists(sp):
            s = json.load(open(sp, encoding="utf-8"))["lines"]
            for line in p["lines"]:
                total += 1
                best = max(s, key=lambda o: iou(o["box"], line["box"]), default=None)
                if best and iou(best["box"], line["box"]) >= 0.6 and best["conf"] > line["conf"]:
                    line["text"], line["conf"] = best["text"], best["conf"]
                    swapped += 1
        json.dump(p, open(os.path.join(out, fn), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print(f"英文页 {total} 行里换成副模型的 {swapped} 行")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]) if len(sys.argv) > 4 else 0.1)
