from __future__ import annotations


def assess_fault_risk(query: str, entities: list[dict], plan: dict | None = None) -> dict:
    level = "中"
    risk_points = []
    controls = []

    if "500kV" in query or "母线" in query or "三官殿" in query:
        level = "高"
        risk_points.extend([
            "高压主网母线故障可能造成主变失去和关键断面潮流重分布。",
            "需要关注分区发用电平衡、断面越限和频率稳定风险。"
        ])
        controls.extend(["调整吕四港、华通等相关机组出力。", "控制通东南分区关键断面不越限。"])
    elif "全停" in query or "失电" in query:
        level = "较高"
        risk_points.extend([
            "全停事故会影响所用电和重要用户供电。",
            "恢复过程需要防止转供路径过载和保护配合风险。"
        ])
        controls.extend(["先恢复所用电，再恢复重要用户。", "转供前校核线路和主变承载能力。"])
    elif "风电场" in query or "新能源" in query:
        level = "中"
        risk_points.extend([
            "新能源场站全停会导致出力损失和局部无功支撑下降。",
            "恢复并网需关注送出线路、升压站和调度指令。"
        ])
        controls.extend(["核对保护动作和送出线路状态。", "按调度指令分步恢复机组并网。"])
    else:
        risk_points.append("需结合实时断面、故障录波和仿真校核进一步判断。")
        controls.append("接入暂稳评估和断面安全校核模型后复核。")

    return {
        "risk_level": level,
        "risk_points": risk_points,
        "controls": controls,
        "mechanism_basis": ["暂态稳定约束", "发用电平衡约束", "断面越限约束", "频率安全约束"],
        "note": "当前为演示用轻量模拟评估，可替换为真实暂稳评估/在线安全校核模型。",
        "used_tool": "mock_stability_check"
    }

