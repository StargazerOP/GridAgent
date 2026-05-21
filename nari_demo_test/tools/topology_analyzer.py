from __future__ import annotations

from tools.common import unique_keep_order


def analyze_topology(entities: list[dict]) -> dict:
    connected = []
    feeders = []
    users = []
    for entity in entities:
        connected.extend(entity.get("connected_entities", []))
        feeders.extend(entity.get("feeders", []))
        users.extend(entity.get("important_users", []))

    return {
        "connected_entities": unique_keep_order(connected),
        "feeders": unique_keep_order(feeders),
        "important_users": unique_keep_order(users),
        "path_hints": _build_path_hints(entities),
        "used_tool": "topology_analyzer"
    }


def _build_path_hints(entities: list[dict]) -> list[str]:
    hints = []
    names = [e["name"] for e in entities]
    if "奥体变" in names and "富城变" in names:
        hints.append("奥体变 -- 青竹园#2线/富奥线相关通道 -- 富城变")
    if "奥体变" in names and "嘉庆变" in names:
        hints.append("奥体变 -- 润地#3线/润地#4线相关通道 -- 嘉庆变")
    if "三官殿变" in names:
        hints.append("三官殿变500kV母线 -- 2号主变 -- 通东南分区发用电平衡")
    if "东善桥2号主变" in names:
        hints.append("东善桥2号主变停役 -- 剩余主变/线路承载 -- N-1风险校核")
    return hints

