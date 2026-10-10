"""fp32 ONNX → fp16，旋转位置编码（rotary_emb）留在 fp32。给 ModernBERT / Ettin 底座的模型用（GLiNER-PII edge、small，Ettin PII）。

用法：python fp16_onnx.py <fp32.onnx> <输出 fp16.onnx>

为什么要自己转：knowledgator 发的 gliner-pii-edge 的 model_fp16.onnx 在 ONNX Runtime 1.31 上加载就报 Type Error
（rotary_emb 里一个 Cast 的输出是 float16，下游期望 float）；整图直接转 fp16 也一样。rotary 算的是「位置 × 频率」，
位置到上千时 fp16 的精度不够，本来就该留在 fp32。做法：
  1. onnxruntime.transformers 的 float16 转换器，rotary_emb 的节点和所有「Cast 到 float」的节点不转（keep_io_types，输入输出仍是 int64 / float）；
  2. 转换器在一个张量被几处用到时会插几个一模一样的 Cast（同名、同输出），ORT 不认，删掉重复的。
GLiNER-PII edge：181 MB → 91 MB，70 页基准上与 fp32 的结果一样（66 页逐字相同，召回、误报的数一个不差）。
官方的 uint8（model_quint8.onnx，46 MB）人名召回从 91% 掉到 60%，不能用。
"""
import sys, time
import onnx
import onnxruntime as ort
from onnxruntime.transformers import float16


def main(src, dst):
    t0 = time.time()
    m = onnx.load(src)
    block = [n.name for n in m.graph.node
             if "rotary" in n.name or (n.op_type == "Cast" and any(a.name == "to" and a.i == onnx.TensorProto.FLOAT for a in n.attribute))]
    m = float16.convert_float_to_float16(m, keep_io_types=True, node_block_list=block)
    seen, keep = set(), []
    for node in m.graph.node:
        key = (node.op_type, tuple(node.input), tuple(node.output), tuple(a.SerializeToString() for a in node.attribute))
        if key not in seen:
            seen.add(key)
            keep.append(node)
    dropped = len(m.graph.node) - len(keep)
    del m.graph.node[:]
    m.graph.node.extend(keep)
    onnx.save(m, dst)
    ort.InferenceSession(dst, providers=["CPUExecutionProvider"])     # 能加载才算转好
    print(f"{dst}: {len(block)} nodes kept in fp32, {dropped} duplicate casts dropped, {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
