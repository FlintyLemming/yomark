"""把切成横条（<名>__t<k>.json）分别识别的结果拼回一页（<名>.json），框按条的位置往下平移。

用法：python merge_tiles.py <ocr目录> [<条高，默认 1200>]
条高要与切条时一致（convert_webpii.py 的 TILE），识别要用 full 口径（坐标就是原图像素）。拼完删掉各条的文件。
"""
import json, os, re, sys
from collections import defaultdict


def main(d, tile=1200):
    groups = defaultdict(list)
    for fn in os.listdir(d):
        m = re.match(r"(.+)__t(\d+)\.json$", fn)
        if m:
            groups[m.group(1)].append((int(m.group(2)), fn))
    for name, parts in groups.items():
        lines, w, h = [], 0, 0
        for k, fn in sorted(parts):
            p = json.load(open(os.path.join(d, fn), encoding="utf-8"))
            w = max(w, p["analysis"][0]); h = k * tile + p["analysis"][1]
            for l in p["lines"]:
                b = l["box"]
                lines.append({"text": l["text"], "conf": l["conf"], "box": [b[0], b[1] + k * tile, b[2], b[3] + k * tile]})
            os.remove(os.path.join(d, fn))
        json.dump({"analysis": [w, h], "ms": 0, "lines": lines}, open(os.path.join(d, name + ".json"), "w", encoding="utf-8"),
                  ensure_ascii=False, separators=(",", ":"))
    print(f"merged {len(groups)} tiled pages")


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 1200)
