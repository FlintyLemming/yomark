"""把 WebPII（HF WebPII/webpii，Apache-2.0）的样本转成本目录的基准格式：截图 + 真值 JSON。

用法：python convert_webpii.py <meta.json> <图目录> <输出目录>
  meta.json：rows API 取回的行（去掉 image 字段），图目录里是同 _idx 的 webpii-<idx>.png（可以是 JPEG 内容）。

WebPII 是合成的电商网页截图（1280 宽），每个元素带 key、value 和框。归类：
  find    人名（PII_FULLNAME*、PII_FIRSTNAME*、PII_LASTNAME*、PII_GIFT_FULLNAME*）、街道（PII_STREET*、PII_CITY_STATE_ZIP）、
          邮编（PII_POSTCODE*）、电话、邮箱；
  neutral 单独的城市、州、国家，门店地址（PII_LOCATIONn_*，公开信息），礼品留言、卡号末四位、优惠码，订单里的各项；
  avoid   商品品牌（PRODUCT*_BRAND，「Generic」除外）。
只收可见、没被截断的元素。识别要用 OcrBench 的 full 口径（不降采样），高的页切条、识别后用 merge_tiles.py 拼回。值是 Faker 造的（「Georgebury」这种城市），按邮编和州是否对得上做校验的规则在这里会吃亏。
"""
import json, os, re, sys
from PIL import Image

FIND = re.compile(r"^PII_(FULLNAME|FIRSTNAME|LASTNAME|GIFT_FULLNAME|STREET|CITY_STATE_ZIP|POSTCODE|PHONE|EMAIL)\d*(_FULL|_NUMERIC)?$")
TILE = 1200
KIND = {"FULLNAME": "name", "FIRSTNAME": "name", "LASTNAME": "name", "GIFT_FULLNAME": "name", "STREET": "address",
        "CITY_STATE_ZIP": "address", "POSTCODE": "address", "PHONE": "phone", "EMAIL": "email"}


def main(meta_path, img_dir, out):
    os.makedirs(out + "/samples", exist_ok=True)
    os.makedirs(out + "/truth", exist_ok=True)
    n_find = 0
    for r in json.load(open(meta_path)):
        name = f"en-w-{r['page_type']}-{r['_idx']}"
        src = os.path.join(img_dir, f"webpii-{r['_idx']}.png")
        if not os.path.exists(src):
            continue
        img = Image.open(src).convert("RGB")
        # 桌面网页按 1:1 像素渲染（字高约 14px）：要按 full 口径识别，不能降采样；太高的页切成 1200 高的横条，
        # 不然检测前整页缩到长边 1536，字只剩六七个像素。识别完用 merge_tiles.py 拼回一页
        if img.height > TILE + 200:
            for k, y in enumerate(range(0, img.height, TILE)):
                img.crop((0, y, img.width, min(img.height, y + TILE))).save(f"{out}/samples/{name}__t{k}.png")
        else:
            img.save(f"{out}/samples/{name}.png")
        find, neutral, avoid = [], [], []
        for e in json.loads(r["pii_elements_json"]):
            v = str(e.get("value") or "").strip()
            if not v or not e.get("visible", True) or e.get("clipped"):
                continue
            m = FIND.match(e["key"])
            if m:
                find.append({"kind": KIND[m.group(1)], "text": v})
            else:
                neutral.append(v)
        for e in json.loads(r["product_elements_json"]):
            v = str(e.get("value") or "").strip()
            if e["key"].endswith("_BRAND") and v and v.lower() != "generic":
                avoid.append(v)
        for key in ("order_elements_json", "search_elements_json", "misc_elements_json"):
            neutral += [str(e.get("value") or "").strip() for e in json.loads(r[key]) if str(e.get("value") or "").strip()]
        uniq = {(f["kind"], f["text"]): f for f in find}
        n_find += len(uniq)
        json.dump({"find": list(uniq.values()), "avoid": sorted(set(avoid)), "neutral": sorted(set(neutral)), "rows": [],
                   "meta": {"page_type": r["page_type"], "company": r["company"], "source": "WebPII/webpii test", "row": r["_idx"]}},
                  open(f"{out}/truth/{name}.json", "w"), ensure_ascii=False, indent=1)
    print(f"{len(os.listdir(out + '/truth'))} pages ({len(os.listdir(out + '/samples'))} images), {n_find} find items -> {out}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
