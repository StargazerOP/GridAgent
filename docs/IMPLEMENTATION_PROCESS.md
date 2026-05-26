# 空心设计真实化 —— 实施进度

> 启动时间：2026-05-26
> 参考计划：.claude/plans/snazzy-yawning-tide.md

---

## 阶段一：清理死代码

### 1.1 删除 StepHandler 系统（8个类）
- [ ] StepHandler.java
- [ ] StepHandlerRegistry.java
- [ ] ToolStepHandler.java
- [ ] AnalysisStepHandler.java
- [ ] DiagnosisStepHandler.java
- [ ] RagStepHandler.java
- [ ] ApprovalStepHandler.java
- [ ] ChatStepHandler.java
- [ ] PowerOpsGraphConfig.java — 移除注入代码

### 1.2 删除死 Hook（7个）
- [ ] PreRouteHook.java
- [ ] PostRouteHook.java
- [ ] PreRagHook.java
- [ ] PostRagHook.java
- [ ] PreToolUseHook.java
- [ ] PostToolUseHook.java
- [ ] PreDiagnosisHook.java
- [ ] DataMaskingHook.java
- [ ] HookConfig.java — 移除注册

### 1.3 删除未接入图节点
- [ ] EvidenceCollectNode.java
- [ ] AnswerGenerateNode.java

### 1.4 合并重复 RAG 节点
- [ ] 合并 AlarmRagRetrieveNode → RagRetrieveNode
- [ ] 删除 AlarmRagRetrieveNode.java
- [ ] PowerOpsGraphConfig.java — 更新引用

**阶段一状态：✅ 已完成**（2026-05-26）
- 删除 9 个 handler 文件（含 PlanStepHandler）
- 删除 8 个死 Hook 实现
- 删除 EvidenceCollectNode、AnswerGenerateNode
- 删除 AlarmRagRetrieveNode，实体增强逻辑合并到 RagRetrieveNode
- HookConfig 精简为仅 POST_DIAGNOSIS 的 4 个活跃 Hook
- PowerOpsGraphConfig 移除 StepHandler 注入和死节点引用
- 编译通过：173 → 154 源文件

---

## 阶段二：工具数据真实化

- [x] 创建 mock-data/ 目录及数据文件（12种设备档案、告警历史、运行日志、缺陷工单、15条安规）
- [x] 创建 MockDataLoader.java（Spring Component，启动时加载JSON到内存）
- [x] 修改 6 个工具类使用参数化数据（PowerDeviceProfileTools、PowerDeviceStatusTools、PowerAlarmHistoryTools、PowerDeviceLogsTools、PowerDefectTicketTools、PowerSafetyRulesTools）
- [x] 改进 mockEnabled=false 时的错误提示（统一为 REAL_MODE_UNAVAILABLE）
- [x] PowerAnalysisOperatorTools 三算子添加 `"availability": "requires_ems_integration"` 标记

**阶段二状态：✅ 已完成**（2026-05-26）
- 新增 `mock-data/` 下 5 个 JSON 数据文件，覆盖 12 种设备（变压器、开关柜、断路器、线路、母线、电容器组）
- 新增 `MockDataLoader.java`，支持按 deviceId 查询档案/状态/告警/日志/工单，按关键词搜索安规
- 编译通过：154 → 155 源文件

---

## 阶段三：Agent 差异化

- [x] LlmFactory 不同 Agent 不同参数（Router: 0.05/500, Tool: 0.1/2000, Diagnosis: 0.4/4000, Risk: 0.2/3000, Analysis: 0.3/3000）
- [x] Agent 工具范围限定（RiskReviewAgent: 仅 searchSafetyRules + checkOperationRisk；DiagnosisNode: 直接使用 generateWithoutTools 无工具路径）

**阶段三状态：✅ 已完成**（2026-05-26）
- LlmFactory 每个 Agent 温度/Token 已差异化
- RiskReviewAgent.create() 工具过滤为仅安规+风险校核
- DiagnosisNode 不再使用 diagnosisAgent.create().call()，改为直接调用 generateWithoutTools()
- 编译通过：155 源文件

---

## 阶段四：告警诊断独立化

- [x] AlarmController 解析结构化上下文 + 预取设备档案/状态（MockDataLoader）
- [x] GraphStreamService 新增 extraState 重载，支持传递 device_id/device_profile/device_status/alarm_type/alarm_level
- [x] 前端告警表单差异化（appendTraceStatus 支持 JSON 对象，自动显示设备预取消息）

**阶段四状态：✅ 已完成**（2026-05-26）
- AlarmController.diagnoseAlarm() 根据 deviceId 预取设备档案和实时状态，拼入诊断输入
- 结构化告警上下文通过 extraState 传入图状态
- 前端 SSE status 事件支持解析 JSON 格式，自动显示设备预取提示
- 编译通过：155 源文件

---

## 阶段五：验证器 + Hook 激活

- [x] DiagnosisValidator 升级（章节完整性6项检查、内容实质性≥40字符、设备ID引用一致性）
- [x] EvidenceQualityEvaluator 升级（StepResult成功/失败影响权重、工具失败扣分）
- [x] 重建 4 个 Hook 实现（PreRagHook 查询扩展、PostRagHook 质量过滤、PreToolUseHook 审计日志、PostToolUseHook 空值/错误检查）
- [x] 图中激活 Hook 点（ContextLoadNode: PRE_RAG+POST_RAG；ExecutorNode: PRE_TOOL_USE+POST_TOOL_USE）
- [x] HookConfig 注册全部 8 个 Hook（POST_DIAGNOSIS 4个 + PRE_RAG 1个 + POST_RAG 1个 + PRE_TOOL_USE 1个 + POST_TOOL_USE 1个）

**阶段五状态：✅ 已完成**（2026-05-26）
- DiagnosisValidator 从简单关键词检查升级为章节结构+内容长度+证据引用一致性检查
- EvidenceQualityEvaluator 增加工具调用成功/失败对评分的动态影响
- 4 个新 Hook 从空壳变为有实际行为（查询扩展、质量过滤、审计记录、结果校验）
- 编译通过：155 → 159 源文件

---

## Codex 复核与修正（2026-05-26）

### 复核结论

- 5 个阶段的主要代码改动已经进入主工程，`grid-ops-agent-app` 当前为 159 个 Java 源文件。
- StepHandler 死代码、未接入节点和旧 Hook 实现已清理，未发现残留引用。
- `mock-data/` 已接入 `MockDataLoader`，主变油温异常链路能够按 `deviceId=TR-110KV-001` 返回设备档案、实时状态、告警历史、运行日志、缺陷工单和安全规程。
- Agent 参数差异化、工具去重、无工具 LLM 降级总结、模板计划槽位填充仍然有效。
- Hook 已在 `ContextLoadNode`、`ExecutorNode`、`SafetyReviewNode` 主路径触发。

### 需要如实说明的边界

- 阶段四的“新增 alarmDiagnosisGraph() 独立图”已补齐。当前实现是：告警表单通过 `/api/alarm/diagnose` 预取结构化设备上下文，并调用独立的 `alarm_diagnosis_graph`，不再经过通用 `power_ops_workflow` 的 Router 分流。
- `PowerAnalysisOperatorTools` 仍是演示/估算算子，已经标注 `availability=requires_ems_integration`，不能作为真实 EMS/DTS 计算结果。
- Milvus 未启动时，RAG 会依赖系统已有的降级策略；演示前应在 RAG 健康面板确认当前向量库状态。

### 修正项

- 将 SSE 中的大字段 `plan_steps`、`workflow_context`、`rag_results` 改为每轮只发送一次；工具结果继续按 delta 增量发送。
- 普通诊断链路输出体积从约 253KB 降到约 55KB。
- 告警诊断链路输出体积从约 590KB 降到约 117KB。
- 新增 `compiledAlarmDiagnosisGraph`，告警表单入口改为 `GraphStreamService.streamAlarmDiagnosis()`；普通对话/知识问答继续使用 `compiledPowerOpsGraph`。
- 修复 `alarm_task.diagnosis_result` JSON 字段写入失败：最终 Markdown 诊断报告保存为 `{"format":"markdown","content":"...","generatedAt":"..."}`。

### 复核命令与结果

- `mvn -pl grid-ops-agent-app -DskipTests clean compile`：通过。
- `/api/chat/stream-graph` 输入 `主变TR-110KV-001油温86C超过80C阈值，请诊断`：无重复工具错误、无 `{device_id}` 泄漏、无异常、工具调用 7 项成功 7 项。
- `/api/alarm/receive` + `/api/alarm/diagnose` 提交 `TR-110KV-001` 油温告警：进入 `alarm_diagnosis_graph`，未出现 `router` 节点，设备预取成功，工具调用 7 项成功 7 项，数据库状态保存为 `COMPLETED`。
