"""两种补充喂法，回答格式都还是 app 的「行号|类型|原文」：

text_all : 只给文字，但不做 hasWords() 过滤——纯数字、打星号的行也送（证件号就在名字下一行）
hybrid   : 截图 + text_all 的文字一起给。图给版面，文字给逐字原文和行号，
           解析、落到像素都还走 app 现成的 SemanticPrompt.parse + quadForRange。
"""
import json, os, sys
from run_eval import HERE, SAMPLES, chunks, parse, ask, image_part, start, peak_rss_mb

LEAD = "截图本身也附在上面，可以参考版面判断（比如名字旁边、下面写着什么）。\n"


def sendable_all(lines):
    return [l["text"][:120] if l["conf"] >= 0.5 else None for l in lines]


def run(model_key):
    out = {"model": model_key, "text_all": {}, "hybrid": {}}
    p = start(model_key)
    try:
        for s in SAMPLES:
            ocr = json.load(open(os.path.join(HERE, "ocr", f"{s}.json"), encoding="utf-8"))
            texts = [l["text"] for l in ocr["lines"]]
            for mode in ("text_all", "hybrid"):
                replies, perf, found = [], [], []
                for prompt, idx in chunks(sendable_all(ocr["lines"])):
                    content = prompt if mode == "text_all" else \
                        [image_part(os.path.join(HERE, "analysis", f"{s}.png")), {"type": "text", "text": LEAD + prompt}]
                    reply, t = ask(content)
                    replies.append(reply); perf.append(t)
                    found += parse(reply, idx, texts)
                out[mode][s] = {"replies": replies, "perf": perf, "found": [(f["kind"], f["text"]) for f in found]}
                print(f"[{model_key}] {mode} {s}: {out[mode][s]['found']} {perf}", flush=True)
        out["peak_rss_mb"] = peak_rss_mb(p.pid)
    finally:
        p.terminate(); p.wait()
    json.dump(out, open(os.path.join(HERE, "results", f"{model_key}.hybrid.json"), "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    for k in sys.argv[1:]:
        run(k)
