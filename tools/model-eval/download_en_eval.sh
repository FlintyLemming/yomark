#!/bin/bash
# 英文调研用的 OCR 候选模型与人名名单。与 app 的构建无关。
#
# OCR：PaddlePaddle 官方在 Hugging Face 上发的是 Paddle 格式（inference.json + .pdiparams），
# 这里用 paddle2onnx 转成 ONNX，再从 inference.yml 里抽出字典（一行一个字，与 app 的 dict.txt 同格式）。
# 需要 Python 3.12 及以下（paddle2onnx 2.x 还没有 3.13 的轮子）：
#   python3.12 -m venv venv && venv/bin/pip install paddlepaddle paddle2onnx onnx pyyaml
#
# 名单：美国人口普查局的公开数据（公有领域）。1990 年的名、2010 年的姓（取前 30000 个）。
set -eu
M=${MODELS_DIR:-$(dirname "$0")/models-en}
N=${NAMES_DIR:-$(dirname "$0")/names}
PY=${PY:-python3.12}
mkdir -p "$M" "$N"

for r in PP-OCRv6_small_det PP-OCRv6_small_rec PP-OCRv6_tiny_det PP-OCRv6_tiny_rec PP-OCRv6_medium_rec en_PP-OCRv5_mobile_rec; do
  mkdir -p "$M/$r"
  for f in inference.json inference.pdiparams inference.yml; do
    curl -sSL --fail -C - --retry 3 -o "$M/$r/$f" "https://huggingface.co/PaddlePaddle/$r/resolve/main/$f"
  done
  paddle2onnx --model_dir "$M/$r" --model_filename inference.json --params_filename inference.pdiparams \
    --save_file "$M/$r.onnx" --opset_version 14 > "$M/$r.convert.log" 2>&1
  case $r in *_rec)
    "$PY" -c 'import sys, yaml; d = yaml.safe_load(open(sys.argv[1]))["PostProcess"]["character_dict"]; open(sys.argv[2], "w").write("\n".join(map(str, d)) + "\n")' \
      "$M/$r/inference.yml" "$M/$r.dict.txt" ;;
  esac
  echo "OK $r"
done

curl -sSL --fail -o "$N/surnames.zip" https://www2.census.gov/topics/genealogy/2010surnames/names.zip
for f in dist.female.first dist.male.first; do
  curl -sSL --fail -o "$N/$f" "https://www2.census.gov/topics/genealogy/1990surnames/$f"
done
"$PY" - "$N" <<'EOF'
import csv, io, sys, zipfile
n = sys.argv[1]
first = {line.split()[0].capitalize() for f in ("dist.female.first", "dist.male.first") for line in open(f"{n}/{f}")}
rows = list(csv.reader(io.TextIOWrapper(zipfile.ZipFile(f"{n}/surnames.zip").open("Names_2010Census.csv"))))[1:]
last = [r[0].capitalize() for r in rows if r[0] != "ALL OTHER NAMES"][:30000]
open(f"{n}/first.txt", "w").write("\n".join(sorted(first)) + "\n")
open(f"{n}/last.txt", "w").write("\n".join(last) + "\n")
print(f"names: {len(first)} first, {len(last)} last")
EOF
