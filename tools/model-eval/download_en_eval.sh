#!/bin/bash
# 英文调研用的 OCR 候选模型、人名名单和字体。与 app 的构建无关。
#
# OCR：PaddlePaddle 在 Hugging Face 上有官方的 ONNX 导出（<模型名>_onnx 仓库，inference.onnx + inference.yml），
# 与自己用 paddle2onnx 转的逐位一致（输出最大差 1e-6），直接用。字典从 inference.yml 里抽出来
# （一行一个字，与 app 的 dict.txt 同格式），需要 pyyaml。
#
# 名单：美国人口普查局的公开数据（公有领域）。1990 年的名、2010 年的姓（取前 30000 个）。
#
# 字体：压力测试页（make_ocr_stress.py）、名址基准页（make_ner_bench.py）用的 Roboto、Noto Sans SC 等，
# 都是 OFL，从 jsDelivr 取 google/fonts 仓库里的文件。
set -eu
M=${MODELS_DIR:-$(dirname "$0")/models-en}
N=${NAMES_DIR:-$(dirname "$0")/names}
F=${FONTS_DIR:-$(dirname "$0")/fonts}
PY=${PY:-python3}
mkdir -p "$M" "$N" "$F"

for r in PP-OCRv6_small_det PP-OCRv6_small_rec PP-OCRv6_tiny_det PP-OCRv6_tiny_rec PP-OCRv6_medium_rec en_PP-OCRv5_mobile_rec; do
  curl -sSL --fail -C - --retry 3 -o "$M/$r.onnx" "https://huggingface.co/PaddlePaddle/${r}_onnx/resolve/main/inference.onnx"
  case $r in *_rec)
    curl -sSL --fail --retry 3 -o "$M/$r.yml" "https://huggingface.co/PaddlePaddle/${r}_onnx/resolve/main/inference.yml"
    "$PY" -c 'import sys, yaml; d = yaml.safe_load(open(sys.argv[1]))["PostProcess"]["character_dict"]; open(sys.argv[2], "w").write("\n".join(map(str, d)) + "\n")' \
      "$M/$r.yml" "$M/$r.dict.txt" ;;
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

G=https://cdn.jsdelivr.net/gh/google/fonts@main/ofl
curl -sSL --fail -o "$F/Roboto.ttf" "$G/roboto/Roboto%5Bwdth,wght%5D.ttf"
curl -sSL --fail -o "$F/NotoSansSC.ttf" "$G/notosanssc/NotoSansSC%5Bwght%5D.ttf"
curl -sSL --fail -o "$F/OpenSans.ttf" "$G/opensans/OpenSans%5Bwdth,wght%5D.ttf"
curl -sSL --fail -o "$F/Lato.ttf" "$G/lato/Lato-Regular.ttf"
curl -sSL --fail -o "$F/SourceSerif4.ttf" "$G/sourceserif4/SourceSerif4%5Bopsz,wght%5D.ttf"
curl -sSL --fail -o "$F/RobotoMono.ttf" "$G/robotomono/RobotoMono%5Bwght%5D.ttf"
echo "fonts: $(ls "$F" | tr '\n' ' ')"
