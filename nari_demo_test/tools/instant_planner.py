from __future__ import annotations

import json
import re
from typing import Any

from config import GRAPH_DIR
from tools.asset_registry import search_assets
from tools.common import load_json
from tools.llm_client import chat_completion, strip_thinking_text


GROUP_ROLES = {"tool_group", "operator_group"}
CALLABLE_TOOL_ROLES = {"operator", "model_tool", "engineering_tool"}
PREFERRED_LEAF_ROLES = CALLABLE_TOOL_ROLES | {"data_asset", "entity", "rule", "case", "experience"}
DOMAIN_KEYWORDS = [
    "牛顿拉夫逊", "稀疏矩阵", "最优潮流", "连续潮流", "潮流",
    "偏微分方程", "稳定性判别", "稳定裕度", "稳定",
    "外部影响因素", "周期识别", "趋势预测", "随机波动", "时间序列",
    "拓扑相关性", "聚类", "空间特征", "不确定性抽样", "时空相关性",
    "故障场景", "整数优化", "随机优化", "鲁棒优化", "机组组合",
    "台风", "气象", "天气", "新能源", "断面", "送出线路", "线路", "风险",
]


def retrieve_planning_context(query: str, top_k: int = 10) -> dict:
    nodes = load_json(GRAPH_DIR / "nodes.json")
    edges = load_json(GRAPH_DIR / "edges.json")
    candidates = _score_nodes(query, nodes, top_k=top_k)
    candidate_ids = {item["id"] for item in candidates}
    related_edges = [
        edge for edge in edges
        if edge["source"] in candidate_ids or edge["target"] in candidate_ids
    ][:30]
    related_ids = set(candidate_ids)
    for edge in related_edges:
        related_ids.add(edge["source"])
        related_ids.add(edge["target"])
    related_nodes = [node for node in nodes if node["id"] in related_ids]
    available_tools = [
        node for node in nodes
        if node.get("category") == "tool_call"
        and node.get("role") in CALLABLE_TOOL_ROLES
    ]
    assets = search_assets(query, top_k=8)
    return {
        "query": query,
        "candidate_nodes": candidates,
        "candidate_assets": [_asset_to_candidate_node(item) for item in assets[:5]],
        "related_nodes": related_nodes,
        "related_edges": related_edges,
        "available_tools": available_tools,
        "data_assets": assets,
    }


def plan_instant_workflow(query: str, use_llm: bool = True) -> dict:
    context = retrieve_planning_context(query)
    fallback = _fallback_plan(query, context)
    llm_result = {"ok": False, "content": "", "skipped": not use_llm}
    plan = fallback
    if use_llm:
        llm_result = chat_completion([
            {
                "role": "system",
                "content": (
                    "你是电网调度智能体的即时规划器。用户任务没有命中固化工作流模板时，"
                    "你需要基于候选知识、可用工具和数据资产生成临时流程。"
                    "只能使用给定工具，不要编造已执行结果。输出严格JSON，不要输出思考过程。"
                ),
            },
            {
                "role": "user",
                "content": _planner_prompt(query, context),
            },
        ], max_tokens=1800)
        if llm_result.get("ok") and llm_result.get("content"):
            parsed = _parse_json_plan(strip_thinking_text(llm_result["content"]))
            if parsed:
                plan = parsed
    return {
        "query": query,
        "mode": "instant_planning",
        "context": context,
        "llm_result": llm_result,
        "plan": plan,
    }


def _score_nodes(query: str, nodes: list[dict], top_k: int) -> list[dict]:
    terms = [part for part in re.split(r"[\s，。；、,.;:：?？/]+", query) if len(part) >= 2]
    scored = []
    for node in nodes:
        if node.get("role") in GROUP_ROLES or node.get("category") == "skill_process":
            continue
        text = " ".join([
            node.get("name", ""),
            node.get("description", ""),
            node.get("category", ""),
            node.get("role", ""),
            node.get("tool_subtype", ""),
            " ".join(node.get("keywords", [])),
            " ".join(node.get("examples", [])),
        ]).lower()
        score = 0
        for term in terms:
            if term.lower() in text:
                score += 3
        for keyword in DOMAIN_KEYWORDS:
            if keyword in query and keyword.lower() in text:
                score += 4
        if query.lower() in text:
            score += 8
        if "潮流" in query and ("潮流" in text or "power_flow" in node.get("id", "")):
            score += 4
        if any(word in query for word in ["台风", "气象", "天气"]) and (
            "气象" in text or "天气" in text or "weather" in node.get("id", "")
        ):
            score += 5
        if "新能源" in query and ("新能源" in text or "出力" in text or "weather_power" in node.get("id", "")):
            score += 5
        if any(word in query for word in ["断面", "送出线路", "线路"]) and (
            "断面" in text or "线路" in text or "拓扑" in text or "stability" in node.get("id", "")
        ):
            score += 4
        if "数据" in query and (node.get("role") == "data_asset" or "数据" in text):
            score += 4
        if "风险" in query and ("风险" in text or "稳定" in text or "安全" in text):
            score += 3
        if node.get("role") in CALLABLE_TOOL_ROLES:
            score += 0.5
        if node.get("operator_level") == "leaf":
            score += 1.2
        elif node.get("operator_level") == "family":
            score += 0.2
        if node.get("role") in PREFERRED_LEAF_ROLES:
            score += 0.3
        if score:
            item = dict(node)
            item["match_score"] = score
            scored.append(item)
    scored.sort(key=lambda item: item["match_score"], reverse=True)
    return scored[:top_k]


def _asset_to_candidate_node(asset: dict) -> dict:
    path = " / ".join(asset.get("path", []))
    description = asset.get("description") or path or "已登记到统一数据源查询工具的数据资产。"
    return {
        "id": asset.get("id", ""),
        "name": asset.get("name", ""),
        "category": "knowledge_entity",
        "role": "data_asset",
        "description": description,
        "match_score": asset.get("score", 0),
        "table_code": asset.get("table_code", ""),
        "data_category": asset.get("data_category", ""),
        "sub_category": asset.get("sub_category", ""),
    }


def _planner_prompt(query: str, context: dict) -> str:
    tool_lines = [
        {
            "id": tool["id"],
            "name": tool["name"],
            "subtype": tool.get("tool_subtype", ""),
            "description": tool.get("description", ""),
        }
        for tool in context["available_tools"]
    ]
    knowledge_lines = [
        {
            "id": node["id"],
            "name": node["name"],
            "type": node.get("category"),
            "role": node.get("role"),
            "description": node.get("description", ""),
        }
        for node in context["candidate_nodes"]
    ]
    asset_lines = [
        {
            "id": item["id"],
            "name": item["name"],
            "table_code": item.get("table_code", ""),
            "category": item.get("data_category", ""),
            "description": item.get("description", ""),
        }
        for item in context["data_assets"]
    ]
    return (
        f"用户任务：{query}\n\n"
        f"候选知识节点：\n{json.dumps(knowledge_lines, ensure_ascii=False, indent=2)}\n\n"
        f"可用工具：\n{json.dumps(tool_lines, ensure_ascii=False, indent=2)}\n\n"
        f"可查询数据资产：\n{json.dumps(asset_lines, ensure_ascii=False, indent=2)}\n\n"
        "请输出如下JSON结构：\n"
        "{\n"
        '  "title": "临时流程名称",\n'
        '  "summary": "为什么需要即时规划",\n'
        '  "steps": [\n'
        '    {"step": "步骤名称", "tool": "必须来自可用工具id", "input": "输入", "expected_output": "预期输出", "evidence": ["依据节点或数据资产id"]}\n'
        "  ],\n"
        '  "knowledge_used": ["候选知识节点id"],\n'
        '  "data_assets_used": ["数据资产id"],\n'
        '  "human_review": ["需要人工复核的事项"],\n'
        '  "template_status": "instant_not_persisted"\n'
        "}"
    )


def _fallback_plan(query: str, context: dict) -> dict:
    def first_tool(*ids: str) -> str:
        available = {tool["id"] for tool in context["available_tools"]}
        for tool_id in ids:
            if tool_id in available:
                return tool_id
        return "tool_llm"

    candidates = context["candidate_nodes"][:5]
    assets = context["data_assets"][:5]
    steps = [
        {
            "step": "解析任务对象和目标",
            "tool": first_tool("tool_slot_filler"),
            "input": query,
            "expected_output": "识别设备、场景、约束和输出目标",
            "evidence": [item["id"] for item in candidates[:2]],
        },
        {
            "step": "召回相关知识和数据资产",
            "tool": first_tool("tool_source_scanner", "tool_graph_query"),
            "input": "候选知识节点与数据资产目录",
            "expected_output": "形成可用于规划的知识依据和数据来源清单",
            "evidence": [item["id"] for item in candidates[:3]] + [item["id"] for item in assets[:3]],
        },
        {
            "step": "选择可调用工具并编排临时流程",
            "tool": first_tool("tool_graph_query", "tool_topology_analyzer"),
            "input": "任务目标、候选知识、可用工具清单",
            "expected_output": "生成临时工具调用顺序和校核逻辑",
            "evidence": [item["id"] for item in candidates[:4]],
        },
        {
            "step": "生成即时规划说明和人工复核项",
            "tool": first_tool("tool_llm"),
            "input": "临时流程草案、知识依据、数据资产",
            "expected_output": "输出即时规划结果，标注未固化为模板",
            "evidence": [item["id"] for item in candidates[:5]],
        },
    ]
    return {
        "title": "未命中模板的即时规划流程",
        "summary": "当前任务没有命中固化工作流模板，系统基于候选知识、可用工具和数据资产生成临时规划，需人工确认后使用。",
        "steps": steps,
        "knowledge_used": [item["id"] for item in candidates],
        "data_assets_used": [item["id"] for item in assets],
        "human_review": [
            "确认临时流程是否符合调度规程和现场业务边界。",
            "确认涉及的工具接口和数据资产是否已经接入真实系统。",
            "正式执行前需要人工复核风险结论和操作建议。",
        ],
        "template_status": "instant_not_persisted",
    }


def _parse_json_plan(text: str) -> dict[str, Any] | None:
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?", "", text).strip()
        text = re.sub(r"```$", "", text).strip()
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        text = text[start:end + 1]
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(payload, dict) or not isinstance(payload.get("steps"), list):
        return None
    payload.setdefault("template_status", "instant_not_persisted")
    payload.setdefault("human_review", ["即时规划结果未固化为模板，需要人工确认。"])
    return payload
