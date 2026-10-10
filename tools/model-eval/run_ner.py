"""用 NER / PII 模型读 OCR 行，输出与 EnRules 同格式的 JSON（按类分三个键：ner-name、ner-address、ner-other），
供 score_en_rules.py 打分、与规则合并。

用法：python run_ner.py <ocr目录> <输出.json> <模型> [<阈值，默认 0.3>]
  模型写法：
    gliner:<HF 仓库>[:<onnx 文件名>]   GLiNER 系列；不给 onnx 文件名就用 PyTorch 权重（fp32），给了就用该 ONNX（如 onnx/model_quint8.onnx）
    gliner+org:<同上>                  同上，另加 organization / brand / store / product 几个「对手」标签：店名、品牌被它们认走，就不会落到 person 上
    hf:<HF 仓库>                       普通的 token classification 模型（BERT / DeBERTa / ModernBERT 类），transformers 的 pipeline
    ort:<HF 仓库>:<onnx 文件名|本地路径>  只发了 ONNX 的 token classification 模型（如 Rampart 的 onnx/model_q4.onnx）：
                                      tokenizer.json + config.json 的 id2label，ONNX Runtime 推理，BIO 拼段
只跑 en- 开头的页。环境变量 NER_MODE=page（默认）把整页的行用换行连起来送进去，=line 一行一送；
hf / ort 模型超长时按行切块（每块不超过约 350 个词）。
认出的片段跨行时按行拆开。只留人名、地址、电话、邮箱、证件号这几类，机构、日期、金额一概不要（它们不在「该遮」的范围里，留着只会多出误报）。

需要 pip install gliner transformers（CPU 版 torch 即可）。
"""
import json, os, re, sys, time

GLINER_LABELS = ["person", "address", "street address", "city", "postal code", "phone number", "email"]
# 对手标签：认出来也不要，只是让品牌、店名有个去处
GLINER_RIVALS = ["organization", "brand", "store", "product"]

# hf 模型的实体名五花八门（PER / B-NAME / GIVENNAME / STREET / ZIPCODE ...），按关键词归到要遮的几类
KEEP = re.compile(r"PER|NAME|GIVEN|SURNAME|FIRST|LAST|MIDDLE|PREFIX|ADDR|STREET|BUILDING|CITY|ZIP|POSTCODE|POSTAL|STATE|"
                  r"SECONDARY|COUNTY|PHONE|TEL|EMAIL|MAIL|ACCOUNT|SSN|PASSPORT|LICEN|ID", re.I)
DROP = re.compile(r"USERNAME|ORG|COMPANY|DATE|TIME|AGE|MONEY|AMOUNT|CURRENCY|URL|IP|JOB|TITLE|GENDER|SEX", re.I)


def pieces(text):
    return [p.strip(" ,") for p in text.split("\n") if p.strip(" ,")]


def chunked(run_block):
    """按行凑块（每块不超过约 350 个词）分别跑，结果拼起来。"""
    def run(text, thr):
        out, chunk = [], []
        for line in text.split("\n") + [None]:
            if line is not None and sum(len(c.split()) for c in chunk) + len(line.split()) < 350:
                chunk.append(line)
                continue
            if chunk:
                out += run_block("\n".join(chunk), thr)
            chunk = [line] if line is not None else []
        return out
    return run


def merge_adjacent(text, spans):
    """同一行里首尾相接、或只隔一个空格的同类几段并成一段：BPE 模型的 simple 聚合会把一个词切成几截（「Sh」「ored」「itch」），
    名和姓（first_name、last_name）也各报一段。跨行不并。spans 是 (起, 止, 类别)。"""
    out = []
    for a, b, c in spans:
        if out and out[-1][2] == c and 0 <= a - out[-1][1] <= 1 and text[out[-1][1]:a] in ("", " "):
            out[-1] = (out[-1][0], max(b, out[-1][1]), c)
        else:
            out.append((a, b, c))
    return out


def keep_label(g):
    return KEEP.search(g) and not DROP.search(g)


NAME_CLASS = re.compile(r"PER|NAME|GIVEN|SURNAME|FIRST|LAST|MIDDLE", re.I)
ADDRESS_CLASS = re.compile(r"ADDR|STREET|BUILDING|CITY|ZIP|POSTCODE|POSTAL|STATE|SECONDARY|COUNTY", re.I)


def label_class(g):
    """实体名归成三类：人名、地址、其他（电话、邮箱、证件号……）。规则已经把电话邮箱认得很好，模型主要比前两类。"""
    g = g.lower()
    if g in ("person",) or NAME_CLASS.search(g):
        return "name"
    if g in ("address", "street address", "city", "postal code") or ADDRESS_CLASS.search(g):
        return "address"
    return "other"


def load(spec):
    kind, _, rest = spec.partition(":")
    if kind in ("gliner", "gliner+org"):
        from gliner import GLiNER
        repo, _, onnx = rest.partition(":")
        if onnx:
            m = GLiNER.from_pretrained(repo, load_onnx_model=True, load_tokenizer=True, onnx_model_file=onnx)
        else:
            m = GLiNER.from_pretrained(repo)
        labels = GLINER_LABELS + (GLINER_RIVALS if kind == "gliner+org" else [])
        return lambda text, thr: [(label_class(e["label"]), e["text"]) for e in m.predict_entities(text, labels, threshold=thr)
                                  if e["label"] in GLINER_LABELS]
    if kind == "ort":
        import numpy as np, onnxruntime as ort
        from huggingface_hub import hf_hub_download
        from tokenizers import Tokenizer
        repo, _, onnx = rest.partition(":")
        tok = Tokenizer.from_file(hf_hub_download(repo, "tokenizer.json"))
        tok.no_padding(); tok.enable_truncation(512)
        id2label = {int(k): v for k, v in json.load(open(hf_hub_download(repo, "config.json")))["id2label"].items()}
        local = os.path.exists(onnx)    # 也可以给本地的 .onnx（比如自己量化出来的），分词器和标签仍从仓库取
        sess = ort.InferenceSession(onnx if local else hf_hub_download(repo, onnx), providers=["CPUExecutionProvider"])
        names = [i.name for i in sess.get_inputs()]

        def block(text, thr):
            enc = tok.encode(text)
            feed = {"input_ids": np.array([enc.ids], dtype=np.int64), "attention_mask": np.array([enc.attention_mask], dtype=np.int64)}
            if "token_type_ids" in names:
                feed["token_type_ids"] = np.zeros_like(feed["input_ids"])
            logits = sess.run(None, {k: v for k, v in feed.items() if k in names})[0][0]
            p = np.exp(logits - logits.max(-1, keepdims=True)); p /= p.sum(-1, keepdims=True)
            out, cur = [], None          # cur = [类型, 起, 止, 分数和, 个数]
            for i, (a, b) in enumerate(enc.offsets):
                if a == b:               # 特殊符号
                    continue
                lab = id2label[int(p[i].argmax())]
                typ = lab[2:] if lab[:2] in ("B-", "I-") else None
                # 同类型的 I- 接在后面，或者紧挨着（WordPiece 的 ##）就并进当前段
                if typ and cur and cur[0] == typ and (lab.startswith("I-") or a == cur[2]):
                    cur[2] = b; cur[3] += p[i].max(); cur[4] += 1
                    continue
                if cur:
                    out.append(cur)
                cur = [typ, a, b, p[i].max(), 1] if typ else None
            if cur:
                out.append(cur)
            spans = sorted((a, b, label_class(typ)) for typ, a, b, sc, n in out if keep_label(typ) and sc / n >= thr)
            return [(c, text[a:b]) for a, b, c in merge_adjacent(text, spans)]
        return chunked(block)
    if kind == "hf":
        from transformers import pipeline
        nlp = pipeline("token-classification", model=rest, aggregation_strategy="simple", device=-1)

        def block(text, thr):
            spans = sorted((e["start"], e["end"], label_class(e.get("entity_group") or e.get("entity", ""))) for e in nlp(text)
                           if e["score"] >= thr and keep_label(e.get("entity_group") or e.get("entity", "")))
            return [(c, text[a:b]) for a, b, c in merge_adjacent(text, spans)]
        return chunked(block)
    raise SystemExit(f"不认得的模型写法：{spec}")


def main(ocr_dir, out_path, spec, threshold=0.3):
    predict = load(spec)
    out, times = {}, []
    for fn in sorted(os.listdir(ocr_dir)):
        if not fn.startswith("en-") or not fn.endswith(".json"):
            continue
        lines = [l["text"] for l in json.load(open(os.path.join(ocr_dir, fn), encoding="utf-8"))["lines"] if l["conf"] >= 0.5]
        t0 = time.time()
        if os.environ.get("NER_MODE") == "line":
            found = [f for line in lines for f in predict(line, threshold)]
        else:
            found = predict("\n".join(lines), threshold)
        times.append(time.time() - t0)
        page = {"ner-name": [], "ner-address": [], "ner-other": []}
        for cls, f in found:
            page["ner-" + cls] += pieces(f)
        out[fn[:-5]] = page
    json.dump(out, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"{spec}: {len(times)} pages, median {sorted(times)[len(times) // 2] * 1000:.0f} ms/page")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]) if len(sys.argv) > 4 else 0.3)
