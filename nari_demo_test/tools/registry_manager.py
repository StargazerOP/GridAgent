from __future__ import annotations

import json
import shutil
import time
from pathlib import Path

from config import BASE_DIR, DATA_DIR, GRAPH_DIR, WORKSPACE_DIR
from tools.asset_registry import asset_summary
from tools.common import load_json
from tools.source_scanner import scan_source_files

CUSTOM_REGISTRY_JSON = DATA_DIR / "custom_registry.json"
SOURCE_LIBRARY_DIR = WORKSPACE_DIR / "调规知识问答"
INBOX_DIR = SOURCE_LIBRARY_DIR / "新增资料"


def load_custom_registry() -> dict:
    if not CUSTOM_REGISTRY_JSON.exists():
        return {"items": []}
    return json.loads(CUSTOM_REGISTRY_JSON.read_text(encoding="utf-8"))


def register_item(payload: dict, copy_file: bool = False) -> dict:
    kind = payload.get("kind", "").strip()
    if kind not in {"document", "skill", "workflow", "mechanism", "data_asset", "data_driven_model"}:
        raise ValueError("kind 必须是 document/skill/workflow/mechanism/data_asset/data_driven_model 之一")
    name = payload.get("name", "").strip()
    if not name:
        raise ValueError("name 不能为空")

    item = {
        "id": payload.get("id") or _make_id(kind, name),
        "kind": kind,
        "name": name,
        "description": payload.get("description", ""),
        "source_path": payload.get("source_path", ""),
        "target_path": "",
        "status": "registered",
        "created_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "next_steps": _next_steps(kind),
        "notes": payload.get("notes", ""),
    }
    if kind == "document" and payload.get("source_path"):
        source = Path(payload["source_path"])
        if copy_file and source.exists():
            INBOX_DIR.mkdir(parents=True, exist_ok=True)
            target = INBOX_DIR / source.name
            if source.resolve() != target.resolve():
                shutil.copy2(source, target)
            item["target_path"] = str(target)
        else:
            item["status"] = "registered_reference_only"

    registry = load_custom_registry()
    registry["items"] = [old for old in registry.get("items", []) if old.get("id") != item["id"]]
    registry["items"].append(item)
    CUSTOM_REGISTRY_JSON.write_text(json.dumps(registry, ensure_ascii=False, indent=2), encoding="utf-8")
    return item


def registry_overview() -> dict:
    registry = load_custom_registry()
    counts: dict[str, int] = {}
    for item in registry.get("items", []):
        counts[item.get("kind", "unknown")] = counts.get(item.get("kind", "unknown"), 0) + 1
    catalog = managed_asset_catalog()
    return {
        "registry_path": str(CUSTOM_REGISTRY_JSON),
        "counts": counts,
        "items": registry.get("items", []),
        "catalog": catalog,
        "catalog_counts": {key: len(value) for key, value in catalog.items() if isinstance(value, list)},
        "workflow_count": len(load_json(DATA_DIR / "workflow_templates.json")),
        "node_count": len(load_json(GRAPH_DIR / "nodes.json")),
        "edge_count": len(load_json(GRAPH_DIR / "edges.json")),
        "project_root": str(BASE_DIR),
    }


def managed_asset_catalog() -> dict:
    templates = load_json(DATA_DIR / "workflow_templates.json")
    nodes = load_json(GRAPH_DIR / "nodes.json")
    source_files = scan_source_files()
    skills = _scan_skills()
    data_summary = asset_summary().get("summary", {})
    return {
        "workflows": [
            {
                "id": item["template_id"],
                "name": item["name"],
                "kind": "workflow",
                "description": item.get("description", ""),
                "status": "active_template",
                "path": "data/workflow_templates.json",
                "meta": {
                    "scene": item.get("scene", ""),
                    "steps": len(item.get("workflow", [])),
                    "keywords": item.get("keywords", []),
                },
            }
            for item in templates
        ],
        "skills": skills,
        "mechanisms": [
            _node_asset(item, "mechanism")
            for item in nodes
            if item.get("tool_subtype") in {"mechanism_tool_group", "mechanism_operator_group", "mechanism_operator", "mechanism_model_tool"}
        ],
        "data_driven_models": [
            _node_asset(item, "data_driven_model")
            for item in nodes
            if item.get("tool_subtype") in {"data_driven_model_tool_group", "data_driven_model_tool"}
        ],
        "documents": [
            {
                "id": f"document_{idx}",
                "name": item["name"],
                "kind": "document",
                "description": item.get("preview", "") or item.get("source_type", ""),
                "status": "scanned",
                "path": item["path"],
                "meta": {
                    "source_type": item.get("source_type", ""),
                    "suffix": item.get("suffix", ""),
                    "size": item.get("size", 0),
                },
            }
            for idx, item in enumerate(source_files, start=1)
        ],
        "data_assets_count": data_summary.get("data_tables", 0),
    }


def addition_guide() -> dict:
    return {
        "document": [
            "把 Word/PDF/TXT/CSV/JSON 等资料放入 调规知识问答/新增资料，或用 register-item 登记外部路径。",
            "运行 scan-source 检查资料是否能被扫描。",
            "需要语义召回时运行 rag-build 重建 BGE-M3 索引。",
            "需要沉淀图谱候选时运行 build-graph，人工审核后合并正式 graph。",
        ],
        "workflow": [
            "在 data/workflow_templates.json 增加 template_id、scene、name、description、keywords、slots、workflow。",
            "运行 scripts/rebuild_graph_taxonomy.js 让 9+ 工作流同步为图谱流程节点。",
            "运行 template-build 重建工作流模板向量索引。",
            "在网页流程检索中验证命中与画布展示。",
        ],
        "skill": [
            "在 skills/<skill_name>/ 新增 skill.py、prompt.md、demos.json。",
            "在 main.py 或后续 API 层登记该 skill 的执行入口。",
            "把 skill 需要调用的工具写入 graph/nodes.json/edges.json 或 rebuild 脚本。",
        ],
        "mechanism": [
            "在图谱中登记为 机理工具 -> 机理算子大类/机理算子/机理模型工具。",
            "说明输入、输出、适用边界和可替换真实接口。",
            "把相关工作流通过 orchestrates 边连接到该工具。",
        ],
        "data_asset": [
            "优先更新 nari_mechanism_data_knowledge_tree.txt。",
            "运行 asset-build，从树文件抽取数据资产表目录。",
            "当前 demo 只注册可调用数据资产和表名，不要求维护真实表头。",
        ],
        "data_driven_model": [
            "登记为 数据驱动模型工具，不放入真实数据资产。",
            "说明模型输入、输出、训练/推理数据来源和适用边界。",
            "把依赖的数据资产通过 depends_on 边连接。",
        ],
    }


def _make_id(kind: str, name: str) -> str:
    import re

    slug = re.sub(r"\W+", "_", name).strip("_").lower()[:48] or str(int(time.time()))
    return f"custom_{kind}_{slug}"


def _next_steps(kind: str) -> list[str]:
    return addition_guide().get(kind, [])


def _scan_skills() -> list[dict]:
    skills_dir = BASE_DIR / "skills"
    result = []
    if not skills_dir.exists():
        return result
    for path in skills_dir.iterdir():
        if not path.is_dir() or path.name.startswith("__"):
            continue
        prompt = path / "prompt.md"
        skill_py = path / "skill.py"
        demos = path / "demos.json"
        description = ""
        if prompt.exists():
            description = prompt.read_text(encoding="utf-8", errors="ignore")[:240]
        result.append({
            "id": f"skill_{path.name}",
            "name": path.name,
            "kind": "skill",
            "description": description,
            "status": "active_skill" if skill_py.exists() else "incomplete",
            "path": str(path),
            "meta": {
                "has_skill_py": skill_py.exists(),
                "has_prompt": prompt.exists(),
                "has_demos": demos.exists(),
            },
        })
    return result


def _node_asset(item: dict, kind: str) -> dict:
    return {
        "id": item["id"],
        "name": item["name"],
        "kind": kind,
        "description": item.get("description", ""),
        "status": "registered_in_graph",
        "path": "graph/nodes.json",
        "meta": {
            "role": item.get("role", ""),
            "tool_subtype": item.get("tool_subtype", ""),
            "inputs": item.get("inputs", []),
            "outputs": item.get("outputs", []),
        },
    }
