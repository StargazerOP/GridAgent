# GridOpsAgent

## 真实运行边界与成本说明

当前版本包含真实可运行链路、内置种子数据和 mock/estimate 能力三类内容，部署或演示前请按下面边界理解：

- **真实依赖 Docker 的能力**：主应用依赖 MySQL 保存会话、文档元数据、切片、处理任务、Trace 与工具调用日志；依赖 Milvus 保存 RAG 向量索引。Redis 已保留配置和依赖，面向后续运行缓存/分布式会话扩展；当前核心链路没有直接读写 Redis，未启动 Redis 时主应用仍可运行。MySQL 默认端口 `3307`，Redis 预留端口 `6379`，Milvus 默认端口 `19530`。
- **RAG 本地存储位置**：上传原文件保存到 `grid-ops-agent-app/uploads`；文档、切片和处理状态保存到 MySQL；向量默认写入本地 Docker Milvus collection `biz`。如果 Milvus 不可用，系统会退回内存向量库，页面会明确提示“当前使用内存向量库，重启会丢失”。
- **会消耗 DeepSeek token/额度的能力**：多 Agent 对话、路由、规划、诊断、风险复核和知识问答生成会使用 `DEEPSEEK_API_KEY`。默认配置下 RAG embedding 使用本地 BGE-M3 服务 `http://127.0.0.1:9910`，不会消耗 DeepSeek token。
- **Embedding 服务边界**：默认 embedding endpoint 是本地 OpenAI-compatible BGE-M3 服务，模型目录为 `E:\code\bge-m3`，端口为 `9910`，需要和 MCP Server、主应用一样单独启动。如果本地 BGE-M3 不可用，系统会启用 `LOCAL_HASH_FALLBACK` 本地哈希向量，保证上传、切片、入库、检索链路可验证；该模式只适合本地开发和连通性自检，不等同于生产级语义检索质量。
- **mock/estimate 能力**：MCP 电力工具默认返回模拟设备、告警、工单、台账数据；`calculatePowerFlowEstimate`、`checkOperationRisk`、`generateFaultScenario` 等算子会在返回中标注 mock/estimate 或数据来源，不代表真实 EMS/DTS/SCADA 计算结果。
- **nari 迁移内容边界**：`knowledge-organization` 中的流程模板、知识图谱节点和边是内置种子资源，用于任务匹配、图谱浏览和 Planner 上下文增强；不会启动原 Python demo 运行时。

GridOpsAgent 是一个面向电网智能运维场景的 Multi-Agent 平台。项目基于 Spring Boot 3.2、Spring AI、Spring AI Alibaba Graph、MCP、RAG、MySQL、Redis 和 Milvus 构建，提供电力知识问答、设备状态查询、告警诊断、知识库上传、流程模板匹配、知识拓扑浏览、即时规划、审批与可观测性等能力。

当前仓库是 Maven 多模块项目，核心代码是 Java。`nari_demo_test/` 是本地 Python 原型目录，已经从 Git 仓库中移除并加入忽略；其中的“流程模板、知识图谱、即时规划、资产目录”思路已经迁移到 Java 主应用的 `knowledge-organization` 资源、接口、前端页面和 Planner 上下文中。

## 功能概览

- 智能对话：支持普通问答、SSE 流式输出、会话历史与上下文记忆。
- 告警诊断：围绕告警事件执行实体抽取、RAG 检索、计划生成、工具调用、证据校验、综合诊断和风险复核。
- 知识库：支持上传 `txt`、`md`、`pdf`、`docx`、`xlsx`、`html` 文档，自动切片、向量化和检索。
- 知识组织：内置由 nari 原型迁移而来的工作流模板、工具节点、知识实体和关系图谱，可用于流程匹配和即时规划。
- MCP 工具：独立的电力工具服务暴露设备状态、告警历史、设备日志、缺陷工单、设备台账等查询工具。
- 安全与治理：包含 RBAC、审批、Hook、审计日志、输入校验、工具结果校验、Resilience4j 重试与熔断。
- 可观测性：提供健康检查、Trace 查询、Micrometer/Prometheus 指标暴露。

## 项目亮点

GridOpsAgent 不是一个简单的聊天接口包装，而是把电力运维流程拆成多个职责明确的 Agent、工具服务和 Graph 子流程：

- **Multi-Agent 协作**：RouterAgent、ToolAgent、AnalysisAgent、DiagnosisAgent、RiskReviewAgent 分别负责意图路由、工具调用、数据分析、诊断生成和风险复核。
- **Graph 显式编排**：基于 Spring AI Alibaba Graph 将输入校验、上下文加载、意图识别、子图执行、安全复核、记忆保存串成可观测流程。
- **Plan-Execute-Replan 诊断闭环**：告警诊断不是一次性问答，而是先规划排查步骤，再调用设备状态、告警历史、日志、工单、台账等工具收集证据，根据证据质量决定继续、重规划或降级。
- **RAG + ReAct 融合**：知识问答流程同时结合查询改写、混合检索、重排序、工具调用、回答质量评估和引用校验。
- **nari 知识组织迁移**：将原 Python 原型中的流程模板、工具/算子、知识实体、图谱关系迁移为 Java classpath 资源，主应用启动时加载。
- **MCP 工具隔离**：电力工具能力独立在 `power-tools-mcp-server`，主应用通过 MCP Client 调用，方便未来替换为真实 SCADA、PMS、工单、台账系统。
- **工程化安全机制**：输入校验、工具结果校验、证据质量评分、高风险审批、Hook、审计日志和 Resilience4j 共同约束 Agent 行为。
- **知识库构建能力**：支持多格式文档上传、切片、向量化、Milvus 存储、知识组织和版本管理。

## 当前系统版本

本版本可以理解为“Java 主系统 + nari 原型能力资源化迁移”的版本：

| 能力 | 当前实现 |
| --- | --- |
| LLM 接入 | 使用 Spring AI OpenAI 兼容接口，默认 DeepSeek。API Key 读取环境变量 `DEEPSEEK_API_KEY`。 |
| nari 工作流模板 | 已迁移到 `grid-ops-agent-app/src/main/resources/knowledge-organization/workflow_templates.json`，当前 9 个模板。 |
| nari 知识图谱 | 已迁移到 `nodes.json`、`edges.json`、`schema.json`，当前 71 个节点、255 条边。 |
| 即时规划 | Java 端 `/api/knowledge-org/instant-plan` 根据任务匹配模板、候选节点、推荐工具，并生成 `PlanStep`。 |
| Graph Planner 接入 | `ContextLoadNode` 注入 workflow context，`PlannerNode` 在生成诊断计划时使用模板、图谱和 Skill 上下文。 |
| 前端展示 | 主页面包含任务流程匹配、知识拓扑、流程编排、工具依赖、知识约束和当前任务子图视图。 |
| 工具/算子映射 | nari 原型中的 `graph_query`、`topology_analyzer`、`operator_power_flow_calculation` 等映射为 Java 工具。 |
| 工程边界 | 潮流估算、风险校核、故障场景生成等当前是 mock/estimate 能力，README 和工具返回会显式标注，不等同于真实 EMS/DTS 计算。 |

## 能力落地对账

下面这张表用于区分“已经真实落地”“当前是模板/预览”“当前是 mock/estimate”的能力边界：

| 能力 | 当前状态 | 说明 |
| --- | --- | --- |
| DeepSeek LLM 调用 | 已落地 | 通过 Spring AI OpenAI 兼容接口调用，API Key 读取环境变量 `DEEPSEEK_API_KEY`。 |
| Graph 主流程执行 | 已落地 | `/api/chat/stream-graph` 会实际运行 `pre_check -> context_load -> router -> 子图 -> safety_review -> final_response -> memory_save`，前端显示真实节点事件。 |
| Graph 节点耗时 | 已落地 | 后端在 `ObservedNodeAction` 统计单节点耗时，SSE 同时返回节点耗时和累计耗时。 |
| 任务编排台流程匹配 | 预览能力 | 调用 `/api/knowledge-org/instant-plan`，只做模板匹配和计划草案生成，不执行工具、不产生真实证据。 |
| nari 工作流模板 | 已落地为资源 | 9 个模板已迁移到 classpath 资源，供页面匹配和 Planner 上下文使用。 |
| nari 知识图谱 | 已落地为资源 | 71 个节点、255 条边已迁移到 classpath 资源，供拓扑页面和图谱工具查询。 |
| MCP 工具服务 | 已落地但默认 mock 数据 | `power-tools-mcp-server` 独立运行并暴露设备状态、告警历史、日志、工单、台账工具；当前默认返回模拟电力数据。 |
| RAG 文档上传与检索 | 已落地 | 文档上传、切片、向量检索、Milvus/内存向量存储链路已实现。检索质量取决于已上传文档和 embedding 可用性。 |
| Skill Registry | 已落地 | 内置 5 个 Skill，当前主要作为场景提示、推荐工具和诊断流程参考。 |
| 潮流计算 | mock/estimate | `calculatePowerFlowEstimate` 是估算演示，不是真实 EMS/DTS 潮流计算。 |
| 操作风险校核 | mock/estimate | `checkOperationRisk` 基于模板、图谱和规则提示生成风险结论，不是真实在线安全校核。 |
| 故障场景生成 | mock/estimate | `generateFaultScenario` 生成模拟场景，适合作为预案草案和复核线索。 |
| RBAC / 审批 / Hook / 审计 | 部分落地 | 代码中有服务、节点和接口，适合演示治理链路；生产级用户体系、权限数据和审批流仍需接入真实系统。 |
| 前端知识拓扑 | 已落地展示 | 可浏览流程编排、工具依赖、知识约束和当前任务子图。 |

因此，页面上的“任务编排台”是规划预览；“协同诊断会话”里的 Agent 执行轨迹才是实际 Graph 运行结果。

## 应用场景

| 场景 | 说明 |
| --- | --- |
| 电力安规问答 | 查询作业安全规程、操作要求和现场注意事项。 |
| 设备状态查询 | 查询变压器、开关柜、断路器等设备的实时状态、台账和运行记录。 |
| 告警分析 | 对告警事件做原因分析、影响范围判断和后续排查建议。 |
| 故障诊断 | 结合 RAG、实时数据、日志、工单和风险复核生成结构化诊断报告。 |
| 知识库管理 | 上传电力文档，构建可检索、可引用、可版本管理的运维知识库。 |
| 工具治理 | 对工具进行搜索、分类、高风险识别和统一调用。 |
| 流程编排展示 | 输入调度/运维任务后，匹配 nari 迁移来的工作流模板，展示候选流程、工具链和任务相关子图。 |

## 项目结构

```text
GridOpsAgent-main/
├── grid-ops-agent-app/          # 主应用，端口 9900
├── power-tools-mcp-server/      # MCP 工具服务，端口 9901
├── aiops-docs/                  # 示例电力知识文档，可上传到知识库
├── grid-ops-agent-app/src/main/resources/knowledge-organization/
│   ├── workflow_templates.json  # nari 迁移工作流模板
│   ├── nodes.json               # 知识组织节点
│   ├── edges.json               # 知识组织关系
│   └── schema.json              # 图谱 schema
├── pom.xml                      # Maven 父工程
├── docker-compose.yml           # Milvus 简化编排
├── vector-database.yml          # Milvus + Attu 编排
├── Makefile                     # Linux/macOS/Git Bash 下的辅助命令
└── README.md
```

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 语言与框架 | Java 17、Spring Boot 3.2.0、Maven |
| AI 与 Agent | Spring AI 1.1.0、Spring AI Alibaba 1.1.0.0-RC2、Graph 编排 |
| 大模型接入 | OpenAI 兼容接口，默认 DeepSeek：`DEEPSEEK_API_KEY` |
| 工具协议 | Spring AI MCP Client / MCP Server |
| 数据存储 | MySQL 8、Milvus；Redis 7 为预留缓存组件 |
| 文档解析 | Apache POI、PDFBox、Jsoup |
| 稳定性 | Resilience4j Retry / CircuitBreaker |
| 可观测性 | Spring Boot Actuator、Micrometer、Prometheus |

## 模块说明

| 模块 | 端口 | 说明 |
| --- | --- | --- |
| `scripts/bge_m3_embedding_server.py` | `9910` | 本地 BGE-M3 embedding 服务，提供 OpenAI-compatible `/v1/embeddings` 接口，模型目录默认 `E:\code\bge-m3`。 |
| `power-tools-mcp-server` | `9901` | MCP Server，提供电力运维工具查询能力，默认启用 mock 数据。 |
| `grid-ops-agent-app` | `9900` | 主应用，负责 Web/API、Agent/Graph 编排、RAG、知识库、审批、审计和可观测性。 |

主应用通过 Spring AI MCP Client 连接 `power-tools-mcp-server`，应用层仍通过统一的 ToolCallback 调用工具，不需要直接感知底层 MCP 细节。

## 系统架构

```text
┌─────────────────────────────────────────────────────────────┐
│ Controller 层                                                │
│ Chat / Alarm / Knowledge / Approval / Tools / Observability  │
├─────────────────────────────────────────────────────────────┤
│ Graph 编排层                                                 │
│ PowerOpsGraphConfig + StateGraph + 子图分发                  │
├─────────────────────────────────────────────────────────────┤
│ Multi-Agent 层                                               │
│ Router / Tool / Analysis / Diagnosis / RiskReview            │
├─────────────────────────────────────────────────────────────┤
│ RAG 与知识服务层                                             │
│ HybridSearch / Rerank / VectorSearch / KnowledgeGraph        │
├─────────────────────────────────────────────────────────────┤
│ 工具层                                                       │
│ 本地工具 + MCP Power Tools                                   │
├─────────────────────────────────────────────────────────────┤
│ 数据与基础设施                                               │
│ MySQL / Milvus / Docker / Redis（预留缓存）                    │
└─────────────────────────────────────────────────────────────┘
```

## Multi-Agent 设计

| Agent | 模式 | 职责 |
| --- | --- | --- |
| RouterAgent | 低温度意图分类 | 判断用户请求属于知识问答、故障诊断还是普通对话，并返回结构化路由结果。 |
| ToolAgent | ReAct + ToolCallback | 统一调用 MCP 工具和本地工具，屏蔽工具来源差异。 |
| AnalysisAgent | ReAct 分析 | 汇总多维运维数据，形成面向诊断的分析结论。 |
| DiagnosisAgent | ReAct 诊断 | 根据告警、证据和计划执行结果输出结构化诊断报告。 |
| RiskReviewAgent | ReAct 风险复核 | 判断风险等级，给出安全约束、审批建议和行动建议。 |

内置 Skill 当前包括主变油温异常诊断、开关柜局放异常诊断、安规条款查询、缺陷工单检查、配网线路跳闸抢修。Skill 用于给 Agent 注入业务场景提示、推荐工具和诊断流程参考。

### 主流程

```text
用户输入
  -> PreCheckNode：输入清洗、长度限制、安全检查
  -> ContextLoadNode：加载历史会话、记忆、Skill 和 nari 迁移工作流上下文
  -> RouterNode：调用 RouterAgent 做意图识别
  -> IntentDispatcher：分发到 KnowledgeQA / Diagnosis / Chat 子图
  -> SafetyReviewNode：安全复核、Hook 执行、审计记录
  -> FinalResponseNode：格式化最终响应
  -> MemorySaveNode：保存会话记忆
```

### 子图流程

| 子图 | 核心模式 | 关键节点 |
| --- | --- | --- |
| KnowledgeQA | RAG + ReAct + 回答评估循环 | QueryRewriteNode、RagRetrieveNode、ToolExecuteNode、RerankNode、ReactQaAgentNode、AnswerReviewNode、CitationCheckNode |
| Diagnosis | Plan-Execute-Replan + Evidence Validation | EntityExtractNode、AlarmRagRetrieveNode、PlannerNode、ExecutorNode、EvidenceValidationNode、DiagnosisNode、RiskAssessmentNode、ReplannerNode、ActionRecommendNode |
| Chat | RAG 增强对话 | ChatAgentNode |

KnowledgeQA 子图：

```text
QueryRewriteNode
  -> RagRetrieveNode
  -> ToolExecuteNode
  -> RerankNode
  -> ReactQaAgentNode
  -> AnswerReviewNode
      -> ACCEPT: CitationCheckNode
      -> NEED_MORE: 回到 RagRetrieveNode 重新检索
```

Diagnosis 子图：

```text
EntityExtractNode
  -> AlarmRagRetrieveNode
  -> PlannerNode
  -> ExecutorNode
  -> EvidenceValidationNode
  -> DiagnosisNode
  -> RiskAssessmentNode
  -> ReplannerNode
      -> CONTINUE: ActionRecommendNode
      -> REPLAN / FALLBACK: 回到排查流程或降级输出
```

### 诊断任务模型

诊断流程使用结构化任务对象表达，而不是只依赖自然语言上下文。nari 工作流模板匹配后，会被转换为可执行或可参考的 `PlanStep`，再由 `PlanValidator` 和实际可用工具进行归一化：

- `DiagnosisTask`：诊断任务元信息。
- `TaskPlan`：一次诊断计划。
- `PlanStep`：单个排查步骤，包含步骤类型、工具名、参数、预期结果、是否必需、重试次数等。
- `StepResult`：工具调用结果，记录状态、结果摘要、错误类型、是否可恢复、证据类型和耗时。

### 状态与校验

Graph 状态由 `GraphStateKeys`、`PowerOpsStateFactory` 和 `PowerOpsStateView` 统一管理，避免节点之间随意传递松散字段。关键状态包括：

| 状态 | 说明 |
| --- | --- |
| `intent` | RouterAgent 识别出的意图。 |
| `entities` | 查询改写或实体抽取得到的设备编号、告警类型、等级等信息。 |
| `rag_results` | RAG 检索结果。 |
| `plan_steps` | 诊断计划步骤。 |
| `step_results` | 工具执行结果，使用追加策略保存。 |
| `evidence_score` | 证据质量评分。 |
| `diagnosis_result` | 结构化诊断结果。 |
| `risk_level` | 风险复核等级。 |
| `final_response` | 最终输出。 |

关键校验器：

| 组件 | 作用 |
| --- | --- |
| InputValidator | 清洗输入、过滤危险片段、补齐任务和追踪信息。 |
| PlanValidator | 修复工具别名、限制计划步数、必要时回退默认诊断模板。 |
| ToolResultValidator | 校验工具返回是否为空、JSON 是否合法、业务字段是否完整。 |
| EvidenceQualityEvaluator | 对台账、实时状态、历史告警、日志、工单、安规等证据覆盖度评分。 |
| DiagnosisValidator | 检查诊断报告是否包含告警摘要、关键证据、原因、风险、建议和安全说明。 |

## nari 原型迁移内容

原 `nari_demo_test` 里的 Python 版本偏“流程编排原型和图谱展示”。当前 Java 版本没有把 Python 服务本身提交到仓库，而是吸收了其中的核心建模成果：

| nari 原型内容 | Java 主系统落点 |
| --- | --- |
| `data/workflow_templates.json` | `knowledge-organization/workflow_templates.json`，作为流程模板资源启动加载。 |
| `graph/nodes.json`、`graph/edges.json` | `knowledge-organization/nodes.json`、`edges.json`，用于知识拓扑、任务子图和 Planner 上下文。 |
| 流程检索 | `/api/knowledge-org/match` 和前端任务匹配区。 |
| 即时规划 | `/api/knowledge-org/instant-plan`，输出命中模板、候选节点、推荐工具和 `plan_steps`。 |
| 图谱浏览 | `/api/knowledge-org/graph` 和前端“知识拓扑”页面。 |
| 资产概览 | `/api/knowledge-org/overview` 汇总模板、节点、边、Skill、工具和文档数量。 |
| mock 潮流/稳定/故障算子 | `PowerAnalysisOperatorTools` 中的 `calculatePowerFlowEstimate`、`checkOperationRisk`、`generateFaultScenario` 等。 |

当前内置的 9 个流程模板包括：

| 场景 | 模板 |
| --- | --- |
| 故障处置 | 变电站全停故障处置、500kV 母线检修方式故障处置、新能源场站全场停电处置 |
| 操作校核 | 负荷转供操作校核、继电保护投退方案校核、检修方式 N-1 风险校核 |
| 潮流计算 | 潮流自动计算、断面潮流自动计算、运行方式调整后潮流越限校核 |

这些模板会参与两条链路：

- 页面链路：用户在首页任务区输入“负荷转供”“N-1 校核”“新能源场站全停”等任务，前端调用 `/api/knowledge-org/instant-plan`，展示命中流程、候选工具和当前任务子图。
- Agent 链路：`ContextLoadNode` 根据用户输入构建 workflow context，`PlannerNode` 在诊断计划生成时把它作为约束和参考，优先生成当前 Java 工具能够执行的步骤。

## 环境要求

- JDK 17+
- Maven 3.9+
- Docker Desktop
- 可用的 DeepSeek API Key，设置到 `DEEPSEEK_API_KEY`
- 本地 BGE-M3 embedding 模型目录：`E:\code\bge-m3`
- Python 3.10+，并安装 `fastapi`、`uvicorn`、`onnxruntime`、`tokenizers`、`numpy`，用于启动本地 BGE-M3 embedding 服务
- Windows PowerShell、Git Bash、WSL、Linux 或 macOS 终端均可运行

## 快速启动

### 1. 克隆并进入项目

```bash
git clone git@github.com:StargazerOP/GridAgent.git
cd GridAgent
```

如果你已经在本地目录：

```powershell
cd E:\code\电网agent项目\GridOpsAgent-main
```

### 2. 设置模型 API Key

PowerShell：

```powershell
$env:DEEPSEEK_API_KEY="你的 DeepSeek API Key"
```

Bash / Git Bash / WSL：

```bash
export DEEPSEEK_API_KEY="你的 DeepSeek API Key"
```

### 3. 启动系统（推荐方式）

推荐把 Docker 基础设施交给 Docker Desktop 管理，然后用脚本启动本地 BGE、MCP Server 和主应用。这是当前最稳的 Windows 启动方式。

先在 Docker Desktop 中启动下面这些必需容器：

| 组件 | 需要状态 | 端口 |
| --- | --- | --- |
| `mysql-gridops` | Running | `3307:3306` |
| `milvus-standalone` | Running / healthy | `19530:19530` |
| `milvus-minio` | Running / healthy | `9000:9000`、`9001:9001` |
| `milvus-etcd` | Running / healthy | 内部端口即可 |

可选容器：

| 组件 | 是否必需 | 说明 |
| --- | --- | --- |
| `milvus-attu` | 否 | Milvus Web 管理界面，常见地址是 `http://localhost:8001`，只用于查看 collection/schema/索引状态，不影响主应用运行。 |
| `redis-gridops` | 否 | 预留缓存组件，当前核心链路没有直接读写 Redis，停着也可以。 |

然后在项目根目录执行：

```powershell
.\scripts\start-gridops.ps1 -SkipDocker
```

这个命令只负责启动和检查本地运行组件：

| 步骤 | 说明 |
| --- | --- |
| 启动本地 BGE-M3 | 启动 `scripts\bge_m3_embedding_server.py`，端口 `9910`。 |
| 启动 MCP Server | 启动 `power-tools-mcp-server`，端口 `9901`。 |
| 启动主应用 | 启动 `grid-ops-agent-app`，端口 `9900`。 |
| 打开页面 | 默认打开 `http://localhost:9900/`。 |

日常启动建议固定使用 `-SkipDocker`。如果你希望脚本也尝试启动 Docker 组件，可以执行 `.\scripts\start-gridops.ps1`，但在部分 Windows / Docker Desktop 环境下，`docker compose up -d` 可能比手动在 Docker Desktop 中启动更慢或被 Docker warning 干扰。

常用参数：

```powershell
.\scripts\start-gridops.ps1 -SkipDocker      # 不启动 Docker 组件，只启动本地 BGE/MCP/主应用
.\scripts\start-gridops.ps1 -SkipDocker -NoBrowser  # 只启动，不自动打开浏览器
```

查看状态：

```powershell
.\scripts\status-gridops.ps1
```

正常状态示例：

```text
Main app          9900   True   HTTP 200
MCP server        9901   True
BGE-M3 embedding  9910   True   HTTP 200
MySQL             3307   True
Milvus            19530  True
```

如果 `Redis` 显示停止，不影响当前核心链路；如果 `Attu` 停止，只影响 Milvus Web 管理界面，不影响主应用连接 Milvus。

如果 Docker Desktop 里后来才启动 Milvus，而主应用已经先启动过，知识库运维页可能会显示“向量库异常 / 当前使用内存向量库”。这时重新执行：

```powershell
.\scripts\start-gridops.ps1 -SkipDocker
```

脚本会检测到“Milvus 已可用但主应用仍使用内存兜底”的状态，并自动重启 `9900` 主应用，让 RAG 切回 Milvus。

停止本地服务：

```powershell
.\scripts\stop-gridops.ps1
```

如果连 Docker 基础设施也要一起停掉：

```powershell
.\scripts\stop-gridops.ps1 -WithDocker
```

日志统一写入：

```text
logs/
```

### 4. 验证服务

```powershell
curl http://127.0.0.1:9910/health
curl http://localhost:9900/actuator/health
curl http://localhost:9900/api/knowledge/health
curl http://localhost:9900/milvus/health
```

主应用健康检查返回 `{"status":"UP"}` 即表示服务启动成功。

## 手动启动与排障步骤（可选）

下面的步骤用于脚本失败时定位问题；日常启动优先使用 `.\scripts\start-gridops.ps1`。

### 1. 启动基础设施

MySQL：

```bash
docker run -d --name mysql-gridops -p 3307:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=power_aiops mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

如果 MySQL 容器已存在但停止了：

```bash
docker start mysql-gridops
```

Redis 当前是预留缓存组件，主流程不强依赖。若希望按完整预留环境启动，可额外执行：

```bash
docker run -d --name redis-gridops -p 6379:6379 redis:7-alpine
```

如果 Redis 容器已存在但停止了：

```bash
docker start redis-gridops
```

启动 Milvus 和 Attu：

```bash
docker compose -f vector-database.yml up -d
```

Attu 管理界面：

```text
http://localhost:8001
```

如暂时不使用 Milvus，可在 `grid-ops-agent-app/src/main/resources/application.yml` 中将：

```yaml
milvus:
  enabled: false
```

### 2. 启动本地 BGE-M3 Embedding 服务

RAG 向量化默认调用本地 BGE-M3。该服务是独立进程，每次完整启动系统时都需要启动一次：

```powershell
python scripts\bge_m3_embedding_server.py
```

服务地址：

```text
http://127.0.0.1:9910
```

验证：

```powershell
curl http://127.0.0.1:9910/health
```

正常返回：

```json
{"status":"UP","model":"bge-m3","dimension":1024}
```

### 3. 编译项目

```bash
mvn clean compile
```

### 4. 启动 MCP 工具服务

必须先启动 MCP Server：

```bash
mvn -pl power-tools-mcp-server spring-boot:run
```

服务地址：

```text
http://localhost:9901
```

### 5. 启动主应用

另开一个终端：

```bash
mvn -pl grid-ops-agent-app spring-boot:run
```

主应用地址：

```text
http://localhost:9900
```

浏览器访问：

```text
http://localhost:9900/
```

页面主要区域：

| 页面/区域 | 用途 |
| --- | --- |
| 对话与诊断 | 调用 Chat、Graph 流式对话和告警诊断能力。 |
| 任务流程匹配 | 输入调度或运维任务，匹配 nari 迁移工作流模板并生成即时计划。 |
| 知识拓扑 | 查看流程模板、工具调用、知识实体之间的关系，可按流程编排、工具依赖、知识约束和当前任务子图过滤。 |
| 知识库管理 | 上传 `aiops-docs/` 或自定义文档，构建 RAG 检索索引。 |
| 观测与治理 | 查看工具、Skill、审批、Trace 等运行信息。 |

### 6. 验证服务

```bash
curl http://127.0.0.1:9910/health
curl http://localhost:9900/actuator/health
curl http://localhost:9900/api/knowledge/health
curl http://localhost:9900/milvus/health
curl http://localhost:9901/sse -H "Accept: text/event-stream"
```

主应用健康检查返回 `{"status":"UP"}` 即表示服务启动成功。

## Windows 脚本命令

推荐使用仓库内置脚本管理本地服务：

```powershell
.\scripts\start-gridops.ps1       # 启动完整演示环境
.\scripts\status-gridops.ps1      # 查看端口、健康检查和容器状态
.\scripts\stop-gridops.ps1        # 停止 BGE、MCP Server 和主应用
.\scripts\stop-gridops.ps1 -WithDocker  # 同时停止 MySQL、Redis、Milvus/Attu
```

查看日志：

```powershell
Get-Content .\logs\bge-m3-embedding.log -Wait
Get-Content .\logs\mcp-server.log -Wait
Get-Content .\logs\server.log -Wait
```

## Linux / macOS / Git Bash 辅助命令

仓库提供 `Makefile`，适合 Bash 环境：

```bash
make help
make up       # 启动 Milvus
make start    # 后台启动 MCP Server 和主应用；本地 BGE-M3 仍需按第 4 步单独启动
make upload   # 上传 aiops-docs 下的示例文档
make stop     # 停止 Spring Boot 服务
make down     # 停止 Milvus
```

`Makefile` 使用 `nohup`、`pkill`、`curl` 等命令，在纯 Windows PowerShell 下建议按前面的手动命令启动。

## 核心配置

主应用配置文件：

```text
grid-ops-agent-app/src/main/resources/application.yml
```

关键配置：

```yaml
server:
  port: 9900

spring:
  datasource:
    url: jdbc:mysql://localhost:3307/power_aiops
    username: ${MYSQL_USERNAME:root}
    password: ${MYSQL_PASSWORD:root}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat
      embedding:
        api-key: local-bge-m3
        base-url: http://127.0.0.1:9910
        options:
          model: bge-m3
    mcp:
      client:
        sse:
          connections:
            power-tools:
              url: http://localhost:9901

milvus:
  enabled: true
  host: localhost
  port: 19530
```

MCP 工具服务配置文件：

```text
power-tools-mcp-server/src/main/resources/application.yml
```

关键配置：

```yaml
server:
  port: 9901

spring:
  ai:
    mcp:
      server:
        enabled: true
        type: SYNC

power:
  mock-enabled: true
```

## 主要接口

| 功能 | 接口 |
| --- | --- |
| 同步对话 | `POST /api/chat` |
| 流式对话 | `POST /api/chat/stream` |
| Graph 流式对话 | `POST /api/chat/stream-graph` |
| 会话历史 | `GET /api/chat/history` |
| 会话列表 | `GET /api/chat/sessions` |
| 清空会话 | `POST /api/chat/clear` |
| 告警接收 | `POST /api/alarm/receive` |
| 告警诊断 | `POST /api/alarm/diagnose` |
| 诊断状态 | `GET /api/alarm/diagnose/{taskId}/status` |
| 恢复诊断任务 | `POST /api/alarm/resume/{taskId}` |
| 知识文档上传 | `POST /api/knowledge/documents/upload` |
| 知识文档列表 | `GET /api/knowledge/documents` |
| 知识检索测试 | `POST /api/knowledge/search/test` |
| 知识组织概览 | `GET /api/knowledge-org/overview` |
| 工作流模板列表 | `GET /api/knowledge-org/templates` |
| 知识组织图谱 | `GET /api/knowledge-org/graph` |
| 任务模板匹配 | `POST /api/knowledge-org/match` |
| 即时规划 | `POST /api/knowledge-org/instant-plan` |
| 工具列表 | `GET /api/tools/list` |
| 工具搜索 | `GET /api/tools/search` |
| 高风险工具 | `GET /api/tools/high-risk` |
| 技能列表 | `GET /api/skills` |
| 审批列表 | `GET /api/approval/pending` |
| Trace 查询 | `GET /api/observability/traces/{traceId}` |
| Milvus 健康检查 | `GET /milvus/health` |

## 调用示例

同步对话：

```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d "{\"question\":\"查询变压器 TR-110KV-001 的运行状态\"}"
```

上传知识文档：

```bash
curl -X POST http://localhost:9900/api/knowledge/documents/upload \
  -F "file=@aiops-docs/电力安规_变电部分.md" \
  -F "documentType=电力安规"
```

检索知识库：

```bash
curl -X POST http://localhost:9900/api/knowledge/search/test \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d "{\"query\":\"开关柜局放异常如何处理\",\"topK\":3}"
```

查看 nari 迁移知识组织概览：

```bash
curl http://localhost:9900/api/knowledge-org/overview
```

任务流程匹配与即时规划：

```bash
curl -X POST http://localhost:9900/api/knowledge-org/instant-plan \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d "{\"query\":\"对某 220kV 站负荷转供方案做 N-1 风险校核，并给出操作前复核项\"}"
```

查询知识组织图谱：

```bash
curl "http://localhost:9900/api/knowledge-org/graph?category=skill_process&q=潮流"
```

## MCP 工具清单

`power-tools-mcp-server` 默认使用 mock 数据，适合本地演示和开发。

| 工具 | 说明 |
| --- | --- |
| `getDeviceStatus` | 查询设备实时运行状态。 |
| `getAlarmHistory` | 查询历史告警。 |
| `getDeviceLogs` | 查询设备运行日志。 |
| `getDefectTickets` | 查询缺陷工单。 |
| `getDeviceProfile` | 查询设备台账。 |

主应用还包含本地工具，例如当前时间、内部文档查询、电力安规查询等。

## 本地工具和 nari 算子映射

除 MCP 工具外，主应用还注册了一组本地工具，用于承接 nari 原型中的图谱查询、拓扑分析和机理算子概念：

| 工具 | 说明 |
| --- | --- |
| `queryInternalDocs` | 查询已上传的 RAG 知识库。 |
| `searchSafetyRules` | 查询内置/mock 电力安规和运行规程。 |
| `queryKnowledgeGraph` | 查询 `knowledge-organization` 图谱资源。 |
| `analyzeTopology` | 基于图谱关系返回关联节点、边和薄弱环节提示。 |
| `calculatePowerFlowEstimate` | mock 潮流估算，用于流程演示和计划生成，不代表真实 EMS 计算。 |
| `checkOperationRisk` | mock 操作风险校核，用于 N-1、转供、检修等场景的风险提示。 |
| `generateFaultScenario` | mock 故障场景生成，用于故障处置预案和诊断规划。 |

这些工具会在 Planner 中作为可用工具出现。涉及 `mock` 或 `estimate` 的结果只能作为演示证据或人工复核线索，正式调度操作前必须接入真实业务系统并人工确认。

## 常见问题

### 主应用连接 MCP Server 报 404

确认先启动 `power-tools-mcp-server`，并检查主应用配置中 MCP 连接为：

```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            power-tools:
              url: http://localhost:9901
```

### MySQL 连接失败

检查容器是否运行：

```bash
docker ps | grep mysql-gridops
```

如果容器已存在但停止：

```bash
docker start mysql-gridops
```

### LLM 调用失败

检查 `DEEPSEEK_API_KEY` 是否设置、是否有效，以及 API 额度是否充足。

PowerShell：

```powershell
echo $env:DEEPSEEK_API_KEY
```

Bash：

```bash
echo $DEEPSEEK_API_KEY
```

### Embedding API 显示异常

默认 RAG embedding 调用本地 BGE-M3 服务。先确认 `9910` 端口服务已启动：

```powershell
curl http://127.0.0.1:9910/health
```

如果未启动，在项目根目录执行：

```powershell
python scripts\bge_m3_embedding_server.py
```

主应用健康面板中 `Embedding API` 应显示 `Local BGE-M3`、`bge-m3`、`1024` 维。如果 BGE-M3 服务不可用，系统会回退到 `LOCAL_HASH_FALLBACK`，页面会提示已启用本地兜底；此时链路可跑通，但语义检索质量不代表真实效果。

### Milvus 连接失败

检查容器：

```bash
docker compose -f vector-database.yml ps
```

重启 Milvus：

```bash
docker compose -f vector-database.yml up -d
```

如只想临时跑通主流程，可关闭 Milvus：

```yaml
milvus:
  enabled: false
```

### 端口冲突

默认端口：

| 服务 | 端口 |
| --- | --- |
| 主应用 | `9900` |
| MCP 工具服务 | `9901` |
| 本地 BGE-M3 Embedding 服务 | `9910` |
| MySQL | `3307` |
| Redis 预留缓存 | `6379` |
| Milvus | `19530` |
| Attu | `8001` |

请停止占用端口的进程，或修改对应 `application.yml` / Docker Compose 映射。

## 停止服务

停止 Spring Boot / BGE-M3 服务：在启动它们的终端中按 `Ctrl+C`。

PowerShell 中也可以按端口停止本地三个服务：

```powershell
Get-NetTCPConnection -LocalPort 9900,9901,9910 -State Listen |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -Force }
```

停止容器：

```bash
docker rm -f mysql-gridops
docker rm -f redis-gridops  # 如果启动了预留 Redis 容器
docker compose -f vector-database.yml down
```

如需保留数据库数据，不要删除 Docker volume；如需完全清理 Milvus 本地数据，可删除 `volumes/` 目录。

## 提交注意事项

`.gitignore` 已忽略常见运行产物和本地目录：

- Maven `target/`
- 日志 `*.log`
- 本地环境变量 `.env`
- 上传目录 `uploads/`
- Docker volume `volumes/`
- Python demo `nari_demo_test/`

提交前建议执行：

```bash
git status
git ls-files | grep -E "target/|\.log$|\.env$|nari_demo_test/"
```

第二条命令没有输出，说明这些本地运行文件没有被 Git 跟踪。
