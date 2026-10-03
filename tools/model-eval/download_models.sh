#!/bin/bash
# 下载评测用的候选模型（GGUF + 视觉投影）。约 13 GB，断点续传。
set -u
M=${MODELS_DIR:-$(dirname "$0")/models}
mkdir -p "$M"
get() { # repo file outname
  curl -sSL --fail -C - --retry 3 -o "$M/$3" "https://huggingface.co/$1/resolve/main/$2" && echo "OK $3" || echo "FAIL $3"
}
get unsloth/Qwen3.5-0.8B-GGUF Qwen3.5-0.8B-Q4_K_M.gguf qwen35-08b.gguf
get unsloth/Qwen3.5-0.8B-GGUF mmproj-F16.gguf qwen35-08b-mmproj.gguf
get unsloth/Qwen3.5-2B-GGUF Qwen3.5-2B-Q4_K_M.gguf qwen35-2b.gguf
get unsloth/Qwen3.5-2B-GGUF mmproj-F16.gguf qwen35-2b-mmproj.gguf
get unsloth/Qwen3.5-4B-GGUF Qwen3.5-4B-Q4_K_M.gguf qwen35-4b.gguf
get unsloth/Qwen3.5-4B-GGUF mmproj-F16.gguf qwen35-4b-mmproj.gguf
get openbmb/MiniCPM-V-4.6-gguf MiniCPM-V-4_6-Q4_K_M.gguf minicpmv46.gguf
get openbmb/MiniCPM-V-4.6-gguf mmproj-model-f16.gguf minicpmv46-mmproj.gguf
get google/gemma-4-E2B-it-qat-q4_0-gguf gemma-4-E2B_q4_0-it.gguf gemma4-e2b.gguf
get google/gemma-4-E2B-it-qat-q4_0-gguf gemma-4-E2B-it-mmproj.gguf gemma4-e2b-mmproj.gguf
get LiquidAI/LFM2.5-VL-1.6B-GGUF LFM2.5-VL-1.6B-Q4_K_M.gguf lfm25vl-16b.gguf
get LiquidAI/LFM2.5-VL-1.6B-GGUF mmproj-LFM2.5-VL-1.6b-Q8_0.gguf lfm25vl-16b-mmproj.gguf
