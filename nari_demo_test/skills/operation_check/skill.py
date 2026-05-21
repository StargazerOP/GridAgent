from __future__ import annotations

import json

from tools.graph_query import graph_summary
from tools.llm_client import chat_completion, strip_thinking_text
from tools.mock_power_check import check_operation_risk
from tools.regulation_retriever import retrieve_regulations
from tools.topology_analyzer import analyze_topology


def run(case: dict, use_llm: bool = True) -> dict:
    query = case["query"]
    entities = case.get("entities", [])

    graph = graph_summary(entities, query)
    topology = analyze_topology(graph["matched_entities"])
    power_check = check_operation_risk(query, graph["matched_entities"], topology)
    regulations = retrieve_regulations(query)

    evidence = {
        "case": case,
        "data_channel": {
            "graph": graph,
            "topology": topology
        },
        "mechanism_channel": {
            "power_check": power_check
        },
        "knowledge_channel": {
            "regulations": regulations
        }
    }
    report = _fallback_report(evidence)
    llm_result = {"ok": False, "content": "", "skipped": not use_llm}
    if use_llm:
        llm_result = chat_completion([
            {
                "role": "system",
                "content": "你是电网操作校核智能体，请直接输出中文校核报告，不要输出思考过程、推理草稿或英文分析。"
            },
            {
                "role": "user",
                "content": "请基于以下工具结果生成校核报告：\n" + json.dumps(evidence, ensure_ascii=False, indent=2)
            }
        ])
        if llm_result.get("ok") and llm_result.get("content"):
            report = strip_thinking_text(llm_result["content"])

    return {
        "skill": "operation_check",
        "fusion_type": "交互式融合框架下的轻量串行执行链",
        "workflow": [
            "识别操作场景",
            "查询数据图谱",
            "分析拓扑影响",
            "调用机理模拟校核",
            "检索调规/继保依据",
            "生成校核报告"
        ],
        "evidence": evidence,
        "llm_result": llm_result,
        "report": report
    }


def _fallback_report(evidence: dict) -> str:
    case = evidence["case"]
    graph = evidence["data_channel"]["graph"]
    topology = evidence["data_channel"]["topology"]
    power = evidence["mechanism_channel"]["power_check"]
    regs = evidence["knowledge_channel"]["regulations"]

    lines = [
        f"【操作校核报告】{case['title']}",
        f"问题：{case['query']}",
        "",
        "1. 数据侧结果",
        f"- 命中实体：{', '.join(e['name'] for e in graph['matched_entities']) or '未命中'}",
        f"- 关联设备/区域：{', '.join(topology['connected_entities']) or '无'}",
        f"- 相关馈线：{', '.join(topology['feeders']) or '无'}",
        f"- 重要用户：{', '.join(topology['important_users']) or '无'}",
        "",
        "2. 机理侧校核",
        f"- 风险等级：{power['risk_level']}",
        "- 风险点：" + "；".join(power["risk_points"]),
        "- 建议：" + "；".join(power["suggestions"]),
        "",
        "3. 知识侧依据",
    ]
    for reg in regs[:3]:
        lines.append(f"- {reg['title']}：{reg['content']}")
    lines.extend([
        "",
        "4. 结论",
        "当前 demo 给出的是轻量模拟校核结论，建议接入真实SCADA断面、潮流计算和在线安全校核后再形成正式操作许可。"
    ])
    return "\n".join(lines)
