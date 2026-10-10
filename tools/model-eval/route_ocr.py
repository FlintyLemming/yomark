"""按页分流：先用中文模型（能认中英文）识别，判定这页是英文页，就换成英文模型的结果。

用法：python route_ocr.py <首轮ocr目录> <英文模型ocr目录> <输出目录> [<汉字占比阈值，默认 0.1>]

判定：首轮结果里 汉字 / (汉字 + 拉丁字母) 低于阈值就是英文页。前提是一张图不会中英混排（用户给的约定）。
在 app 里这一步不用多跑一遍检测：同一批框只把识别重跑一遍。评测台里两套结果是分别跑出来的，这里只是按页挑。
打印每页的判定和汉字占比，判错的标出来（页名以 en- 开头算英文页）。
"""
import json, os, shutil, sys


def han_ratio(path):
    text = "".join(l["text"] for l in json.load(open(path, encoding="utf-8"))["lines"])
    han = sum('一' <= c <= '鿿' for c in text)
    latin = sum(c.isascii() and c.isalpha() for c in text)
    return han / max(han + latin, 1)


def main(first, english, out, threshold=0.1):
    os.makedirs(out, exist_ok=True)
    wrong = 0
    n = 0
    for fn in sorted(os.listdir(first)):
        if not fn.endswith(".json"):
            continue
        r = han_ratio(os.path.join(first, fn))
        is_en = r < threshold
        src = os.path.join(english if is_en and os.path.exists(os.path.join(english, fn)) else first, fn)
        shutil.copy(src, os.path.join(out, fn))
        truth_en = fn.startswith("en-")
        n += 1
        if truth_en != is_en:
            wrong += 1
            print(f"判错 {fn}: 汉字占比 {r:.3f} -> {'英文' if is_en else '中文'}")
    print(f"{n} 页，判错 {wrong} 页（阈值 {threshold}）")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]) if len(sys.argv) > 4 else 0.1)
