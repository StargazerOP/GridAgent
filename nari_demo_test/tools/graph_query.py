from __future__ import annotations

from config import DATA_DIR, GRAPH_DIR
from tools.common import load_json


def load_entities() -> list[dict]:
    return load_json(DATA_DIR / "entities.json")


def load_graph_nodes() -> list[dict]:
    return load_json(GRAPH_DIR / "nodes.json")


def find_entities(names: list[str] | None = None, query: str = "") -> list[dict]:
    entities = load_entities()
    if not names:
        names = []
    matched = []
    for entity in entities:
        if entity["name"] in names or entity["name"] in query:
            matched.append(entity)
    return matched


def graph_summary(names: list[str], query: str = "") -> dict:
    matched = find_entities(names, query)
    return {
        "matched_entities": matched,
        "entity_count": len(matched),
        "used_tool": "graph_query"
    }

