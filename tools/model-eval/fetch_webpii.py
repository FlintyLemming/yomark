"""从 WebPII（HF WebPII/webpii，Apache-2.0）的 test split 取样，给 convert_webpii.py 用。

用法：python fetch_webpii.py <输出目录>   → <输出目录>/meta.json（去掉 image 字段的行）+ img/webpii-<行号>.png

取法：rows API 从第 0 行起每隔 150 行取 12 行，按 page_type 分组，每种取前 5 行。2026-10-10 取到的是 57 行
（行号 0–11、150–153、300–303、900–904、2250–2254、2700–2703、2850–2854、3450–3453、3750–3753、3900–3903、4061、4200–4204），
docs/english-recognition-research.md 的 WebPII 数字就是这 57 页。图的 URL 是一小时过期的签名地址，所以取到就下载。
"""
import json, os, sys, urllib.request, collections, time
out = sys.argv[1]
os.makedirs(out + "/img", exist_ok=True)
rows = []
for off in range(0, 4481, 150):
    url = f"https://datasets-server.huggingface.co/rows?dataset=WebPII/webpii&config=default&split=test&offset={off}&length=12"
    for attempt in range(3):
        try:
            d = json.load(urllib.request.urlopen(url, timeout=60)); break
        except Exception as e:
            print("retry", off, e); time.sleep(3)
    else:
        continue
    for r in d["rows"]:
        row = r["row"]
        row["_idx"] = r["row_idx"]
        rows.append(row)
print("fetched rows", len(rows))
by_type = collections.defaultdict(list)
for r in rows:
    by_type[r["page_type"]].append(r)
keep = []
for t, rs in sorted(by_type.items()):
    keep += rs[:5]
print("page types", len(by_type), "kept", len(keep))
meta = []
for r in keep:
    src = r["image"]["src"]
    p = f"{out}/img/webpii-{r['_idx']}.png"
    try:
        data = urllib.request.urlopen(src, timeout=120).read()
        open(p, "wb").write(data)
    except Exception as e:
        print("img fail", r["_idx"], e); continue
    r = dict(r); r.pop("image")
    meta.append(r)
json.dump(meta, open(f"{out}/meta.json", "w"))
print("saved", len(meta), collections.Counter(r["page_type"] for r in meta).most_common(60))
