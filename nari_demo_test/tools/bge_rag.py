from __future__ import annotations

import hashlib
import json
import math
import time
from pathlib import Path
from typing import Iterable

import numpy as np

from config import (
    BGE_M3_MODEL_DIR,
    RAG_CHUNKS_JSONL,
    RAG_DENSE_NPY,
    RAG_INDEX_DIR,
    RAG_MANIFEST_JSON,
    SOURCE_LIBRARY_DIR,
)
from tools.common import safe_read_text
from tools.llm_client import chat_completion, strip_thinking_text
from tools.source_scanner import scan_source_files

SUPPORTED_TEXT_SUFFIXES = {".docx", ".txt", ".csv", ".json"}


def build_bge_index(
    query_filter: str = "",
    max_files: int = 80,
    chunk_size: int = 700,
    overlap: int = 120,
    batch_size: int = 4,
) -> dict:
    model = _load_bge_model()
    files = _select_files(query_filter=query_filter, max_files=max_files)
    chunks = []
    for record in files:
        path = Path(record["path"])
        text = safe_read_text(path, max_chars=120000)
        for idx, chunk_text in enumerate(_chunk_text(text, chunk_size=chunk_size, overlap=overlap)):
            chunks.append({
                "chunk_id": f"{_hash_text(str(path))}_{idx}",
                "source_file": str(path),
                "source_name": path.name,
                "source_type": record["source_type"],
                "text": chunk_text
            })

    if not chunks:
        raise RuntimeError("没有可切块的原始资料。当前BGE-M3 RAG只索引 .docx/.txt/.csv/.json。")

    vectors = []
    texts = [chunk["text"] for chunk in chunks]
    for start in range(0, len(texts), batch_size):
        batch = texts[start:start + batch_size]
        encoded = model.encode(batch, batch_size=batch_size, max_length=1024)
        dense = encoded["dense_vecs"]
        vectors.extend(dense)

    matrix = np.asarray(vectors, dtype=np.float32)
    matrix = _normalize(matrix)

    RAG_INDEX_DIR.mkdir(parents=True, exist_ok=True)
    with RAG_CHUNKS_JSONL.open("w", encoding="utf-8") as f:
        for chunk in chunks:
            f.write(json.dumps(chunk, ensure_ascii=False) + "\n")
    np.save(RAG_DENSE_NPY, matrix)

    manifest = {
        "created_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "model_dir": str(BGE_M3_MODEL_DIR),
        "source_root": str(SOURCE_LIBRARY_DIR),
        "query_filter": query_filter,
        "file_count": len(files),
        "chunk_count": len(chunks),
        "chunk_size": chunk_size,
        "overlap": overlap,
        "embedding_backend": "FlagEmbedding.BGEM3FlagModel dense_vecs"
    }
    RAG_MANIFEST_JSON.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


def retrieve_bge_chunks(query: str, top_k: int = 5) -> list[dict]:
    _ensure_index()
    model = _load_bge_model()
    chunks = _load_chunks()
    matrix = np.load(RAG_DENSE_NPY)
    encoded = model.encode([query], batch_size=1, max_length=1024)
    q = np.asarray(encoded["dense_vecs"], dtype=np.float32)
    q = _normalize(q)[0]
    scores = matrix @ q
    order = np.argsort(-scores)[:top_k]
    results = []
    for rank, idx in enumerate(order, start=1):
        chunk = dict(chunks[int(idx)])
        chunk["rank"] = rank
        chunk["score"] = float(scores[int(idx)])
        results.append(chunk)
    return results


def answer_with_bge_rag(query: str, top_k: int = 5, use_llm: bool = True) -> dict:
    chunks = retrieve_bge_chunks(query, top_k=top_k)
    context = "\n\n".join(
        f"[{idx}] 来源：{chunk['source_name']}\n{chunk['text']}"
        for idx, chunk in enumerate(chunks, start=1)
    )
    fallback = _fallback_answer(query, chunks)
    llm_result = {"ok": False, "content": "", "skipped": not use_llm}
    answer = fallback
    if use_llm:
        llm_result = chat_completion([
            {
                "role": "system",
                "content": "你是电网调规资料库RAG问答助手。只能基于给定资料片段回答；依据不足时要明确说明。不要输出思考过程。"
            },
            {
                "role": "user",
                "content": f"问题：{query}\n\n资料片段：\n{context}\n\n请给出中文回答，并列出引用来源。"
            }
        ], max_tokens=1200)
        if llm_result.get("ok") and llm_result.get("content"):
            answer = strip_thinking_text(llm_result["content"])
    return {
        "query": query,
        "top_k": top_k,
        "chunks": chunks,
        "llm_result": llm_result,
        "answer": answer
    }


def _load_bge_model():
    from FlagEmbedding import BGEM3FlagModel

    return BGEM3FlagModel(str(BGE_M3_MODEL_DIR), use_fp16=False, devices="cpu")


def _select_files(query_filter: str, max_files: int) -> list[dict]:
    files = [
        record for record in scan_source_files()
        if Path(record["path"]).suffix.lower() in SUPPORTED_TEXT_SUFFIXES
    ]
    if query_filter:
        filtered = [
            record for record in files
            if query_filter in record["name"] or query_filter in record["path"]
        ]
        files = filtered or files
    return files[:max_files]


def _chunk_text(text: str, chunk_size: int, overlap: int) -> Iterable[str]:
    text = "\n".join(line.strip() for line in text.splitlines() if line.strip())
    if not text:
        return
    start = 0
    while start < len(text):
        end = min(start + chunk_size, len(text))
        chunk = text[start:end].strip()
        if chunk:
            yield chunk
        if end >= len(text):
            break
        start = max(0, end - overlap)


def _normalize(matrix: np.ndarray) -> np.ndarray:
    denom = np.linalg.norm(matrix, axis=1, keepdims=True)
    denom[denom == 0] = 1.0
    return matrix / denom


def _load_chunks() -> list[dict]:
    chunks = []
    with RAG_CHUNKS_JSONL.open("r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                chunks.append(json.loads(line))
    return chunks


def _ensure_index() -> None:
    if not RAG_CHUNKS_JSONL.exists() or not RAG_DENSE_NPY.exists():
        raise RuntimeError("BGE-M3 RAG索引不存在，请先运行：python demo_test\\main.py rag-build")


def _hash_text(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()[:12]


def _fallback_answer(query: str, chunks: list[dict]) -> str:
    lines = [f"问题：{query}", "", "BGE-M3 召回片段："]
    for chunk in chunks:
        preview = chunk["text"][:220].replace("\n", " ")
        lines.append(f"- [{chunk['rank']}] {chunk['source_name']} score={chunk['score']:.4f}：{preview}")
    lines.append("")
    lines.append("未调用或未成功调用LLM时，仅展示BGE-M3召回依据；请结合片段人工判断。")
    return "\n".join(lines)

