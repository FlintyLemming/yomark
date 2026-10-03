"""端侧候选模型评测：同一个模型分别吃「PP-OCR 文字」和「截图」，外加条码判断题。

文字模式逐字复用 app 的提示词（instructions.txt 由 SemanticPrompt.INSTRUCTIONS 导出）、
切块规则（GeminiNanoClassifier：置信度 ≥ 0.5 且至少两个字母的行才送）和解析规则
（SemanticPrompt.parse：行号|类型|原文，原文必须能在那一行里找到）。

用法：python run_eval.py <model_key> [<model_key> ...]
"""
import base64, io, json, os, re, subprocess, sys, time, urllib.request
from PIL import Image

HERE = os.environ.get("MODEL_EVAL_OUT", os.path.join(os.path.dirname(os.path.abspath(__file__)), "out"))
MODELS_DIR = os.environ.get("MODELS_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "models"))
SERVER = os.environ.get("LLAMA_SERVER", "llama-server")
PORT = 8091

MODELS = {
    "qwen35-08b": ("qwen35-08b.gguf", "qwen35-08b-mmproj.gguf"),
    "qwen35-2b": ("qwen35-2b.gguf", "qwen35-2b-mmproj.gguf"),
    "qwen35-4b": ("qwen35-4b.gguf", "qwen35-4b-mmproj.gguf"),
    "minicpmv46": ("minicpmv46.gguf", "minicpmv46-mmproj.gguf"),
    "gemma4-e2b": ("gemma4-e2b.gguf", "gemma4-e2b-mmproj.gguf"),
    "lfm25vl-16b": ("lfm25vl-16b.gguf", "lfm25vl-16b-mmproj.gguf"),
}

# 有真值、也跑过 OCR 的页都算样本（out/truth/<名>.json + out/ocr/<名>.json）
SAMPLES = sorted(f[:-5] for f in os.listdir(os.path.join(HERE, "truth"))
                 if os.path.exists(os.path.join(HERE, "ocr", f)))
PROBES = sorted(f for f in os.listdir(os.path.join(HERE, "probes")) if f.endswith(".png"))

INSTRUCTIONS = open(os.path.join(HERE, "instructions.txt"), encoding="utf-8").read()

IMAGE_INSTRUCTIONS = (
    "你在帮用户给手机截图打码。请看这张截图，找出其中能指向具体某个人的隐私信息，只包括四类：\n"
    "人名：真实姓名，或带姓的称呼（如「王先生」）\n"
    "地址：住址、收货地址、小区楼栋门牌\n"
    "电话：手机号、座机号，包括打了星号的\n"
    "号码：身份证号、银行卡号、账号、取件码、验证码等个人号码\n"
    "不要输出：店铺名、公司名、商品名、按钮和提示文字、城市名、日期时间、价格。\n"
    "每找到一处输出一行，格式为：类型|原文\n"
    "原文必须与截图上的文字逐字一致，只写隐私的那一段，不加引号、不加解释。\n"
    "一处也没有就只输出：无\n"
)
BARCODE_PROMPT = "这张图片里有没有条形码或二维码？只回答「有」或「没有」。"

# ---------- SemanticPrompt / GeminiNanoClassifier 的 Python 移植 ----------
CHUNK_CHARS, MAX_LINE_CHARS, MAX_SPAN, MAX_NAME = 1200, 120, 60, 8
ROW = re.compile(r"(\d+)\s*[|｜]\s*(人名|地址|电话|号码)\s*[|｜]\s*(.+)")
QUOTES = "\"'「」“”‘’`"


def sendable(lines):
    """GeminiNanoClassifier.classify：置信度 ≥ 0.5 且至少两个字母（汉字算字母）。"""
    return [l["text"][:MAX_LINE_CHARS] if l["conf"] >= 0.5 and sum(c.isalpha() for c in l["text"]) >= 2 else None
            for l in lines]


def chunks(texts):
    out, body, idx = [], "", []
    for i, t in enumerate(texts):
        if t is None:
            continue
        if len(body) + len(t) > CHUNK_CHARS and idx:
            out.append((INSTRUCTIONS + body, idx)); body, idx = "", []
        idx.append(i)
        body += f"{len(idx)}: {t}\n"
    if idx:
        out.append((INSTRUCTIONS + body, idx))
    return out


def locate(line, quote):
    at = line.find(quote)
    if at >= 0:
        return (at, at + len(quote) - 1)
    origin = [i for i, c in enumerate(line) if not c.isspace()]
    compact = "".join(line[i] for i in origin)
    q = "".join(c for c in quote if not c.isspace())
    if not q:
        return None
    at = compact.find(q)
    return None if at < 0 else (origin[at], origin[at + len(q) - 1])


def parse(reply, idx, texts):
    found = []
    for row in reply.splitlines():
        m = ROW.fullmatch(row.strip())
        if not m:
            continue
        n = int(m.group(1))
        if not 1 <= n <= len(idx):
            continue
        li, kind = idx[n - 1], m.group(2)
        quote = m.group(3).strip().strip(QUOTES)
        if len(quote) < 2 or len(quote) > MAX_SPAN or (kind == "人名" and len(quote) > MAX_NAME):
            continue
        r = locate(texts[li], quote)
        if r and (li, r, kind) not in [(f["line"], f["range"], f["kind"]) for f in found]:
            found.append({"line": li, "range": r, "kind": kind, "text": texts[li][r[0]:r[1] + 1]})
    return found


# ---------- 打分 ----------
def norm(s):
    s = re.sub(r"\s+", "", s)
    s = re.sub(r"[*＊•●xX]+", "*", s)
    return s.replace("￥", "¥")


def hits(found_text, item):
    f = norm(found_text)
    for g in [item["text"]] + item.get("alias", []):
        g = norm(g)
        short, long_ = (f, g) if len(f) <= len(g) else (g, f)
        # 引用比真值长太多（整行照抄）不算命中：那等于把整行圈出来，不是认出了这一处
        if len(short) >= 2 and short in long_ and len(short) >= 0.5 * len(g) and len(f) <= max(2 * len(g), len(g) + 6):
            return True
    return False


def score(found_texts, truth):
    hit = [any(hits(t, item) for t in found_texts) for item in truth["find"]]
    fp, other = [], []
    for t in found_texts:
        if any(hits(t, item) for item in truth["find"]):
            continue
        (fp if any(norm(a) in norm(t) or norm(t) in norm(a) for a in truth["avoid"]) else other).append(t)
    return {"hit": sum(hit), "total": len(hit), "missed": [i["text"] for i, h in zip(truth["find"], hit) if not h],
            "fp": fp, "other": other}


# ---------- llama-server ----------
def post(payload, timeout=1800):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/v1/chat/completions",
                                 data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        d = json.load(r)
    d["_wall"] = time.time() - t0
    return d


def ask(content, max_tokens=384):
    d = post({"messages": [{"role": "user", "content": content}], "temperature": 0, "top_k": 1,
              "max_tokens": max_tokens, "chat_template_kwargs": {"enable_thinking": False}})
    msg = d["choices"][0]["message"]
    t = d.get("timings", {})
    return (msg.get("content") or ""), {"wall_s": round(d["_wall"], 2), "prompt_n": t.get("prompt_n"),
                                        "prompt_ms": t.get("prompt_ms"), "gen_n": t.get("predicted_n"),
                                        "gen_ms": t.get("predicted_ms")}


def image_part(path):
    buf = io.BytesIO()
    Image.open(path).convert("RGB").save(buf, "PNG")
    return {"type": "image_url", "image_url": {"url": "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()}}


def start(model_key):
    gguf, mmproj = MODELS[model_key]
    log = open(os.path.join(HERE, "results", f"{model_key}.server.log"), "w")
    p = subprocess.Popen([SERVER, "-m", os.path.join(MODELS_DIR, gguf), "--mmproj", os.path.join(MODELS_DIR, mmproj),
                          "-c", "16384", "-t", "4", "-np", "1", "--port", str(PORT), "--no-webui",
                          "--reasoning", "off", "--no-mmproj-offload"], stdout=log, stderr=subprocess.STDOUT)
    for _ in range(600):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=5) as r:
                if r.status == 200:
                    return p
        except Exception:
            pass
        if p.poll() is not None:
            raise RuntimeError(f"server exited, see {log.name}")
        time.sleep(1)
    raise RuntimeError("server did not become healthy")


def peak_rss_mb(pid):
    for line in open(f"/proc/{pid}/status"):
        if line.startswith("VmHWM"):
            return int(line.split()[1]) // 1024


def run(model_key):
    out = {"model": model_key, "text": {}, "image": {}, "barcode": {}}
    p = start(model_key)
    try:
        for s in SAMPLES:
            ocr = json.load(open(os.path.join(HERE, "ocr", f"{s}.json"), encoding="utf-8"))
            truth = json.load(open(os.path.join(HERE, "truth", f"{s}.json"), encoding="utf-8"))
            lines = ocr["lines"]
            texts = [l["text"] for l in lines]

            # 文字模式：app 的原提示词 + 原解析
            found, replies, perf = [], [], []
            for prompt, idx in chunks(sendable(lines)):
                reply, t = ask(prompt)
                replies.append(reply); perf.append(t)
                found += parse(reply, idx, texts)
            out["text"][s] = {"found": [(f["kind"], f["text"]) for f in found], "score": score([f["text"] for f in found], truth),
                              "replies": replies, "perf": perf}
            print(f"[{model_key}] text  {s}: {out['text'][s]['score']['hit']}/{out['text'][s]['score']['total']} "
                  f"fp={out['text'][s]['score']['fp']} other={out['text'][s]['score']['other']} {perf}", flush=True)

            # 图片模式：直接看截图（与设备上识别用的同一张降采样图）
            reply, t = ask([image_part(os.path.join(HERE, "analysis", f"{s}.png")), {"type": "text", "text": IMAGE_INSTRUCTIONS}], 512)
            items = []
            for row in reply.splitlines():
                m = re.search(r"(人名|地址|电话|号码)\s*[|｜]\s*(.+)", row)
                if m:
                    items.append((m.group(1), m.group(2).strip().strip(QUOTES)))
            grounded = [q for _, q in items if any(locate(tx, q) for tx in texts) or any(norm(q) in norm(tx) for tx in texts)]
            out["image"][s] = {"found": items, "grounded": len(grounded), "score": score([q for _, q in items], truth),
                               "reply": reply, "perf": t}
            print(f"[{model_key}] image {s}: {out['image'][s]['score']['hit']}/{out['image'][s]['score']['total']} "
                  f"fp={out['image'][s]['score']['fp']} other={out['image'][s]['score']['other']} "
                  f"grounded={len(grounded)}/{len(items)} {t}", flush=True)

        for f in PROBES:
            reply, t = ask([image_part(os.path.join(HERE, "probes", f)), {"type": "text", "text": BARCODE_PROMPT}], 16)
            says_yes = "没有" not in reply and "有" in reply
            out["barcode"][f] = {"reply": reply.strip(), "yes": says_yes, "perf": t}
            print(f"[{model_key}] barcode {f}: {reply.strip()!r} {t}", flush=True)
        out["peak_rss_mb"] = peak_rss_mb(p.pid)
    finally:
        p.terminate(); p.wait()
    json.dump(out, open(os.path.join(HERE, "results", f"{model_key}.json"), "w"), ensure_ascii=False, indent=1)
    print(f"[{model_key}] peak RSS {out.get('peak_rss_mb')} MB", flush=True)


if __name__ == "__main__":
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    for k in sys.argv[1:]:
        run(k)
