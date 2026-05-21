from __future__ import annotations

import json
import re
import time
from pathlib import Path

from config import GRAPH_DIR, OUTPUT_DIR
from tools.common import load_json, safe_read_text
from tools.source_scanner import scan_source_files


ENTITY_PATTERNS = [
    (re.compile(r"([\u4e00-\u9fa5A-Za-z0-9#_]+变)"), "knowledge_entity", "entity"),
    (re.compile(r"([\u4e00-\u9fa5A-Za-z0-9#_]+线[0-9A-Za-z#]*)"), "knowledge_entity", "entity"),
    (re.compile(r"([0-9]+kV[\u4e00-\u9fa5A-Za-z0-9#_]*母线)"), "knowledge_entity", "entity"),
    (re.compile(r"(N-[0-9]+)"), "knowledge_entity", "rule"),
    (re.compile(r"(潮流|暂态稳定|电压|频率|断面|发用电平衡)"), "knowledge_entity", "rule"),
    (re.compile(r"(调度规程|继电保护|安全自动装置|事故预案|专家经验|稳定判据)"), "knowledge_entity", "document"),
    (re.compile(r"(操作校核流程|故障处置预案生成流程|潮流自动计算流程|安全校核流程|异常结果解释流程)"), "skill_process", "workflow"),
    (re.compile(r"(潮流计算|稳定分析|优化决策|安全校核|气象预测|新能源出力预测|状态预测|风险识别|向量检索|图谱查询)"), "tool_call", "model_tool")
]


def build_incremental_graph(max_files: int = 80) -> dict:
    existing_nodes = load_json(GRAPH_DIR / "nodes.json")
    existing_names = {node["name"] for node in existing_nodes}
    candidate_nodes = []
    candidate_edges = []

    for record in scan_source_files()[:max_files]:
        path = Path(record["path"])
        doc_node = _document_node(record)
        if doc_node["name"] not in existing_names:
            candidate_nodes.append(doc_node)
            existing_names.add(doc_node["name"])

        text = path.name + "\n" + safe_read_text(path, max_chars=1200)
        for name, category, role in _extract_candidates(text):
            if name not in existing_names:
                node = {
                    "id": _node_id(category, role, name),
                    "name": name,
                    "category": category,
                    "role": role,
                    "description": f"从资料 {path.name} 中抽取的候选节点，需人工确认。"
                }
                candidate_nodes.append(node)
                existing_names.add(name)
            candidate_edges.append({
                "source": doc_node["id"],
                "target": _node_id(category, role, name),
                "type": "mentions"
            })

    artifact = {
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "node_count": len(candidate_nodes),
        "edge_count": len(candidate_edges),
        "nodes": candidate_nodes,
        "edges": candidate_edges
    }
    output_path = OUTPUT_DIR / "incremental_graph_candidates.json"
    output_path.write_text(json.dumps(artifact, ensure_ascii=False, indent=2), encoding="utf-8")
    artifact["output_path"] = str(output_path)
    return artifact


def _document_node(record: dict) -> dict:
    source_type = record["source_type"]
    if source_type in {"plan", "regulation", "reference"}:
        category = "knowledge_entity"
    elif source_type == "structured_data":
        category = "knowledge_entity"
    else:
        category = "knowledge_entity"
    return {
        "id": _node_id(category, "document", record["name"]),
        "name": record["name"],
        "category": category,
        "role": "document",
        "description": f"原始资料库文件：{record['path']}"
    }


def _extract_candidates(text: str) -> list[tuple[str, str, str]]:
    candidates = []
    seen = set()
    for pattern, category, role in ENTITY_PATTERNS:
        for match in pattern.finditer(text):
            name = match.group(1).strip(" ，。；;:：、（）()[]【】")
            if len(name) < 2 or len(name) > 40:
                continue
            key = (name, category, role)
            if key not in seen:
                candidates.append(key)
                seen.add(key)
    return candidates


def _node_id(category: str, role: str, name: str) -> str:
    safe = re.sub(r"[^0-9A-Za-z\u4e00-\u9fa5]+", "_", name).strip("_")
    return f"{category}_{role}_{safe}"
