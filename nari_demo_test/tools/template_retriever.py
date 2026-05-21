from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from config import BGE_M3_MODEL_DIR, DATA_DIR, OUTPUT_DIR
from tools.common import load_json

TEMPLATE_INDEX_DIR = OUTPUT_DIR / "template_index"
TEMPLATE_META_JSON = TEMPLATE_INDEX_DIR / "templates.json"
TEMPLATE_DENSE_NPY = TEMPLATE_INDEX_DIR / "dense.npy"


def build_template_index() -> dict:
    templates = load_json(DATA_DIR / "workflow_templates.json")
    model = _load_bge_model()
    texts = [_template_text(template) for template in templates]
    encoded = model.encode(texts, batch_size=4, max_length=512)
    matrix = _normalize(np.asarray(encoded["dense_vecs"], dtype=np.float32))

    TEMPLATE_INDEX_DIR.mkdir(parents=True, exist_ok=True)
    TEMPLATE_META_JSON.write_text(json.dumps(templates, ensure_ascii=False, indent=2), encoding="utf-8")
    np.save(TEMPLATE_DENSE_NPY, matrix)
    return {
        "template_count": len(templates),
        "index_dir": str(TEMPLATE_INDEX_DIR),
        "model_dir": str(BGE_M3_MODEL_DIR)
    }


def retrieve_template(query: str, top_k: int = 3) -> list[dict]:
    _ensure_template_index()
    templates = load_json(TEMPLATE_META_JSON)
    matrix = np.load(TEMPLATE_DENSE_NPY)
    model = _load_bge_model()
    encoded = model.encode([query], batch_size=1, max_length=512)
    q = _normalize(np.asarray(encoded["dense_vecs"], dtype=np.float32))[0]
    scores = matrix @ q
    order = np.argsort(-scores)[:top_k]
    results = []
    for rank, idx in enumerate(order, start=1):
        template = dict(templates[int(idx)])
        template["rank"] = rank
        template["score"] = float(scores[int(idx)])
        template["rerank_score"] = template["score"] + _rule_bonus(query, template)
        results.append(template)
    results.sort(key=lambda item: item["rerank_score"], reverse=True)
    for rank, template in enumerate(results, start=1):
        template["rank"] = rank
    return results


def _rule_bonus(query: str, template: dict) -> float:
    template_id = template["template_id"]
    bonus = 0.0
    if "变" in query and ("全停" in query or "全所失电" in query):
        if template_id == "fault_substation_blackout":
            bonus += 0.18
        if template_id == "fault_wind_farm_blackout":
            bonus -= 0.12
    if ("风电场" in query or "光伏" in query or "新能源" in query) and ("全停" in query or "全场停电" in query):
        if template_id == "fault_wind_farm_blackout":
            bonus += 0.18
    if "保护" in query or "定值" in query or "过流" in query:
        if template_id == "operation_protection_check":
            bonus += 0.16
    if "转供" in query:
        if template_id == "operation_load_transfer_check":
            bonus += 0.16
    if "N-1" in query or "N-2" in query or "停役" in query or "检修" in query:
        if template_id == "operation_n_minus_one_check":
            bonus += 0.12
    if "500kV" in query and "母线" in query:
        if template_id == "fault_500kv_bus_fault":
            bonus += 0.18
    if "潮流" in query or "越限" in query or "断面" in query or "自动计算" in query:
        if template_id == "power_flow_auto_calculation":
            bonus += 0.2
    if "潮流" in query and ("当前" in query or "实时" in query or "日内" in query or "断面" in query or "节点电压" in query):
        if template_id == "power_flow_snapshot_calculation":
            bonus += 0.24
        if template_id == "power_flow_auto_calculation":
            bonus -= 0.04
    if "潮流" in query and ("调整" in query or "停运" in query or "转供" in query or "越限" in query or "检修" in query):
        if template_id == "power_flow_operation_adjustment_check":
            bonus += 0.24
        if template_id == "power_flow_auto_calculation":
            bonus -= 0.04
    return bonus


def _template_text(template: dict) -> str:
    keywords = " ".join(template.get("keywords", []))
    slots = " ".join(template.get("slots", {}).keys())
    steps = " ".join(step["step"] for step in template.get("workflow", []))
    return f"{template['name']}\n{template['description']}\n{keywords}\n{slots}\n{steps}"


def _load_bge_model():
    from FlagEmbedding import BGEM3FlagModel

    return BGEM3FlagModel(str(BGE_M3_MODEL_DIR), use_fp16=False, devices="cpu")


def _normalize(matrix: np.ndarray) -> np.ndarray:
    denom = np.linalg.norm(matrix, axis=1, keepdims=True)
    denom[denom == 0] = 1.0
    return matrix / denom


def _ensure_template_index() -> None:
    if not TEMPLATE_META_JSON.exists() or not TEMPLATE_DENSE_NPY.exists():
        build_template_index()
