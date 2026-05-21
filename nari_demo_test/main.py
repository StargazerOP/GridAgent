from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from config import DATA_DIR, OUTPUT_DIR
from skills.fault_plan_generation.skill import run as run_fault_plan
from skills.operation_check.skill import run as run_operation_check
from skills.power_flow_calculation.skill import run as run_power_flow_calculation
from tools.common import load_json
from tools.graph_builder import build_incremental_graph
from tools.source_scanner import scan_source_files, search_source_library


def load_cases() -> list[dict]:
    return load_json(DATA_DIR / "demo_cases.json")


def list_cases() -> None:
    cases = load_cases()
    print("可用 demo：")
    for idx, case in enumerate(cases, start=1):
        print(f"{idx}. [{case['scene']}] {case['id']} - {case['title']}")


def run_case(case: dict, use_llm: bool = True) -> dict:
    if case["scene"] == "operation_check":
        result = run_operation_check(case, use_llm=use_llm)
    elif case["scene"] == "power_flow_calculation":
        result = run_power_flow_calculation(case, use_llm=use_llm)
    elif case["scene"] == "fault_plan_generation":
        result = run_fault_plan(case, use_llm=use_llm)
    else:
        raise ValueError(f"Unknown scene: {case['scene']}")
    return result


def save_result(case_id: str, result: dict) -> Path:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    path = OUTPUT_DIR / "run_logs" / f"{case_id}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(result, ensure_ascii=False, indent=2)
    try:
        path.write_text(payload, encoding="utf-8")
    except PermissionError:
        stamp = time.strftime("%Y%m%d_%H%M%S")
        path = OUTPUT_DIR / "run_logs" / f"{case_id}_{stamp}.json"
        path.write_text(payload, encoding="utf-8")
    return path


def cmd_run(args: argparse.Namespace) -> None:
    cases = load_cases()
    matched = [case for case in cases if case["id"] == args.case_id]
    if not matched:
        raise SystemExit(f"未找到 demo: {args.case_id}")
    result = run_case(matched[0], use_llm=not args.no_llm)
    path = save_result(matched[0]["id"], result)
    print_step_trace(result)
    print("\n" + result["report"])
    print(f"\n运行日志已保存：{path}")


def cmd_run_all(args: argparse.Namespace) -> None:
    for case in load_cases():
        print("=" * 80)
        print(f"运行：{case['id']} - {case['title']}")
        result = run_case(case, use_llm=not args.no_llm)
        path = save_result(case["id"], result)
        print_step_trace(result)
        print(f"日志：{path}")


def cmd_scan(args: argparse.Namespace) -> None:
    if args.query:
        hits = search_source_library(args.query, top_k=args.top_k)
        print(f"原始资料库检索结果：{args.query}")
        for idx, hit in enumerate(hits, start=1):
            print(f"{idx}. [{hit['source_type']}] {hit['name']} | score={hit['match_score']}")
            print(f"   {hit['path']}")
    else:
        files = scan_source_files()
        counts = {}
        for item in files:
            counts[item["source_type"]] = counts.get(item["source_type"], 0) + 1
        print(f"扫描到原始资料文件 {len(files)} 个")
        for key, value in sorted(counts.items()):
            print(f"- {key}: {value}")


def cmd_build_graph(args: argparse.Namespace) -> None:
    artifact = build_incremental_graph(max_files=args.max_files)
    print("增量建图候选已生成")
    print(f"- 候选节点数：{artifact['node_count']}")
    print(f"- 候选边数：{artifact['edge_count']}")
    print(f"- 输出文件：{artifact['output_path']}")


def cmd_asset_build(args: argparse.Namespace) -> None:
    from tools.asset_registry import build_asset_registry

    result = build_asset_registry()
    print("数据资产表目录已生成")
    print(f"- 树文件：{result['tree_path']}")
    print(f"- 数据资产类目数：{result['summary']['data_categories']}")
    print(f"- 数据表/叶子资产数：{result['summary']['data_tables']}")


def cmd_asset_search(args: argparse.Namespace) -> None:
    from tools.asset_registry import asset_summary, search_assets

    if args.query:
        hits = search_assets(args.query, top_k=args.top_k)
        print(f"数据资产检索：{args.query}")
        for idx, item in enumerate(hits, start=1):
            code = f"({item['table_code']})" if item.get("table_code") else ""
            print(f"{idx}. {item['name']}{code} [{item.get('data_category', '')}/{item.get('sub_category', '')}]")
            print(f"   {item.get('description', '')}")
    else:
        summary = asset_summary()
        print("数据资产目录：")
        print(json.dumps(summary, ensure_ascii=False, indent=2))


def cmd_instant_plan(args: argparse.Namespace) -> None:
    from tools.instant_planner import plan_instant_workflow

    result = plan_instant_workflow(args.query, use_llm=not args.no_llm)
    output_path = OUTPUT_DIR / "run_logs" / "instant_plan.json"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    plan = result["plan"]
    print(f"即时规划：{plan.get('title', '未命名规划')}")
    print(plan.get("summary", ""))
    for idx, step in enumerate(plan.get("steps", []), start=1):
        print(f"[{idx}] {step.get('step')} -> {step.get('tool')}")
    print("人工复核：")
    for item in plan.get("human_review", []):
        print(f"- {item}")
    print(f"\n日志已保存：{output_path}")


def cmd_register_item(args: argparse.Namespace) -> None:
    from tools.registry_manager import register_item

    payload = {
        "kind": args.kind,
        "name": args.name,
        "description": args.description,
        "source_path": args.source_path,
        "notes": args.notes,
    }
    item = register_item(payload, copy_file=args.copy_file)
    print("新增项已登记：")
    print(json.dumps(item, ensure_ascii=False, indent=2))


def cmd_serve(args: argparse.Namespace) -> None:
    from api_server import run

    run(host=args.host, port=args.port)


def cmd_rag_build(args: argparse.Namespace) -> None:
    from tools.bge_rag import build_bge_index

    manifest = build_bge_index(
        query_filter=args.query_filter,
        max_files=args.max_files,
        chunk_size=args.chunk_size,
        overlap=args.overlap,
        batch_size=args.batch_size,
    )
    print("BGE-M3 RAG索引已生成")
    print(f"- 文件数：{manifest['file_count']}")
    print(f"- chunk数：{manifest['chunk_count']}")
    print(f"- 模型：{manifest['model_dir']}")


def cmd_rag_ask(args: argparse.Namespace) -> None:
    from tools.bge_rag import answer_with_bge_rag

    result = answer_with_bge_rag(args.query, top_k=args.top_k, use_llm=not args.no_llm)
    output_path = OUTPUT_DIR / "run_logs" / "rag_answer.json"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(result["answer"])
    print(f"\nRAG日志已保存：{output_path}")


def cmd_template_build(args: argparse.Namespace) -> None:
    from tools.template_retriever import build_template_index

    result = build_template_index()
    print("BGE-M3工作流模板索引已生成")
    print(f"- 模板数：{result['template_count']}")
    print(f"- 索引目录：{result['index_dir']}")
    print(f"- 模型：{result['model_dir']}")


def cmd_route(args: argparse.Namespace) -> None:
    from tools.slot_filler import build_case_from_template, fill_slots
    from tools.template_retriever import retrieve_template

    templates = retrieve_template(args.query, top_k=args.top_k)
    selected = templates[0]
    filled = fill_slots(args.query, selected)
    case = build_case_from_template(args.query, selected, filled)
    result = run_case(case, use_llm=not args.no_llm)
    route_result = {
        "query": args.query,
        "matched_templates": templates,
        "selected_template": selected,
        "filled_slots": filled,
        "generated_case": case,
        "execution_result": result
    }
    output_path = OUTPUT_DIR / "run_logs" / "route_result.json"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(route_result, ensure_ascii=False, indent=2), encoding="utf-8")

    print("模板路由结果：")
    for template in templates:
        print(f"- rank={template['rank']} score={template['score']:.4f} {template['template_id']} / {template['name']}")
    print("\n槽位填充：")
    print(json.dumps(filled["slots"], ensure_ascii=False, indent=2))
    print("\n工作流：")
    print_step_trace(result)
    print("\n" + result["report"])
    print(f"\n路由日志已保存：{output_path}")


def print_step_trace(result: dict) -> None:
    print("工作流：")
    for idx, step in enumerate(result["workflow"], start=1):
        print(f"[{idx}] {step}")
    llm = result.get("llm_result", {})
    if llm.get("skipped"):
        print("LLM：已跳过")
    elif llm.get("ok"):
        print("LLM：调用成功")
    else:
        print(f"LLM：调用失败或未返回，使用本地模板报告。{llm.get('error', '')}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="机理-数据-知识融合调度智能体 demo CLI")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="列出内置演示案例")

    run_parser = sub.add_parser("run", help="运行单个demo")
    run_parser.add_argument("case_id", help="demo case id")
    run_parser.add_argument("--no-llm", action="store_true", help="不调用vLLM，使用本地模板报告")

    all_parser = sub.add_parser("run-all", help="运行所有demo")
    all_parser.add_argument("--no-llm", action="store_true", help="不调用vLLM，使用本地模板报告")

    scan_parser = sub.add_parser("scan-source", help="扫描或检索原始调规知识问答资料库")
    scan_parser.add_argument("--query", default="", help="检索关键词")
    scan_parser.add_argument("--top-k", type=int, default=8)

    graph_parser = sub.add_parser("build-graph", help="从原始资料库抽取增量图谱候选节点")
    graph_parser.add_argument("--max-files", type=int, default=80)

    sub.add_parser("asset-build", help="从 nari_mechanism_data_knowledge_tree.txt 生成数据资产表目录")

    asset_search_parser = sub.add_parser("asset-search", help="检索已注册的数据资产表目录")
    asset_search_parser.add_argument("--query", default="", help="检索关键词；为空则输出目录摘要")
    asset_search_parser.add_argument("--top-k", type=int, default=20)

    instant_parser = sub.add_parser("instant-plan", help="未命中模板时，基于候选知识和可用工具进行即时规划")
    instant_parser.add_argument("query", help="自然语言任务")
    instant_parser.add_argument("--no-llm", action="store_true", help="不调用LLM，使用本地兜底即时规划")

    register_parser = sub.add_parser("register-item", help="登记新增文档、skill、工作流、机理工具、数据资产或数据驱动模型")
    register_parser.add_argument("--kind", required=True, choices=["document", "skill", "workflow", "mechanism", "data_asset", "data_driven_model"])
    register_parser.add_argument("--name", required=True)
    register_parser.add_argument("--description", default="")
    register_parser.add_argument("--source-path", default="")
    register_parser.add_argument("--notes", default="")
    register_parser.add_argument("--copy-file", action="store_true", help="kind=document 时复制文件到 调规知识问答/新增资料")

    serve_parser = sub.add_parser("serve", help="启动网页和API服务")
    serve_parser.add_argument("--host", default="127.0.0.1")
    serve_parser.add_argument("--port", type=int, default=8765)

    rag_build_parser = sub.add_parser("rag-build", help="使用BGE-M3构建原始资料库向量索引")
    rag_build_parser.add_argument("--query-filter", default="", help="只索引文件名或路径包含该词的资料；为空则索引前N个可读文件")
    rag_build_parser.add_argument("--max-files", type=int, default=80)
    rag_build_parser.add_argument("--chunk-size", type=int, default=700)
    rag_build_parser.add_argument("--overlap", type=int, default=120)
    rag_build_parser.add_argument("--batch-size", type=int, default=4)

    rag_ask_parser = sub.add_parser("rag-ask", help="使用BGE-M3 top-k chunk召回并调用LLM进行普通RAG问答")
    rag_ask_parser.add_argument("query", help="RAG问题")
    rag_ask_parser.add_argument("--top-k", type=int, default=5)
    rag_ask_parser.add_argument("--no-llm", action="store_true")

    sub.add_parser("template-build", help="使用BGE-M3构建工作流模板向量索引")

    route_parser = sub.add_parser("route", help="使用BGE-M3识别相似工作流模板、填槽位并执行")
    route_parser.add_argument("query", help="自然语言任务")
    route_parser.add_argument("--top-k", type=int, default=3)
    route_parser.add_argument("--no-llm", action="store_true")

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    if args.command == "list":
        list_cases()
    elif args.command == "run":
        cmd_run(args)
    elif args.command == "run-all":
        cmd_run_all(args)
    elif args.command == "scan-source":
        cmd_scan(args)
    elif args.command == "build-graph":
        cmd_build_graph(args)
    elif args.command == "asset-build":
        cmd_asset_build(args)
    elif args.command == "asset-search":
        cmd_asset_search(args)
    elif args.command == "instant-plan":
        cmd_instant_plan(args)
    elif args.command == "register-item":
        cmd_register_item(args)
    elif args.command == "serve":
        cmd_serve(args)
    elif args.command == "rag-build":
        cmd_rag_build(args)
    elif args.command == "rag-ask":
        cmd_rag_ask(args)
    elif args.command == "template-build":
        cmd_template_build(args)
    elif args.command == "route":
        cmd_route(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
