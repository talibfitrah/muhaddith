# -*- coding: utf-8 -*-
"""يصدّر bge-m3 إلى ONNX بعد تقليم مفردات التضمين إلى القطع العربية والمستعملة، ثم يكمّمه int8."""
import json, struct, os, torch, numpy as np
from transformers import XLMRobertaModel
SRC = "/home/claude/muhaddith-data/bge"
keep = json.load(open("keep_pieces.json")); K = len(keep)
new_id = {old: i for i, old in enumerate(keep)}
assert [new_id[i] for i in range(4)] == [0, 1, 2, 3]
model = XLMRobertaModel.from_pretrained(SRC, torch_dtype=torch.float32).eval()
old_emb = model.embeddings.word_embeddings.weight.data
pruned = torch.nn.Embedding(K, old_emb.shape[1], padding_idx=1)
pruned.weight.data = old_emb[torch.tensor(keep)].clone()
model.embeddings.word_embeddings = pruned
model.config.vocab_size = K
del old_emb
class W(torch.nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, input_ids, attention_mask):
        h = s.m(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state[:, 0]
        return torch.nn.functional.normalize(h, p=2, dim=1)
w = W(model)
ids = torch.tensor([[0, 5, 6, 2, 1, 1]]); mask = torch.tensor([[1, 1, 1, 1, 0, 0]])
os.makedirs("bge-onnx", exist_ok=True)
torch.onnx.export(w, (ids, mask), "bge-onnx/bge_fp32.onnx", input_names=["input_ids", "attention_mask"], output_names=["embedding"],
                  dynamic_axes={"input_ids": {0: "b", 1: "s"}, "attention_mask": {0: "b", 1: "s"}, "embedding": {0: "b"}}, opset_version=14)
print("fp32 onnx MB:", round(os.path.getsize("bge-onnx/bge_fp32.onnx") / 1e6))
del model, w
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic("bge-onnx/bge_fp32.onnx", "bge-onnx/bge_int8.onnx", weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gather"])
print("int8 onnx MB:", round(os.path.getsize("bge-onnx/bge_int8.onnx") / 1e6))
# المفردات المقلَّمة بمعرّفاتها الجديدة
vocab = json.load(open(f"{SRC}/tokenizer.json", encoding="utf-8"))["model"]["vocab"]
with open("bge-onnx/bge_vocab.bin", "wb") as f:
    f.write(b"MSBT"); f.write(struct.pack("<I", K))
    for old in keep:
        p, s = vocab[old]; b = p.encode("utf-8"); f.write(struct.pack("<H", len(b))); f.write(b); f.write(struct.pack("<f", float(s)))
json.dump({"name": "bge-m3", "dim": 1024, "query_prefix": "", "max_len": 64, "vocab": K}, open("bge-onnx/model.json", "w"))
print("vocab entries:", K)
