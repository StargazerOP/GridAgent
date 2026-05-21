# tools 目录说明

Tool 是可执行工具，输入输出相对确定。在融合图谱中，Tool 对应 `tool_call` 节点；Skill/Workflow 通过调用多个 Tool 完成一个任务流程。

当前工具：

- `graph_query.py`：查询演示实体图谱。
- `topology_analyzer.py`：分析一跳/两跳邻接、馈线、重要用户和路径提示。
- `plan_retriever.py`：从结构化预案和原始资料库召回事故预案。
- `regulation_retriever.py`：从结构化依据和原始资料库召回调规/继保依据。
- `mock_power_check.py`：模拟潮流、越限、N-1操作风险校核。
- `mock_stability_check.py`：模拟暂稳、断面、频率和发用电平衡风险。
- `llm_client.py`：调用服务器 vLLM 的 OpenAI 兼容接口。
- `source_scanner.py`：扫描和检索 `config.py` 中配置的原始资料库，默认是 `<workspace_root>\调规知识问答`。
- `graph_builder.py`：从原始资料库抽取增量图谱候选节点和边。
- `bge_rag.py`：使用 `config.py` 中的 `BGE_M3_MODEL_DIR` 和 `FlagEmbedding` 实现文档切块、embedding、向量索引、top-k chunk召回和普通RAG问答。BGE-M3 模型不随 demo 打包，需要使用者自行指定模型目录。
- `template_retriever.py`：使用 BGE-M3 对工作流模板进行向量索引和相似模板召回。
- `slot_filler.py`：从自然语言任务中抽取设备、故障类型、电压等级、转供对象等槽位，并生成可执行 case。

当前机理类工具是演示模拟接口，后续可替换为真实潮流计算、稳定计算、在线安全校核或调控云接口。

按图谱口径，工具调用节点分为三类：

- 机理算子/机理模型工具：如 `mock_power_check.py`、`mock_stability_check.py`，正式系统可替换为课题2的电网分析决策基础算子，包括潮流计算算子、稳定计算算子、时间序列预测算子、空间特征分析算子、故障场景生成算子和机组组合优化算子。
- 数据模型工具：当前以接口占位方式体现在流程中，正式系统可接入课题3气象预测、新能源出力预测，以及课题4电网科学计算基础模型、潮流快速估计、状态预测、风险识别等工具。
- 普通工程工具：如 `graph_query.py`、`topology_analyzer.py`、`plan_retriever.py`、`regulation_retriever.py`、`bge_rag.py`、`template_retriever.py`、`slot_filler.py` 和 `llm_client.py`，负责把资料、图谱、向量检索、模板路由和报告生成串起来。

BGE-M3 RAG 建议只在项目独立环境中运行，避免污染 base。先进入 `<project_root>` 并激活环境，再执行：

```powershell
python main.py rag-build --query-filter 奥体变 --max-files 20
python main.py rag-ask "奥体变全停后如何恢复重要用户供电？"
python main.py template-build
python main.py route "白云变全停后如何处置？"
```
