from __future__ import annotations

import json
import re
import time
from pathlib import Path

from config import BASE_DIR, OUTPUT_DIR, WORKSPACE_DIR

DEFAULT_TREE_PATH = WORKSPACE_DIR / "结题" / "nari_mechanism_data_knowledge_tree.txt"
ASSET_REGISTRY_JSON = OUTPUT_DIR / "asset_registry.json"


def build_asset_registry(tree_path: Path | None = None) -> dict:
    tree_path = tree_path or DEFAULT_TREE_PATH
    if not tree_path.exists():
        raise FileNotFoundError(f"未找到知识库树文件：{tree_path}")

    lines = tree_path.read_text(encoding="utf-8", errors="ignore").splitlines()
    stack: list[str] = []
    records: list[dict] = []
    counters = {"top_categories": 0, "data_categories": 0, "data_tables": 0, "leaf_items": 0}

    for line in lines[1:]:
        parsed = _parse_tree_line(line)
        if not parsed:
            continue
        depth, raw_name, description = parsed
        while len(stack) >= depth:
            stack.pop()
        stack.append(raw_name)

        if depth == 1:
            counters["top_categories"] += 1
        if stack and stack[0] == "数据" and depth == 2:
            counters["data_categories"] += 1
        if stack and stack[0] == "数据" and description:
            records.append(_record_from_path(stack, description))
        if description:
            counters["leaf_items"] += 1

    counters["data_tables"] = len(records)
    payload = {
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "tree_path": str(tree_path),
        "summary": counters,
        "assets": records,
    }
    ASSET_REGISTRY_JSON.parent.mkdir(parents=True, exist_ok=True)
    ASSET_REGISTRY_JSON.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return payload


def load_asset_registry(auto_build: bool = True) -> dict:
    if not ASSET_REGISTRY_JSON.exists():
        if not auto_build:
            return {"summary": {}, "assets": []}
        return build_asset_registry()
    return json.loads(ASSET_REGISTRY_JSON.read_text(encoding="utf-8"))


def search_assets(query: str = "", top_k: int = 80) -> list[dict]:
    registry = load_asset_registry(auto_build=True)
    assets = registry.get("assets", [])
    if not query:
        return assets[:top_k]
    terms = [part for part in re.split(r"[\s，。；、,.;:：?？/]+", query) if len(part) >= 2]
    scored = []
    for item in assets:
        haystack = " ".join([
            item.get("name", ""),
            item.get("table_code", ""),
            item.get("description", ""),
            " ".join(item.get("path", [])),
        ]).lower()
        score = 0
        for term in terms:
            if term.lower() in haystack:
                score += 3
        if query.lower() in haystack:
            score += 8
        if score:
            enriched = dict(item)
            enriched["match_score"] = score
            scored.append(enriched)
    scored.sort(key=lambda item: item["match_score"], reverse=True)
    return scored[:top_k]


def asset_summary() -> dict:
    registry = load_asset_registry(auto_build=True)
    assets = registry.get("assets", [])
    by_category: dict[str, int] = {}
    for item in assets:
        category = item.get("data_category") or "未分类"
        by_category[category] = by_category.get(category, 0) + 1
    return {
        "registry_path": str(ASSET_REGISTRY_JSON),
        "tree_path": registry.get("tree_path"),
        "generated_at": registry.get("generated_at"),
        "summary": registry.get("summary", {}),
        "by_category": by_category,
    }


def _parse_tree_line(line: str) -> tuple[int, str, str] | None:
    if not line.strip():
        return None
    marker = max(line.rfind("|--"), line.rfind("`--"))
    if marker < 0:
        return None
    depth = marker // 4 + 1
    content = line[marker + 3:].strip()
    if not content:
        return None
    if ":" in content:
        name, description = content.split(":", 1)
        return depth, name.strip(), description.strip()
    return depth, content, ""


def _record_from_path(path_items: list[str], description: str) -> dict:
    leaf = path_items[-1]
    table_code = ""
    match = re.search(r"\(([A-Za-z0-9_]+)\)", leaf)
    if match:
        table_code = match.group(1)
        name = leaf[:match.start()].strip()
    else:
        name = leaf
    stable = table_code or re.sub(r"\W+", "_", name).strip("_").lower()
    return {
        "id": f"asset_{stable}",
        "name": name,
        "table_code": table_code,
        "description": description,
        "top_category": path_items[0] if path_items else "",
        "data_category": path_items[1] if len(path_items) > 1 else "",
        "sub_category": path_items[2] if len(path_items) > 2 else "",
        "path": path_items[:],
        "callable_via": "tool_source_scanner",
        "header_registered": False,
        "status": "registered_for_demo",
    }


def project_asset_overview() -> dict:
    source_root = WORKSPACE_DIR / "调规知识问答"
    source_files = []
    if source_root.exists():
        for path in source_root.rglob("*"):
            if path.is_file():
                source_files.append(path)
    return {
        "project_root": str(BASE_DIR),
        "source_root": str(source_root),
        "source_file_count": len(source_files),
        "asset_registry": asset_summary(),
    }
