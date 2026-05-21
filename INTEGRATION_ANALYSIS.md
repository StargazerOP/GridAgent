# GridOpsAgent-main × nari_demo 整合分析方案

## 一、两个项目概览

### GridOpsAgent-main（Java / Spring Boot 企业级智能调度运维平台）

**技术栈**：Spring Boot + DashScope AI + StateGraph 工作流引擎 + Milvus 向量检索 + MySQL/MyBatis Plus + Redis

**核心能力**：
- **StateGraph 工作流引擎**：22个节点的有状态图编排，支持条件路由、回环和子图
- **三意图路由**：KNOWLEDGE_QA（知识问答）→ RAG 管线；DIAGNOSIS（诊断分析）→ 规划-执行-证据验证-诊断-风险评估-重规划 循环；CHAT（通用对话）
- **多智能体架构**：RouterAgent、ToolAgent、DiagnosisAgent、AnalysisAgent、RiskReviewAgent
- **安全合规**：SafetyReview 节点、ApprovalService 审批、RbacService 权限、HookEngine 钩子
- **可观测性**：全链路 ObservabilityService + CheckpointService 断点恢复
- **电力专用工具**：设备台账、设备状态、告警历史、设备日志、缺陷工单、安规检索（均为 mock 数据）
- **Skill 插件体系**：SkillRegistry / SkillSelector，支持场景化技能注册
- **持久化**：ChatSession、ChatMessage、AgentExecutionLog、ToolCallLog、KnowledgeChunk 等完整数据模型

### nari_demo（Python 本地演示平台）

**技术栈**：Python + BGE-M3 向量检索 + Qwen LLM + 本地知识图谱 + 简易 HTTP API + Web UI

**核心能力**：
- **9个固化工作流模板**：变电站全停故障处置、负荷转供校核、保护投退校核、N-1 风险校核、500kV 母线故障处置、潮流自动计算、断面潮流计算、运行方式调整越限校核、新能源场站停电处置
- **18个机理叶子算子**：牛顿拉夫逊、稀疏矩阵、最优潮流、连续潮流、偏微分方程、稳定性判别、稳定裕度计算、外部影响因素、周期识别、趋势预测、随机波动生成、拓扑相关性、聚类、不确定性抽样、时空相关性生成、整数优化、随机优化、鲁棒优化
- **3个 Skill**：fault_plan_generation（故障预案生成）、operation_check（操作校核）、power_flow_calculation（潮流计算）
- **知识图谱**：71个节点 / 255条边，涵盖工作流-工具-算子-知识实体-数据资产的完整关联
- **模板路由 + 槽位填充**：BGE-M3 语义检索匹配模板 → 槽位解析 → 执行工作流
- **即时规划**：模板未命中时，基于候选知识和可用工具进行 LLM 驱动 / 本地兜底规划
- **资产注册表**：2315个数据资产（从机理-数据-知识树抽取），支持检索和管理
- **Web UI**：交互式图谱可视化、资产管理、任务检索、即时规划

---

## 二、功能互补分析

### nari_demo 有而 GridOpsAgent 缺失的能力

| 能力 | 详细说明 | 整合优先级 |
|------|---------|-----------|
| **固化工作流模板（9个）** | 电网典型调度场景的标准化流程，可直接替代硬编码路由逻辑 | 🔴 极高 |
| **机理算子库（18个）** | 潮流计算、稳定分析、时间序列预测、空间分析、故障场景生成、机组优化等物理计算能力定义 | 🔴 极高 |
| **知识图谱（71节点/255边）** | 工作流-工具-算子-知识实体-数据资产的语义关联网络 | 🔴 极高 |
| **模板路由 + 槽位填充** | BGE-M3 驱动的工作流模板语义匹配，比三意图路由更精细 | 🟠 高 |
| **即时规划（Instant Planning）** | 模板未命中时的兜底规划能力，LLM + 本地兜底双模式 | 🟠 高 |
| **数据资产注册表（2315项）** | 按数据分类组织的完整数据目录 | 🟠 高 |
| **数据驱动模型定义** | 潮流快速估计、气象与新能源出力预测等 AI 模型能力定义 | 🟡 中 |
| **Web 可视化 UI** | 图谱交互式浏览、资产管理、任务检索前端 | 🟡 中 |

### GridOpsAgent 有而 nari_demo 缺失的能力

| 能力 | 详细说明 | 整合方向 |
|------|---------|---------|
| **StateGraph 工作流引擎** | 条件路由、回环、子图、状态持久化、断点恢复 | 作为主引擎替代 nari_demo 的轻量串行执行 |
| **安全合规审查** | SafetyReview 节点 + ApprovalService + RbacService | 全场景覆盖 |
| **全链路可观测性** | ObservabilityService + CheckpointService | 全场景覆盖 |
| **完整持久化体系** | MySQL 存储会话/消息/日志，Redis 缓存，Milvus 向量检索 | 替换 nari_demo 的 JSON 文件存储 |
| **企业级部署** | Spring Boot 微服务架构，Docker Compose 一键部署 | 作为统一部署底座 |
| **多智能体协作** | Router → Tool → Diagnosis → RiskReview 管道 | 吸收 nari_demo 的领域知识后更精准 |

---

## 三、整合架构设计

### 整体策略：以 GridOpsAgent 为底座，吸收 nari_demo 的领域资产

```
┌─────────────────────────────────────────────────────────────────┐
│                    GridOpsAgent v2.0（整合版）                    │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    Web UI 层                               │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │   │
│  │  │ 图谱浏览器 │  │ 资产管理  │  │ 任务检索  │  │ 诊断面板  │ │   │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘ │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                  Controller 层（Spring MVC）               │   │
│  │  ChatController │ KnowledgeController │ SkillController  │   │
│  │  AlarmController│ ApprovalController   │ GraphController │   │
│  │  AssetController│ PlanController       │ EvalController  │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              StateGraph 工作流引擎（核心调度层）            │   │
│  │                                                            │   │
│  │  pre_check → context_load → router                        │   │
│  │      │                   │                                │   │
│  │      │    ┌──────────────┼──────────────┐                │   │
│  │      │    ▼              ▼              ▼                │   │
│  │      │ knowledge_qa   diagnosis       chat               │   │
│  │      │ (RAG管线)    (诊断子图)    (对话子图)              │   │
│  │      │              ▲新增模板路由▲                         │   │
│  │      │         template_router → slot_fill → execute      │   │
│  │      │              │（未命中时）                          │   │
│  │      │              ▼                                      │   │
│  │      │         instant_planner                            │   │
│  │      │                                                    │   │
│  │      └──────────→ safety_review → final_response          │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    智能体层（Multi-Agent）                 │   │
│  │                                                            │   │
│  │  RouterAgent  ToolAgent  DiagnosisAgent                  │   │
│  │    ▲扩展为       ▲扩展        ▲扩展                        │   │
│  │  5类意图     41+工具     9项诊断模板                       │   │
│  │                                                            │   │
│  │  AnalysisAgent  RiskReviewAgent  SkillSelector           │   │
│  │   ▲新增            ▲新增            ▲扩展                  │   │
│  │  机理分析       机理风险评估     3→N个Skill               │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    工具层（Tool Layer）                    │   │
│  │                                                            │   │
│  │  原有工具（6个）                 新增工具（来自nari_demo） │   │
│  │  ├ getDeviceProfile              ├ GraphQueryTool         │   │
│  │  ├ getDeviceStatus               ├ TopologyAnalyzerTool   │   │
│  │  ├ getAlarmHistory               ├ PlanRetrieverTool      │   │
│  │  ├ getDeviceLogs                 ├ RegulationRetriever    │   │
│  │  ├ getDefectTickets              ├ PowerFlowCalcTool      │   │
│  │  └ searchSafetyRules             ├ StabilityCheckTool     │   │
│  │                                  ├ TemplateRouterTool     │   │
│  │  MCP 可扩展工具                   ├ SlotFillerTool        │   │
│  │  ├ PowerAlarmHistoryTools        ├ InstantPlannerTool     │   │
│  │  ├ PowerDefectTicketTools        └ SourceScannerTool      │   │
│  │  └ PowerDeviceStatusTools                                 │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                 数据与知识层                               │   │
│  │                                                            │   │
│  │  MySQL               Milvus            Redis             │   │
│  │  ├ ChatSession       ├ KnowledgeChunk   ├ Session Cache  │   │
│  │  ├ ChatMessage       ├ WorkflowTemplate ├ Token Bucket   │   │
│  │  ├ AgentExecLog      ├ MechanismOp      └ Rate Limit     │   │
│  │  ├ ToolCallLog       ├ KnowledgeGraph                   │   │
│  │  ├ KnowledgeDoc      └ AssetRegistry                    │   │
│  │  ├ WorkflowTemplate                                     │   │
│  │  ├ AssetRegistry                                        │   │
│  │  └ SkillRegistry                                        │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │             Python 微服务（可选，机理计算引擎）             │   │
│  │                                                            │   │
│  │  nari-mechanism-service（从 nari_demo 抽取）              │   │
│  │  ├ /api/power_flow/compute    牛顿拉夫逊潮流计算          │   │
│  │  ├ /api/stability/assess      稳定分析与裕度评估          │   │
│  │  ├ /api/prediction/forecast   时间序列预测                │   │
│  │  ├ /api/fault/generate        故障场景生成                │   │
│  │  └ /api/optimization/uc       机组组合优化                │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 四、分阶段实施路线图

### 第一阶段：知识资产迁移（2-3周）

**目标**：将 nari_demo 的静态知识资产导入 GridOpsAgent 的数据层

#### 1.1 工作流模板入库
- 将 `data/workflow_templates.json` 的 9 个模板转为 Java Entity `WorkflowTemplate`
- 为 `SkillRegistry` 注册对应的 3 个 Skill（fault_plan_generation、operation_check、power_flow_calculation）
- 每个 Skill 包含 prompt.md 的提示词模板和 demos.json 的示例案例

```java
// 新增实体
@Entity
public class WorkflowTemplate {
    private String templateId;
    private String scene;          // fault_plan_generation | operation_check | power_flow_calculation
    private String name;
    private String description;
    private List<String> keywords;
    private Map<String, Object> slots;
    private List<WorkflowStep> workflow;  // 步骤+工具+参数
    private String promptTemplate;        // 来自 prompt.md
}
```

#### 1.2 知识图谱入库（Milvus + MySQL）
- `graph/nodes.json`（71节点）→ `KnowledgeGraphNode` 实体 + Milvus 向量索引
- `graph/edges.json`（255边）→ `KnowledgeGraphEdge` 实体
- `graph/schema.json` → 节点/关系类型元数据配置

```java
@Entity
public class KnowledgeGraphNode {
    private String nodeId;
    private String name;
    private String category;       // skill_process | tool_call | knowledge_entity
    private String role;           // workflow | operator | model_tool | entity | rule | ...
    private String description;
    private List<String> keywords;
    private Map<String, Object> properties;
    // Milvus embedding
}
```

#### 1.3 机理算子注册
- 将 18 个机理叶子算子（6 个算子大类）注册为 `MechanismOperator` 实体
- 保留算子输入/输出/适用边界的完整语义描述
- 在 Milvus 中建立向量索引用于语义检索

```java
@Entity
public class MechanismOperator {
    private String operatorId;
    private String name;
    private String operatorLevel;   // family | leaf
    private String parentOperator;
    private List<String> inputs;
    private List<String> outputs;
    private String applicableBoundary;
    private boolean realInterface;  // 是否已接入真实计算接口
}
```

#### 1.4 数据资产注册表
- 将 2315 项数据资产从 `outputs/asset_registry.json` 导入
- 建立 `DataAsset` 实体，按 9 个数据类目组织
- Milvus 向量化以备检索

---

### 第二阶段：路由与规划能力升级（2-3周）

**目标**：将 nari_demo 的模板路由和即时规划能力融入 StateGraph

#### 2.1 RouterAgent 升级为 5 意图路由

原版只有 3 意图（KNOWLEDGE_QA / DIAGNOSIS / CHAT），升级为：

```java
public enum Intent {
    KNOWLEDGE_QA,        // 知识问答（保留）
    DIAGNOSIS,           // 诊断分析（保留，增强为模板路由）
    CHAT,                // 通用对话（保留）
    OPERATION_CHECK,     // 操作校核（新增）
    POWER_FLOW_CALC,     // 潮流计算（新增）
}
```

RouterAgent 的 prompt 需要扩展，融入 nari_demo 的场景识别关键词：

```
判断规则新增：
- 涉及"负荷转供/保护投退/N-1校核/方式调整" → OPERATION_CHECK
- 涉及"潮流计算/断面潮流/越限校核/快速估计" → POWER_FLOW_CALC
- 故障诊断类任务优先走模板匹配，若命中工作流模板则在 DIAGNOSIS 路径内走模板执行
```

#### 2.2 新增 TemplateRouterNode（模板路由节点）

在 DIAGNOSIS 路径的 entity_extract 之后插入：

```
entity_extract → template_router → （命中）slot_fill → execute_by_template
                                 → （未命中）alarm_rag_retrieve → planner → ...（原路径）
```

TemplateRouterNode 的核心逻辑：
1. 接收 entity_extract 提取的设备/场景实体
2. 在 WorkflowTemplate 表中进行 BGE-M3 / Milvus 语义检索
3. 返回匹配度最高的模板（score > 阈值则命中）
4. 未命中时走原 planner → executor 路径

#### 2.3 新增 InstantPlannerNode（即时规划节点）

当模板路由未命中 + LLM Planner 也失败时启用：

```
replanner → （多次失败）→ instant_planner → action_recommend
```

InstantPlannerNode 逻辑（移植自 `tools/instant_planner.py`）：
1. 召回候选知识节点（Milvus 语义检索）
2. 召回相关数据资产
3. 整理可用工具清单
4. LLM 生成临时流程 / 本地规则兜底
5. 标注 `template_status: instant_not_persisted`

#### 2.4 Skill 体系扩展

扩展 SkillRegistry，将 nari_demo 的 3 个 Skill 注册为完整的 GridOpsAgent Skill：

```
现有 Skill（假设有基础 Skill）
+ fault_plan_generation    → 故障预案生成 Skill
+ operation_check          → 操作校核 Skill
+ power_flow_calculation   → 潮流计算 Skill
+ instant_planning         → 即时规划 Skill（新增）
```

每个 Skill 关联：
- 适用场景列表（scenes）
- 对应的 WorkflowTemplate
- 推荐工具集（recommendedTools）
- 提示词模板（promptTemplate，来自 prompt.md）
- 示例案例（examples，来自 demos.json）

---

### 第三阶段：工具层扩展（2-3周）

**目标**：将 nari_demo 的工具能力以 Java Tool 形式接入

#### 3.1 机理类工具（对接 Python 微服务）

将 nari_demo 的机理算子以 `@Tool` 注解注册，底层调用 Python 微服务：

```java
@Component
public class PowerFlowCalcTool {
    
    @Tool(description = "调用牛顿拉夫逊算子进行潮流计算，输入网络拓扑和运行参数，返回节点电压、线路潮流和收敛状态")
    public String computePowerFlow(
        @ToolParam(description = "网络拓扑 JSON") String topologyJson,
        @ToolParam(description = "运行方式参数") String operationParams) {
        // 调用 nari-mechanism-service /api/power_flow/compute
        // 或使用本地 mock 实现
    }
    
    @Tool(description = "评估电网暂态稳定风险，返回稳定裕度和控制建议")
    public String assessStability(
        @ToolParam(description = "故障场景") String faultScenario,
        @ToolParam(description = "运行断面") String operationSnapshot) {
        // 调用 nari-mechanism-service /api/stability/assess
    }
}
```

#### 3.2 工程类工具（纯 Java 实现）

从 nari_demo 移植并在 Java 层实现：

| nari_demo 工具 | GridOpsAgent 对应工具 | 实现方式 |
|---|---|---|
| `graph_query.py` | `GraphQueryTool` | 查询 MySQL 知识图谱表 |
| `topology_analyzer.py` | `TopologyAnalyzerTool` | 基于图谱边关系做 BFS/DFS |
| `plan_retriever.py` | `PlanRetrieverTool` | Milvus 向量检索历史预案 |
| `regulation_retriever.py` | `RegulationRetrieverTool` | Milvus 检索安规条款 |
| `template_retriever.py` | `TemplateRetrieverTool` | Milvus 检索工作流模板 |
| `slot_filler.py` | `SlotFillerTool` | LLM 驱动的槽位解析 |
| `source_scanner.py` | `SourceScannerTool` | 统一数据源查询 |
| `instant_planner.py` | `InstantPlannerTool` | 即时规划（已在节点层实现） |

#### 3.3 MCP 协议扩展

利用 GridOpsAgent 已有的 `spring-ai-starter-mcp-client-webflux`，将 nari_demo 的 Python 工具以 MCP Server 方式接入：

```
GridOpsAgent (MCP Client)
    │
    ├── MCP Server: nari-knowledge-graph
    │   ├── query_nodes(query)
    │   ├── query_edges(node_id)
    │   └── search_related(entity)
    │
    ├── MCP Server: nari-mechanism-engine
    │   ├── power_flow_compute(params)
    │   ├── stability_assess(scenario)
    │   ├── time_series_forecast(data)
    │   └── fault_scenario_generate(params)
    │
    └── MCP Server: nari-data-assets
        ├── search_assets(query)
        ├── get_asset_detail(asset_id)
        └── list_asset_categories()
```

---

### 第四阶段：安全与合规增强（1-2周）

#### 4.1 SafetyReview 增强

在原 SafetyReviewNode 中集成 nari_demo 的人工复核要求：

```java
// SafetyReviewNode 增加复核项检查
- 涉及真实调度操作 → 标记需人工确认
- 涉及保护投退 → 标记需现场核对
- 涉及重要用户恢复 → 标记需汇报确认
- 即时规划的结果 → 必须人工复核
- 模拟工具结果 → 标注"未接入真实接口"
```

#### 4.2 调规约束引擎

将 nari_demo 的 `regulation_retriever` 和 `data/regulations.json` 整合进 HookEngine：

```
HookEngine
├── pre_tool_hook: 工具调用前检查是否在合规边界内
├── post_tool_hook: 工具返回后验证是否违反安规
├── pre_action_hook: 操作建议生成前注入调规约束
└── post_action_hook: 操作建议后检索相关事故案例
```

---

### 第五阶段：前端可视化（2-3周）

#### 5.1 图谱浏览器

将 nari_demo 的 `web_ui/app.js` 中的 D3.js 图谱可视化移植到 GridOpsAgent 前端：
- 完整图谱展示（71节点/255边）
- 节点点击查看详情卡片
- 按类别筛选（工作流/工具/算子/知识实体/数据资产）
- 子图高亮（选中节点上下游展开）

#### 5.2 资产管理面板

将 nari_demo 的资产管理页整合：
- 9 大工作流模板卡片（含步骤预览）
- 18 个机理算子目录（按大类分组）
- 2315 数据资产表（可搜索、筛选）
- 新增资产登记表单

#### 5.3 任务执行面板

增强 ChatController 的前端交互：
- 模板命中时展示工作流步骤进度（step-by-step 可视化）
- 即时规划时展示候选知识和工具
- 诊断报告的结构化渲染（9 项诊断报告模板）
- 风险等级可视化（CRITICAL/HIGH/MEDIUM/LOW 颜色标记）

---

## 五、关键技术决策

### 5.1 模板路由算法

**方案 A（推荐）**：Milvus 向量检索
- 将 WorkflowTemplate 的 name + description + keywords 拼接后生成 embedding
- 用户任务 query 同样生成 embedding
- Top-K 相似度匹配，阈值 > 0.75 视为命中
- 优点：与现有 Milvus 基础设施统一

**方案 B**：BGE-M3 独立服务
- 保持 nari_demo 的 BGE-M3 索引
- 通过 HTTP API 调用检索
- 优点：保留原实现，改动最小

**决策**：推荐方案 A，将模板向量存入 Milvus，统一向量检索引擎。

### 5.2 机理计算实现方式

**方案 A**：Python 微服务（nari-mechanism-service）
- 从 nari_demo 抽取机理相关代码，独立部署
- GridOpsAgent 通过 REST API 调用
- 优点：保持 Python 科学计算生态（numpy/scipy 等），真实计算能力强

**方案 B**：Java 纯实现
- 将机理算法用 Java 重写
- 优点：减少服务依赖，部署简单

**决策**：推荐方案 A（短期 mock，长期微服务）。当前阶段机理算子以注册能力定义为主，实际计算先 mock；待真实接口就绪后替换为 Python 微服务调用。

### 5.3 即时规划触发策略

| 条件 | 行为 |
|------|------|
| 模板匹配 score ≥ 0.85 | 直接执行模板工作流 |
| 0.60 ≤ score < 0.85 | 展示匹配模板供用户确认，确认后执行 |
| score < 0.60 | LLM Planner 生成计划，失败时降级到 InstantPlanner |
| LLM Planner 也失败 | 本地兜底 InstantPlanner（基于规则） |
| 用户明确拒绝模板 | 走 InstantPlanner |

---

## 六、数据流示例（整合后）

### 场景：奥体变全停故障处置

```
用户输入："奥体变全停，需要恢复所用电和重要用户供电"

1. pre_check → 输入校验通过
2. context_load → 加载历史会话上下文 + Skill 匹配
3. router → 意图：DIAGNOSIS
4. entity_extract → 实体：奥体变、全停、所用电、重要用户
5. template_router → Milvus 检索匹配：
   ✅ 命中 "变电站全停故障处置流程" (score=0.92)
6. slot_fill → 槽位填充：
   - 故障对象：奥体变
   - 故障类型：全停
   - 恢复目标：所用电、重要用户
7. execute_by_template:
   Step 1: 召回奥体变全停预案（PlanRetrieverTool）
   Step 2: 查询奥体变及关联设备图谱（GraphQueryTool）
   Step 3: 分析影响范围（TopologyAnalyzerTool）
   Step 4: 机理风险评估（StabilityCheckTool）
   Step 5: 检索调规依据（RegulationRetrieverTool）
   Step 6: LLM 生成处置预案（DiagnosisAgent）
8. risk_assessment → 风险等级评估
9. safety_review → 安全合规审查
10. final_response → 输出结构化预案
11. memory_save → 保存会话上下文

输出：
## 奥体变全停故障处置预案
1. 召回预案：110千伏奥体变全停事故处理预案
2. 影响范围：奥体变10kV母线失电，影响重要用户嘉庆变供电
3. 机理风险：风险等级 HIGH，需关注暂态稳定和电压跌落
4. 处置步骤：（10步操作流程）
5. 依据：调度规程第X条
6. 人工确认项：核对实时断面、保护动作、开关位置
⚠️ 本方案含模拟评估结果，正式执行前需人工复核
```

---

## 七、目录结构变更（GridOpsAgent 整合后）

```
GridOpsAgent-main/
├── grid-ops-agent-app/
│   ├── src/main/java/org/example/
│   │   ├── agent/
│   │   │   ├── router/RouterAgent.java              # 扩展为5意图路由
│   │   │   ├── diagnosis/DiagnosisAgent.java        # 集成9项诊断模板
│   │   │   ├── analysis/AnalysisAgent.java          # 新增机理分析
│   │   │   ├── risk/RiskReviewAgent.java            # 集成机理风险评估
│   │   │   ├── tool_agent/ToolAgent.java            # 扩展工具调用
│   │   │   └── skill/
│   │   │       ├── model/Skill.java                 # 扩展 skill 元数据
│   │   │       └── service/
│   │   │           ├── SkillRegistry.java           # 注册3+新 Skill
│   │   │           └── SkillSelector.java
│   │   │
│   │   ├── graph/
│   │   │   ├── PowerOpsGraphConfig.java             # 新增节点和路由
│   │   │   ├── node/
│   │   │   │   ├── TemplateRouterNode.java          # 🆕 模板路由
│   │   │   │   ├── SlotFillNode.java                # 🆕 槽位填充
│   │   │   │   ├── InstantPlannerNode.java          # 🆕 即时规划
│   │   │   │   └── TemplateExecuteNode.java         # 🆕 模板执行
│   │   │   ├── dispatcher/
│   │   │   │   ├── TemplateMatchDispatcher.java     # 🆕 模板匹配分发
│   │   │   │   └── PlanFallbackDispatcher.java      # 🆕 规划降级分发
│   │   │   └── model/
│   │   │       ├── WorkflowStep.java                # 🆕 工作流步骤
│   │   │       └── PlanningContext.java             # 🆕 规划上下文
│   │   │
│   │   ├── entity/
│   │   │   ├── WorkflowTemplate.java                # 🆕 工作流模板
│   │   │   ├── MechanismOperator.java               # 🆕 机理算子
│   │   │   ├── KnowledgeGraphNode.java              # 🆕 知识图谱节点
│   │   │   ├── KnowledgeGraphEdge.java              # 🆕 知识图谱边
│   │   │   └── DataAsset.java                       # 🆕 数据资产
│   │   │
│   │   ├── tool/
│   │   │   ├── power/
│   │   │   │   ├── GraphQueryTools.java             # 🆕 图谱查询
│   │   │   │   ├── TopologyAnalyzerTools.java       # 🆕 拓扑分析
│   │   │   │   ├── PlanRetrieverTools.java          # 🆕 预案召回
│   │   │   │   ├── RegulationRetrieverTools.java    # 🆕 调规检索
│   │   │   │   ├── PowerFlowCalcTools.java          # 🆕 潮流计算
│   │   │   │   ├── StabilityCheckTools.java         # 🆕 稳定校核
│   │   │   │   ├── TemplateRouterTools.java         # 🆕 模板路由
│   │   │   │   └── InstantPlannerTools.java         # 🆕 即时规划
│   │   │   └── mechanism/                           # 🆕 机理工具包
│   │   │       ├── PowerFlowOperator.java
│   │   │       ├── StabilityOperator.java
│   │   │       ├── TimeSeriesPredictor.java
│   │   │       ├── FaultScenarioGenerator.java
│   │   │       └── UnitCommitmentOptimizer.java
│   │   │
│   │   └── controller/
│   │       ├── GraphController.java                 # 🆕 图谱 API
│   │       ├── AssetController.java                 # 🆕 资产管理 API
│   │       ├── PlanController.java                  # 🆕 模板/规划 API
│   │       └── TemplateController.java              # 🆕 模板管理 API
│   │
│   └── src/main/resources/
│       ├── db/migration/                            # 🆕 Flyway 迁移脚本
│       │   ├── V2__workflow_templates.sql
│       │   ├── V3__knowledge_graph.sql
│       │   ├── V4__mechanism_operators.sql
│       │   └── V5__data_assets.sql
│       └── templates/                               # 🆕 Skill prompt 模板
│           ├── fault_plan_generation.md
│           ├── operation_check.md
│           └── power_flow_calculation.md
│
├── nari-mechanism-service/                          # 🆕 Python 机理微服务
│   ├── api_server.py
│   ├── operators/
│   │   ├── power_flow.py
│   │   ├── stability.py
│   │   ├── prediction.py
│   │   └── optimization.py
│   └── requirements.txt
│
├── web-ui/                                          # 🆕 前端可视化
│   ├── index.html
│   ├── app.js
│   └── styles.css
│
└── docker-compose.yml                               # 扩展：加入 nari-mechanism-service
```

---

## 八、风险与注意事项

1. **Mock 与真实的边界**：nari_demo 的机理算子目前是能力定义（非真实计算），整合到 GridOpsAgent 后必须明确标注 mock 状态，避免在生产环境误用。

2. **BGE-M3 依赖**：如果目标环境没有 BGE-M3 模型，模板路由和 RAG 需要降级为关键词匹配。建议将 Milvus 作为首选向量引擎，DashScope 的 embedding API 作为备选。

3. **Java ↔ Python 通信**：机理计算如果采用 Python 微服务，需要注意网络延迟、序列化开销和错误处理。建议加入 Resilience4j 的熔断和重试机制。

4. **知识图谱维护**：图谱节点从 nari_demo 导入后是一次性的。后续需要建立维护流程（新增节点 → 重建向量索引 → 验证）。

5. **即时规划的安全边界**：即时规划的结果未经过固化模板的验证，必须在 SafetyReview 节点强制标记为"需人工复核"。

---

## 九、总结

两个项目的整合本质上是 **"企业级 Agent 框架"（GridOpsAgent）吸收"领域知识资产"（nari_demo）** 的过程：

- **GridOpsAgent 贡献**：StateGraph 工作流引擎、安全合规审查、全链路可观测性、企业级持久化部署
- **nari_demo 贡献**：9 个固化工作流模板、18 个机理算子、71 节点知识图谱、2315 数据资产目录、模板路由与即时规划能力

整合后的 GridOpsAgent v2.0 将从一个"通用电力运维 Agent 框架"升级为"拥有完整领域知识体系和工作流模板的电网调度智能体平台"。
