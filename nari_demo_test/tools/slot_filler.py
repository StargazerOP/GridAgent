from __future__ import annotations

import re

from tools.source_scanner import search_source_library


VOLTAGE_RE = re.compile(r"((?:35|110|220|500)\s*(?:kV|千伏))", re.IGNORECASE)
ENTITY_RE = re.compile(r"([\u4e00-\u9fa5A-Za-z0-9#_]+(?:变|主变|母线|线|风电场|光伏电站|场站))")


def fill_slots(query: str, template: dict) -> dict:
    slots = {}
    entities = _extract_entities(query)
    voltage = _extract_voltage(query)
    fault_type = _extract_fault_type(query)

    template_id = template["template_id"]
    if template_id == "fault_substation_blackout":
        slots["fault_entity"] = _prefer_entity(entities, suffix="变")
        slots["voltage_level"] = voltage
        slots["fault_type"] = fault_type or "全停"
        slots["important_users"] = []
        slots["dispatch_org"] = ""
    elif template_id == "operation_load_transfer_check":
        slots["source_entity"] = entities[0] if entities else ""
        slots["target_entities"] = entities[1:] if len(entities) > 1 else []
        slots["operation_type"] = "负荷转供" if "转供" in query else "运行方式调整"
        slots["protection_actions"] = _extract_protection_action(query)
    elif template_id == "operation_protection_check":
        slots["related_entity"] = entities[0] if entities else ""
        slots["protection_actions"] = _extract_protection_action(query)
        slots["duration_or_restore_condition"] = _extract_restore_condition(query)
    elif template_id == "operation_n_minus_one_check":
        slots["outage_entity"] = entities[0] if entities else ""
        slots["contingency_type"] = "N-2" if "N-2" in query else "N-1"
        slots["fault_entity"] = entities[1] if len(entities) > 1 else ""
    elif template_id == "fault_500kv_bus_fault":
        slots["station"] = _prefer_entity(entities, suffix="变")
        slots["outage_bus"] = _find_phrase(query, ["II段母线", "Ⅱ段母线", "I段母线", "Ⅰ段母线"])
        slots["fault_bus"] = _find_phrase(query, ["I段母线", "Ⅰ段母线", "II段母线", "Ⅱ段母线"])
        slots["affected_area"] = _find_phrase(query, ["通东南分区"])
        slots["control_units"] = [item for item in ["吕四港", "华通"] if item in query]
    elif template_id == "fault_wind_farm_blackout":
        slots["station"] = entities[0] if entities else ""
        slots["fault_type"] = fault_type or "全场停电"
        slots["grid_connection"] = _find_phrase(query, ["送出线路", "升压站"])
        slots["restore_condition"] = ""
    elif template_id == "power_flow_auto_calculation":
        slots["calculation_area"] = entities[0] if entities else ""
        slots["operation_mode"] = _find_phrase(query, ["检修方式", "事故方式", "正常方式", "调整后方式"])
        slots["target_time"] = _find_phrase(query, ["日前", "日内", "实时", "当前"])
        slots["check_items"] = _find_phrase(query, ["越限", "电压", "断面", "N-1", "N-2"]) or "潮流与越限"
    elif template_id == "power_flow_snapshot_calculation":
        slots["calculation_area"] = entities[0] if entities else ""
        slots["snapshot_time"] = _find_phrase(query, ["当前", "实时", "日内", "日前", "指定时刻"])
        slots["input_data_source"] = _find_phrase(query, ["SCADA", "PMU", "历史断面", "仿真断面"]) or "当前运行断面"
        slots["calculation_goal"] = _find_phrase(query, ["潮流分布", "节点电压", "线路负载", "收敛状态"]) or "潮流分布"
    elif template_id == "power_flow_operation_adjustment_check":
        slots["adjustment_object"] = entities[0] if entities else ""
        slots["adjustment_action"] = _find_phrase(query, ["停运", "转供", "出力调整", "检修", "恢复", "调整"])
        slots["affected_area"] = entities[1] if len(entities) > 1 else ""
        slots["check_items"] = _find_phrase(query, ["越限", "电压越限", "断面限额", "N-1", "N-2"]) or "潮流越限"
    else:
        for key in template.get("slots", {}):
            slots[key] = ""

    source_hits = search_source_library(query, top_k=3)
    return {
        "slots": slots,
        "entities": _entities_for_case(template, slots, entities),
        "source_hits": source_hits
    }


def build_case_from_template(query: str, template: dict, filled: dict) -> dict:
    entity_list = filled["entities"]
    title = _format_title(template, filled["slots"])
    return {
        "id": "routed_" + template["template_id"],
        "scene": template["scene"],
        "title": title,
        "query": query,
        "entities": entity_list,
        "routed_template": {
            "template_id": template["template_id"],
            "name": template["name"],
            "score": template.get("score"),
            "rank": template.get("rank")
        },
        "filled_slots": filled["slots"],
        "source_hits": filled["source_hits"]
    }


def _extract_entities(query: str) -> list[str]:
    result = []
    for match in ENTITY_RE.finditer(query):
        entity = match.group(1).strip("，。；;:：、（）()[]【】")
        if entity and entity not in result:
            result.append(entity)
    return result


def _extract_voltage(query: str) -> str:
    match = VOLTAGE_RE.search(query)
    if not match:
        return ""
    return match.group(1).replace(" ", "")


def _extract_fault_type(query: str) -> str:
    if "全场停电" in query:
        return "全场停电"
    if "全停" in query or "全所失电" in query:
        return "全停"
    if "母线故障" in query:
        return "母线故障"
    if "N-2" in query:
        return "N-2"
    if "N-1" in query:
        return "N-1"
    return ""


def _extract_protection_action(query: str) -> str:
    parts = []
    for key in ["保护停用", "定切保护停用", "过流时间", "定值调整", "投退"]:
        if key in query:
            parts.append(key)
    return "、".join(parts)


def _extract_restore_condition(query: str) -> str:
    if "恢复" in query:
        return "操作后需明确恢复条件"
    return ""


def _prefer_entity(entities: list[str], suffix: str) -> str:
    for entity in entities:
        if entity.endswith(suffix):
            return entity
    return entities[0] if entities else ""


def _find_phrase(query: str, phrases: list[str]) -> str:
    for phrase in phrases:
        if phrase in query:
            return phrase
    return ""


def _entities_for_case(template: dict, slots: dict, extracted: list[str]) -> list[str]:
    keys = [
        "fault_entity", "source_entity", "related_entity", "outage_entity",
        "fault_entity", "station", "calculation_area", "adjustment_object",
        "affected_area"
    ]
    result = []
    for key in keys:
        value = slots.get(key)
        if isinstance(value, str) and value and value not in result:
            result.append(value)
    for key in ["target_entities", "control_units"]:
        value = slots.get(key)
        if isinstance(value, list):
            for item in value:
                if item and item not in result:
                    result.append(item)
    for entity in extracted:
        if entity not in result:
            result.append(entity)
    return result


def _format_title(template: dict, slots: dict) -> str:
    entity = (
        slots.get("fault_entity")
        or slots.get("source_entity")
        or slots.get("related_entity")
        or slots.get("outage_entity")
        or slots.get("station")
        or slots.get("calculation_area")
        or slots.get("adjustment_object")
        or "变体任务"
    )
    return f"{entity}-{template['name']}"
