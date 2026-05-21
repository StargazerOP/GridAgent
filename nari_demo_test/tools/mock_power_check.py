from __future__ import annotations


def check_operation_risk(query: str, entities: list[dict], topology: dict) -> dict:
    risk_points = []
    suggestions = []
    level = "中"

    if "保护" in query or "定切" in query or "过流" in query:
        risk_points.append("涉及保护投退或定值临时调整，需要明确恢复条件和持续时间。")
        suggestions.append("补充保护调整审批、操作后复归和风险告警措施。")

    if "转供" in query or "负荷" in query:
        risk_points.append("转供路径需要校核线路载流量、主变负载率和电压越限风险。")
        suggestions.append("优先恢复所用电和重要用户，普通负荷按承载能力分批恢复。")

    if "N-1" in query or "停役" in query or "检修" in query:
        level = "高"
        risk_points.append("检修方式下系统冗余降低，N-1后可能出现局部过载或供电可靠性下降。")
        suggestions.append("预留负荷控制措施，并对剩余主变、线路进行在线安全校核。")

    if not risk_points:
        risk_points.append("未识别到高风险关键词，仍需结合实时断面进行最终校核。")
        suggestions.append("接入真实潮流计算和SCADA断面后复核。")

    return {
        "risk_level": level,
        "risk_points": risk_points,
        "suggestions": suggestions,
        "mechanism_basis": ["潮流约束", "线路热稳约束", "电压安全约束", "N-1安全约束"],
        "note": "当前为演示用轻量模拟校核，可替换为真实潮流/在线安全校核接口。",
        "used_tool": "mock_power_check"
    }

