from __future__ import annotations

from config import DATA_DIR
from tools.common import keyword_score, load_json
from tools.source_scanner import search_source_library


def retrieve_regulations(query: str, top_k: int = 3) -> list[dict]:
    regulations = load_json(DATA_DIR / "regulations.json")
    scored = []
    for reg in regulations:
        score = keyword_score(query, reg.get("keywords", []))
        if score:
            scored.append((score, reg))
    if not scored:
        scored = [(0, reg) for reg in regulations[:top_k]]
    scored.sort(key=lambda item: item[0], reverse=True)
    results = []
    for score, reg in scored[:top_k]:
        item = dict(reg)
        item["match_score"] = score
        item["source"] = "curated_demo_data"
        results.append(item)

    source_hits = [
        hit for hit in search_source_library(query, top_k=top_k)
        if hit["source_type"] == "regulation"
    ]
    for hit in source_hits:
        results.append({
            "id": "source_" + hit["name"],
            "title": hit["name"],
            "keywords": [hit["name"]],
            "content": "从原始资料库实时检索到的候选规程/规定文件，后续可接入OCR、BGE-M3或LLM进行条款级抽取。",
            "match_score": hit["match_score"],
            "source": "source_library",
            "path": hit["path"],
            "preview": hit.get("preview", "")
        })
    return results
