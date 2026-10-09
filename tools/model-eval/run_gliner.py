"""GLiNER PII（knowledgator/gliner-pii-edge-v1.0）读英文页的 OCR 行，输出与 EnRules 同格式的 JSON，供 score_en_rules.py 打分。

用法：python run_gliner.py <ocr目录> <输出.json> [<onnx 文件名，默认 model_quint8；torch 表示不用 ONNX>] [<阈值，默认 0.3>]

整页的行用换行连起来一次送进去（比逐行送多一点上下文）；认出的片段跨行时按行拆开，每行各算一段。
只跑 en- 开头的页。需要 pip install gliner（会装 torch，CPU 版即可）。
"""
import json, os, sys, time
from gliner import GLiNER

REPO = "knowledgator/gliner-pii-edge-v1.0"
LABELS = ["person", "address", "street address", "city", "phone number", "email"]


def main(ocr_dir, out_path, variant="model_quint8", threshold=0.3):
    if variant == "torch":
        model = GLiNER.from_pretrained(REPO)
    else:
        model = GLiNER.from_pretrained(REPO, load_onnx_model=True, load_tokenizer=True, onnx_model_file=f"onnx/{variant}.onnx")
    out, times = {}, []
    for fn in sorted(os.listdir(ocr_dir)):
        if not fn.startswith("en-"):
            continue
        lines = [l["text"] for l in json.load(open(os.path.join(ocr_dir, fn), encoding="utf-8"))["lines"] if l["conf"] >= 0.5]
        model.predict_entities("\n".join(lines), LABELS, threshold=threshold)   # 预热
        t0 = time.time()
        ents = model.predict_entities("\n".join(lines), LABELS, threshold=threshold)
        times.append(time.time() - t0)
        out[fn[:-5]] = {"gliner": [p.strip(" ,") for e in ents for p in e["text"].split("\n") if p.strip(" ,")]}
    json.dump(out, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"{variant}: {len(times)} pages, median {sorted(times)[len(times) // 2] * 1000:.0f} ms/page")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "model_quint8",
         float(sys.argv[4]) if len(sys.argv) > 4 else 0.3)
