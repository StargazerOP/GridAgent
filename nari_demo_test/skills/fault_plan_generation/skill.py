from __future__ import annotations

import json

from tools.graph_query import graph_summary
from tools.llm_client import chat_completion, strip_thinking_text
from tools.mock_stability_check import assess_fault_risk
from tools.plan_retriever import retrieve_plans
from tools.regulation_retriever import retrieve_regulations
from tools.topology_analyzer import analyze_topology


def run(case: dict, use_llm: bool = True) -> dict:
    query = case["query"]
    entities = case.get("entities", [])

    plans = retrieve_plans(query)
    primary_plan = plans[0] if plans else None
    graph = graph_summary(entities, query)
    topology = analyze_topology(graph["matched_entities"])
    stability = assess_fault_risk(query, graph["matched_entities"], primary_plan)
    regulations = retrieve_regulations(query)

    evidence = {
        "case": case,
        "knowledge_channel": {
            "plans": plans,
            "regulations": regulations
        },
        "data_channel": {
            "graph": graph,
            "topology": topology
        },
        "mechanism_channel": {
            "stability": stability
        }
    }
    report = _fallback_report(evidence)
    llm_result = {"ok": False, "content": "", "skipped": not use_llm}
    if use_llm:
        llm_result = chat_completion([
            {
                "role": "system",
                "content": "你是电网故障处置预案生成智能体，请直接输出中文处置预案，不要输出思考过程、推理草稿或英文分析。"
            },
            {
                "role": "user",
                "content": "请基于以下工具结果生成故障处置预案：\n" + json.dumps(evidence, ensure_ascii=False, indent=2)
            }
        ])
        if llm_result.get("ok") and llm_result.get("content"):
            report = strip_thinking_text(llm_result["content"])

    return {
        "skill": "fault_plan_generation",
        "fusion_type": "交互式融合框架下的轻量串行执行链",
        "workflow": [
            "识别故障场景",
            "召回相似预案",
            "查询设备图谱",
            "分析影响范围",
            "调用机理风险评估",
            "检索调规依据",
            "生成处置预案"
        ],
        "evidence": evidence,
        "llm_result": llm_result,
        "report": report
    }


def _fallback_report(evidence: dict) -> str:
    case = evidence["case"]
    plans = evidence["knowledge_channel"]["plans"]
    graph = evidence["data_channel"]["graph"]
    topology = evidence["data_channel"]["topology"]
    stability = evidence["mechanism_channel"]["stability"]
    regs = evidence["knowledge_channel"]["regulations"]
    plan = plans[0] if plans else {"title": "未召回预案", "impact": [], "steps": []}

    lines = [
        f"【故障处置预案】{case['title']}",
        f"问题：{case['query']}",
        "",
        "1. 召回预案",
        f"- 首选预案：{plan.get('title', '未召回')}",
        f"- 来源：{plan.get('source_file', '无')}",
        "",
        "2. 影响范围",
    ]
    for item in plan.get("impact", []):
        lines.append(f"- {item}")
    lines.extend([
        f"- 命中实体：{', '.join(e['name'] for e in graph['matched_entities']) or '未命中'}",
        f"- 关联对象：{', '.join(topology['connected_entities']) or '无'}",
        f"- 重要用户：{', '.join(topology['important_users']) or '无'}",
        "",
        "3. 机理风险",
        f"- 风险等级：{stability['risk_level']}",
        "- 风险点：" + "；".join(stability["risk_points"]),
        "- 控制建议：" + "；".join(stability["controls"]),
        "",
        "4. 处置步骤"
    ])
    for idx, step in enumerate(plan.get("steps", []), start=1):
        lines.append(f"{idx}. {step}")
    lines.extend([
        "",
        "5. 依据",
    ])
    for reg in regs[:3]:
        lines.append(f"- {reg['title']}：{reg['content']}")
    lines.extend([
        "",
        "6. 人工确认项",
        "- 核对实时断面、保护动作、开关位置和调度指令。",
        "- 模拟风险评估需替换为真实暂稳评估、潮流计算或在线安全校核后用于正式决策。"
    ])
    return "\n".join(lines)
