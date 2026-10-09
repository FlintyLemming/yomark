"""用 NER / PII 模型读 OCR 行，输出与 EnRules 同格式的 JSON（键 "ner"），供 score_en_rules.py 打分、与规则合并。

用法：python run_ner.py <ocr目录> <输出.json> <模型> [<阈值，默认 0.3>]
  模型写法：
    gliner:<HF 仓库>[:<onnx 文件名>]   GLiNER 系列；不给 onnx 文件名就用 PyTorch 权重（fp32），给了就用该 ONNX（如 onnx/model_quint8.onnx）
    hf:<HF 仓库>                       普通的 token classification 模型（BERT / DeBERTa / ModernBERT 类），transformers 的 pipeline
只跑 en- 开头的页。整页的行用换行连起来送进去；hf 模型超长时按行切块（每块不超过约 350 个词）。
认出的片段跨行时按行拆开。只留人名、地址、电话、邮箱、证件号这几类，机构、日期、金额一概不要（它们不在「该遮」的范围里，留着只会多出误报）。

需要 pip install gliner transformers（CPU 版 torch 即可）。
"""
import json, os, re, sys, time

GLINER_LABELS = ["person", "address", "street address", "city", "postal code", "phone number", "email"]

# hf 模型的实体名五花八门（PER / B-NAME / GIVENNAME / STREET / ZIPCODE ...），按关键词归到要遮的几类
KEEP = re.compile(r"PER|NAME|GIVEN|SURNAME|FIRST|LAST|MIDDLE|PREFIX|ADDR|STREET|BUILDING|CITY|ZIP|POSTCODE|POSTAL|STATE|"
                  r"SECONDARY|COUNTY|PHONE|TEL|EMAIL|MAIL|ACCOUNT|SSN|PASSPORT|LICEN|ID", re.I)
DROP = re.compile(r"USERNAME|ORG|COMPANY|DATE|TIME|AGE|MONEY|AMOUNT|CURRENCY|URL|IP|JOB|TITLE|GENDER|SEX", re.I)


def pieces(text):
    return [p.strip(" ,") for p in text.split("\n") if p.strip(" ,")]


def load(spec):
    kind, _, rest = spec.partition(":")
    if kind == "gliner":
        from gliner import GLiNER
        repo, _, onnx = rest.partition(":")
        if onnx:
            m = GLiNER.from_pretrained(repo, load_onnx_model=True, load_tokenizer=True, onnx_model_file=onnx)
        else:
            m = GLiNER.from_pretrained(repo)
        return lambda text, thr: [e["text"] for e in m.predict_entities(text, GLINER_LABELS, threshold=thr)]
    if kind == "hf":
        from transformers import pipeline
        nlp = pipeline("token-classification", model=rest, aggregation_strategy="simple", device=-1)

        def run(text, thr):
            out, chunk = [], []
            for line in text.split("\n") + [None]:
                if line is not None and sum(len(c.split()) for c in chunk) + len(line.split()) < 350:
                    chunk.append(line)
                    continue
                if chunk:
                    block = "\n".join(chunk)
                    for e in nlp(block):
                        g = e.get("entity_group") or e.get("entity", "")
                        if e["score"] >= thr and KEEP.search(g) and not DROP.search(g):
                            out.append(block[e["start"]:e["end"]])
                chunk = [line] if line is not None else []
            return out
        return run
    raise SystemExit(f"不认得的模型写法：{spec}")


def main(ocr_dir, out_path, spec, threshold=0.3):
    predict = load(spec)
    out, times = {}, []
    for fn in sorted(os.listdir(ocr_dir)):
        if not fn.startswith("en-") or not fn.endswith(".json"):
            continue
        lines = [l["text"] for l in json.load(open(os.path.join(ocr_dir, fn), encoding="utf-8"))["lines"] if l["conf"] >= 0.5]
        text = "\n".join(lines)
        t0 = time.time()
        found = predict(text, threshold)
        times.append(time.time() - t0)
        out[fn[:-5]] = {"ner": [p for f in found for p in pieces(f)]}
    json.dump(out, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"{spec}: {len(times)} pages, median {sorted(times)[len(times) // 2] * 1000:.0f} ms/page")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]) if len(sys.argv) > 4 else 0.3)
