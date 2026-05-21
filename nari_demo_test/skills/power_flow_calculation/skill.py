from __future__ import annotations

from skills.operation_check.skill import run as run_operation_check


def run(case: dict, use_llm: bool = True) -> dict:
    """组织电网潮流计算场景的演示流程。

    当前复用操作校核链路中的图谱查询、拓扑分析、模拟潮流校核和报告生成能力，
    并在返回结果中标注潮流计算场景的专用流程。后续接入真实潮流计算引擎时，
    可以把这里替换为对潮流算子或在线安全校核服务的实际调用。
    """
    adapted = dict(case)
    adapted["scene"] = "operation_check"
    adapted["title"] = case.get("title", "潮流自动计算流程")
    result = run_operation_check(adapted, use_llm=use_llm)
    result["skill"] = "power_flow_calculation"
    result["fusion_type"] = "流程牵引、工具支撑、知识约束的电网潮流计算场景"
    result["workflow"] = [
        "识别潮流计算区域、断面和输出目标",
        "查询设备拓扑、运行量测和断面限额",
        "调用潮流快速估计模型生成初值或风险提示",
        "调用潮流计算算子或潮流与越限校核工具",
        "检索运行规则、热稳约束和电压约束",
        "生成潮流分布、越限风险和人工复核项说明",
    ]
    return result
