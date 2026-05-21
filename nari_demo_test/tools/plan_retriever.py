from __future__ import annotations

from config import DATA_DIR
from tools.common import keyword_score, load_json, safe_read_text
from tools.source_scanner import search_source_library


def retrieve_plans(query: str, top_k: int = 2) -> list[dict]:
    plans = load_json(DATA_DIR / "contingency_plans.json")
    result = []
    source_hits = [
        hit for hit in search_source_library(query, top_k=top_k)
        if hit["source_type"] == "plan"
    ]
    for hit in source_hits:
        extracted = _extract_plan_sections(hit["path"])
        result.append({
            "id": "source_" + hit["name"],
            "title": hit["name"],
            "keywords": [hit["name"]],
            "source_file": hit["path"],
            "impact": extracted["impact"] or ["从原始资料库实时检索到候选预案，未抽取到明确影响范围。"],
            "steps": extracted["steps"] or ["读取原文并抽取事故情况、影响范围、处置步骤。"],
            "match_score": hit["match_score"] + 100,
            "source": "source_library",
            "preview": hit.get("preview", "")
        })

    for plan in plans:
        score = keyword_score(query, plan.get("keywords", []))
        if score:
            item = dict(plan)
            item["match_score"] = score
            item["source"] = "curated_demo_data"
            result.append(item)
    if not result:
        for plan in plans[:top_k]:
            item = dict(plan)
            item["match_score"] = 0
            item["source"] = "curated_demo_data_fallback"
            result.append(item)
    result.sort(key=lambda item: item["match_score"], reverse=True)
    return result[:top_k]


def _extract_plan_sections(path: str) -> dict:
    text = safe_read_text(__import__("pathlib").Path(path), max_chars=20000)
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    impact = []
    steps = []
    section = ""
    for line in lines:
        normalized = line.replace("：", ":")
        if "事故情况" in line or "影响" in line:
            section = "impact"
            continue
        if "处理过程" in line or "处置步骤" in line or "调度处置步骤" in line:
            section = "steps"
            continue
        if section == "impact" and len(impact) < 8:
            if not _is_heading(line):
                impact.append(line)
        elif section == "steps" and len(steps) < 12:
            if not _is_heading(line):
                steps.append(_clean_step(line))
    return {"impact": impact, "steps": steps}


def _is_heading(line: str) -> bool:
    return line in {"一、", "二、", "三、"} or len(line) <= 2


def _clean_step(line: str) -> str:
    return line.strip(" 　\t")
