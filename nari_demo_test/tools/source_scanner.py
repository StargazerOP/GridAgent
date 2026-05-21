from __future__ import annotations

from pathlib import Path

from config import SOURCE_LIBRARY_DIR, SOURCE_PLAN_DIR, SOURCE_REGULATION_DIR
from tools.common import keyword_score, safe_read_text

SUPPORTED_SUFFIXES = {".docx", ".doc", ".pdf", ".txt", ".csv", ".json", ".xls"}


def scan_source_files(root: Path | None = None) -> list[dict]:
    root = root or SOURCE_LIBRARY_DIR
    if not root.exists():
        return []
    records = []
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in SUPPORTED_SUFFIXES:
            continue
        records.append({
            "name": path.name,
            "path": str(path),
            "suffix": path.suffix.lower(),
            "size": path.stat().st_size,
            "mtime": path.stat().st_mtime,
            "source_type": classify_source_file(path)
        })
    return records


def classify_source_file(path: Path) -> str:
    text = str(path)
    name = path.name
    if str(SOURCE_PLAN_DIR) in text or "预案" in name or "事故" in name or "运行方式" in name:
        return "plan"
    if str(SOURCE_REGULATION_DIR) in text or "规定" in name or "调规" in name or "规程" in name:
        return "regulation"
    if "export+" in name or path.suffix.lower() in {".csv", ".json"}:
        return "structured_data"
    return "reference"


def search_source_library(query: str, top_k: int = 5) -> list[dict]:
    files = scan_source_files()
    query_terms = _query_terms(query)
    scored = []
    for record in files:
        path = Path(record["path"])
        haystack = f"{record['name']} {record['path']} {record['source_type']}"
        score = 0
        if query in haystack:
            score += 10
        for term in query_terms:
            if term and term in haystack:
                score += 3 if len(term) >= 3 else 1
        score += keyword_score(query, [record["source_type"]])
        preview = ""
        if path.suffix.lower() in {".docx", ".txt", ".csv", ".json"}:
            preview = safe_read_text(path, max_chars=500)
            for term in query_terms:
                if term and term in preview:
                    score += 1
        if score:
            item = dict(record)
            item["match_score"] = score
            item["preview"] = preview[:300]
            scored.append(item)
    scored.sort(key=lambda item: item["match_score"], reverse=True)
    return scored[:top_k]


def _query_terms(query: str) -> list[str]:
    import re

    terms = []
    for pattern in [
        r"[\u4e00-\u9fa5A-Za-z0-9#_]+变",
        r"[\u4e00-\u9fa5A-Za-z0-9#_]+线[0-9A-Za-z#]*",
        r"[\u4e00-\u9fa5A-Za-z0-9#_]+风电场",
        r"全停",
        r"全所失电",
        r"事故",
        r"预案",
        r"N-[12]",
        r"母线",
        r"检修",
        r"保护",
        r"转供"
    ]:
        for match in re.finditer(pattern, query):
            term = match.group(0)
            if term not in terms:
                terms.append(term)
    for part in re.split(r"[，。；;:：、\s？?]+", query):
        part = part.strip()
        if len(part) >= 2 and part not in terms:
            terms.append(part)
    return terms
