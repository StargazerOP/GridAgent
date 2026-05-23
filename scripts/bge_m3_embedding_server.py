import time
from typing import Any, List

import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, Request
from tokenizers import Tokenizer


MODEL_DIR = r"E:\code\bge-m3"
ONNX_MODEL = MODEL_DIR + r"\onnx\model.onnx"
TOKENIZER_FILE = MODEL_DIR + r"\onnx\tokenizer.json"
MAX_LENGTH = 512
MODEL_NAME = "bge-m3"

app = FastAPI(title="Local BGE-M3 Embedding API")
tokenizer = Tokenizer.from_file(TOKENIZER_FILE)
session = ort.InferenceSession(ONNX_MODEL, providers=["CPUExecutionProvider"])


def encode_texts(texts: List[str]) -> List[List[float]]:
    encoded = [tokenizer.encode(text or "") for text in texts]
    token_ids = [item.ids[:MAX_LENGTH] for item in encoded]
    max_len = max(1, max(len(ids) for ids in token_ids))

    input_ids = np.zeros((len(token_ids), max_len), dtype=np.int64)
    attention_mask = np.zeros((len(token_ids), max_len), dtype=np.int64)
    for row, ids in enumerate(token_ids):
        input_ids[row, : len(ids)] = ids
        attention_mask[row, : len(ids)] = 1

    outputs = session.run(
        ["sentence_embedding"],
        {"input_ids": input_ids, "attention_mask": attention_mask},
    )
    vectors = outputs[0].astype(np.float32)
    norms = np.linalg.norm(vectors, axis=1, keepdims=True)
    vectors = vectors / np.maximum(norms, 1e-12)
    return vectors.tolist()


@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "UP", "model": MODEL_NAME, "dimension": 1024}


@app.post("/v1/embeddings")
@app.post("/embeddings")
async def embeddings(request: Request) -> dict[str, Any]:
    payload = await parse_payload(request)
    raw_input = payload.get("input") or payload.get("inputs") or "rag health check"
    if isinstance(raw_input, str):
        texts = [raw_input]
    elif isinstance(raw_input, list):
        texts = [str(text) for text in raw_input if text is not None]
    else:
        texts = [str(raw_input)]
    if not texts:
        texts = ["rag health check"]
    vectors = encode_texts(texts)
    return {
        "object": "list",
        "model": payload.get("model") or MODEL_NAME,
        "data": [
            {"object": "embedding", "index": index, "embedding": vector}
            for index, vector in enumerate(vectors)
        ],
        "usage": {
            "prompt_tokens": sum(len(tokenizer.encode(text or "").ids) for text in texts),
            "total_tokens": sum(len(tokenizer.encode(text or "").ids) for text in texts),
        },
        "created": int(time.time()),
    }


async def parse_payload(request: Request) -> dict[str, Any]:
    try:
        payload = await request.json()
        return payload if isinstance(payload, dict) else {}
    except Exception:
        return {}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=9910)
