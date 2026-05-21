# outputs 目录说明

本目录保存运行结果和增量建图中间产物。

- `run_logs/`：每次运行 demo 保存的完整 JSON 日志。
- `incremental_graph_candidates.json`：由 `python main.py build-graph` 生成的候选节点和边。
- `rag_index/`：由 `rag-build` 生成的 BGE-M3 chunk 索引，包括 `chunks.jsonl`、`dense.npy` 和 `manifest.json`。

这些输出用于演示“每一步调用了哪些 skill/tool/node”，也方便后续人工复核和扩展图谱。
