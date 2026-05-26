# Agent 状态字段生命周期

本文档用于约束 GridOpsAgent Graph 执行过程中的关键状态字段，避免跨节点状态残留造成重规划循环、重复工具调用或前端展示失真。

## 设计原则

- 状态字段只在明确节点写入，消费节点不得隐式假设字段永远有效。
- 临时控制字段必须有清理时机，例如 `additional_steps` 在 `ExecutorNode` 合并执行后清空。
- 展示字段与控制字段分离：`step_results`、`evidence` 可用于溯源展示，`next_action`、`loop_count` 只用于图分支控制。
- 工具失败要区分“业务异常证据”和“接口失败”。例如“冷却器风扇启动失败”是诊断证据，不是工具调用失败。

## 字段总览

| 字段 | 写入节点 | 消费节点 | 清理/覆盖时机 | 说明 |
| --- | --- | --- | --- | --- |
| `input` | `GraphStreamService` | `PreCheckNode`、兜底节点 | 单次 Graph 初始化 | 用户原始输入。 |
| `cleaned_input` | `PreCheckNode` | `ContextLoadNode`、`RouterNode`、诊断子图 | 每轮输入覆盖 | 清洗后的任务文本。 |
| `task_id` | `GraphStreamService` / `PreCheckNode` | Checkpoint、Trace、工具日志 | 单次 Graph 初始化 | 本轮诊断任务 ID。 |
| `session_id` | `GraphStreamService` / `PreCheckNode` | Memory、Checkpoint、Trace | 单次 Graph 初始化 | 会话 ID。 |
| `trace_id` | `PreCheckNode` / `ObservedNodeAction` | `ExecutorNode`、Trace 展示 | 单次 Graph 初始化 | 可观测链路 ID。 |
| `intent` | `RouterNode` | `IntentDispatcher`、子图 | 每轮路由覆盖 | `knowledge_qa`、`diagnosis`、`chat` 等。 |
| `workflow_context` | `ContextLoadNode` | `PlannerNode`、前端 Trace | 每轮上下文装载覆盖 | nari 迁移模板、候选节点、推荐工具。 |
| `entities` | `EntityExtractNode` | `PlannerNode`、诊断报告 | 每轮实体抽取覆盖 | 设备、告警、故障类型等结构化实体。 |
| `rag_results` | `AlarmRagRetrieveNode` / RAG 子图 | `PlannerNode`、`DiagnosisNode`、前端 Trace | 每轮检索覆盖 | 文档召回结果。 |
| `plan_steps` | `PlannerNode`、`ExecutorNode` | `ExecutorNode`、`ReplannerNode`、前端 Trace | Planner 首次写入，Executor 更新状态 | 诊断执行计划及每步状态。 |
| `additional_steps` | `ReplannerNode` | `ExecutorNode` | Executor 合并后清空 | 重规划新增步骤，避免残留重复执行。 |
| `step_results` | `ExecutorNode` | `EvidenceValidationNode`、`ReplannerNode`、`ActionRecommendNode`、`FinalResponseNode` | `ReplaceStrategy` 覆盖为当前全量结果，避免 AppendStrategy 重复追加 | 工具调用结果，保留用于溯源。 |
| `evidence` | `ExecutorNode`、`DiagnosisNode` | `EvidenceValidationNode`、`DiagnosisNode`、`ActionRecommendNode` | 单次 Graph 内累计 | 面向诊断生成的证据摘要。 |
| `execution_result` | `ExecutorNode`、`DiagnosisNode` | `PlannerNode`、`DiagnosisNode`、`ActionRecommendNode` | 后续节点覆盖 | 当前执行阶段摘要。 |
| `evidence_score` | `EvidenceValidationNode` | `ReplannerNode`、前端 Trace | 每次证据校验覆盖 | 证据质量评分。 |
| `evidence_coverage` | `EvidenceValidationNode` | `ReplannerNode`、前端 Trace | 每次证据校验覆盖 | 设备状态、告警、日志、工单、安规等覆盖情况。 |
| `evidence_warnings` | `EvidenceValidationNode` | `ReplannerNode`、前端 Trace | 每次证据校验覆盖 | 缺失证据提示。 |
| `next_action` | `PreCheckNode`、`EvidenceValidationNode`、`ReplannerNode`、`SafetyReviewNode` | Dispatcher | 每个控制节点覆盖 | 控制 Graph 分支。证据充分时必须覆盖旧的 `REPLAN`。 |
| `loop_count` | `ReplannerNode` | `ReplannerNode`、Dispatcher、前端 Trace | 单次 Graph 内递增 | 重规划次数，达到上限后停止补充。 |
| `diagnosis_result` | `DiagnosisNode` | `ActionRecommendNode`、`FinalResponseNode` | 诊断节点覆盖 | 专项诊断结论草稿。 |
| `risk_level` | `RiskAssessmentNode` / 诊断子图 | `ReplannerNode`、`SafetyReviewNode`、最终回答 | 风险评估覆盖 | `LOW`、`MEDIUM`、`HIGH`、`CRITICAL`。 |
| `final_response` | `ActionRecommendNode`、`FinalResponseNode` | Controller/SSE | 最终节点覆盖 | 用户可见最终答案。 |
| `_last_node_name` | `ObservedNodeAction` | `GraphStreamService` | 每个节点覆盖 | 观测用内部字段。 |
| `_last_node_duration_ms` | `ObservedNodeAction` | `GraphStreamService` | 每个节点覆盖 | 节点耗时。若不可用，SSE 使用事件间隔兜底。 |

## 关键清理规则

| 字段 | 清理节点 | 原因 |
| --- | --- | --- |
| `additional_steps` | `ExecutorNode` | 避免同一批补充步骤在后续循环重复追加。 |
| `next_action` | `EvidenceValidationNode` | 每次证据校验都必须重新决策，不能被上一个节点残留的 `REPLAN` 锁死。 |
| `active trace item` | 前端 `archiveActiveTraceItem` | 只展示当前执行节点，其余折叠进溯源列表。 |
| `step_results` SSE 展示 | `GraphStreamService` | 前端事件只携带本节点新增工具结果、总数和失败摘要，避免每个节点重复展示历史工具调用。 |

## 失败类型规范

| errorType | 中文展示 | 含义 |
| --- | --- | --- |
| `DATA_SOURCE_NOT_CONNECTED` | 数据源未接入 | 真实模式下 PMS/SCADA/EMS/工单等外部系统未接入。 |
| `MOCK_DATA_MISSING` | 演示数据缺失 | mock 数据未覆盖当前设备、告警或查询条件。 |
| `PARAMETER_MISMATCH` | 参数不匹配 | 计划步骤缺少有效 `deviceId` 等关键参数。 |
| `INTERFACE_EXCEPTION` | 接口异常 | 工具接口抛错、连接异常或返回错误状态。 |
| `INTERFACE_TIMEOUT` | 接口超时 | 工具调用超时。 |
| `INTERFACE_UNAUTHORIZED` | 接口未授权 | 权限或认证失败。 |
| `INVALID_RESPONSE_SCHEMA` | 返回结构不合法 | 工具返回缺少期望业务字段。 |
| `INVALID_RESPONSE_FORMAT` | 返回格式不合法 | 工具返回不是合法 JSON。 |
| `TOOL_NOT_REGISTERED` | 工具未注册 | Planner/Replanner 生成了当前系统不可执行的工具名。 |
| `EMPTY_TOOL_RESULT` | 工具无返回 | 工具返回空内容。 |

## 当前已修复的状态问题

- `EvidenceValidationNode` 在证据充分时强制写入 `CONTINUE`，不再继承旧 `REPLAN`。
- `ExecutorNode` 执行后清空 `additional_steps`。
- `ReplannerNode` 读取 `StepResult` 的驼峰/下划线字段，减少误判。
- `FinalResponseNode` 和 `ActionRecommendNode` 按有效工具调用去重统计，避免累计数虚高。
- `GraphStreamService` 为节点耗时提供事件间隔兜底，避免前端显示 `0ms`。
- `PowerOpsStateFactory` 将 `step_results` 调整为覆盖策略，由 `ExecutorNode` 显式维护全量结果，避免跨重规划循环重复追加。
- `GraphStreamService` 增加 `step_results_total`、`step_results_delta_count`、`tool_result_summary` 和 `tool_result_status`，前端优先展示本节点新增结果和失败分类。
