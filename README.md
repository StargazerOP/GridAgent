# GridOpsAgent

GridOpsAgent 是一个面向电网调度运维场景的多智能体诊断原型系统。当前版本围绕“告警输入到诊断报告生成”的过程化任务，集成了任务编排、多 Agent Graph 执行、工具调用、RAG 知识库、知识组织图谱、运行轨迹展示和汇报 PPT。

## 当前版本概览

本版本可以理解为：

```text
Spring Boot 主系统
+ Workflow / Skill / Agent 诊断资产层
+ 多 Agent Graph 诊断流程
+ 知识组织资源迁移
+ 本地 BGE-M3 embedding
+ MySQL / Milvus / Redis 基础设施
+ 可展示的学术进展汇报 HTML PPT
```

已完成的主要能力：

- 任务编排台：输入自然语言调度/运维任务，匹配流程模板、候选步骤、推荐工具和关联知识节点。
- 流程资产库：将处置经验沉淀为可复制、可编辑、可持久化的 Workflow，本地保存到 `data/workflows/user-workflows.json`。
- 技能中心：将设备状态查询、RAG 检索、图谱查询、拓扑分析、风险校核等能力作为 Skill 展示，并绑定到 Workflow 步骤。
- 运行诊断控制台：执行 Router、WorkflowContext、Entity、Planner、Tool、Knowledge、Evidence、Safety、Report 等多 Agent / 多节点流程。
- Workflow/Skill 执行闭环：Workflow 步骤同时记录业务 Skill 与底层可执行工具，运行诊断时由 Planner 转换为 `PlanStep`，Executor 记录 Skill 名称、工具结果、耗时和证据摘要。
- 主变油温规则校核：新增 `assessTransformerOilTempRisk`，基于演示状态量、油温阈值、负荷率、冷却器状态、历史告警和缺陷工单输出结构化风险校核结果。
- 知识拓扑：展示流程模板、工具能力和知识实体之间的关系。
- 知识库运维：支持文档上传、切片、向量化、检索测试、RAG 健康状态和一键自检。
- 主变油温异常示例：基于 `TR-110KV-001 油温 86℃ 超过 80℃阈值` 展示诊断流程、运行轨迹和诊断报告。
- 扩展示例：`500kV 母线检修方式下发生 N-1 故障，生成风险校核流程` 可展示 Workflow 匹配、图谱查询、故障场景生成和风险校核 Skill 调用。
- 汇报 PPT：提供当前阶段工作进展汇报 HTML PPT，位于 `outputs/GridOpsAgent_v9_academic_progress_report/`。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 前端展示 | HTML / CSS / JavaScript、SSE 流式事件展示、知识拓扑可视化 |
| 后端服务 | Java 17、Spring Boot 3.2、Spring AI、Spring AI Alibaba Graph |
| Workflow/Skill 资产 | 资源种子 Workflow、用户可编辑 Workflow、本地 JSON 持久化、Skill 能力视图 |
| Agent 编排 | Router / WorkflowContext / Entity / Planner / Tool / Knowledge / Evidence / Safety / Report |
| 模型调用 | DeepSeek OpenAI-compatible API，用于任务理解、规划和报告生成 |
| Embedding | 本地 BGE-M3 OpenAI-compatible embedding 服务，默认端口 `9910` |
| RAG | 文档上传、切片、向量化、Milvus 检索、内存向量库兜底 |
| 数据库 | MySQL 8，默认端口 `3307` |
| 向量库 | Milvus，默认端口 `19530` |
| 缓存/预留 | Redis 7，默认端口 `6379`，当前核心流程可不启动 |
| 工具服务 | MCP 工具服务 `power-tools-mcp-server`，默认端口 `9901` |

## 项目结构

```text
GridOpsAgent-main/
├─ grid-ops-agent-app/                         # 主应用，端口 9900
│  ├─ src/main/java/org/example/
│  │  ├─ graph/                                # Graph 编排与节点
│  │  ├─ agent/                                # Agent 逻辑
│  │  ├─ knowledge/                            # 知识组织服务
│  │  ├─ workflow/                             # Workflow/Skill 资产模型与服务
│  │  ├─ service/                              # RAG、MockData、流式服务
│  │  └─ controller/                           # Web/API 控制器
│  ├─ src/main/resources/knowledge-organization/
│  │  ├─ workflow_templates.json               # 流程模板
│  │  ├─ nodes.json                            # 知识组织节点
│  │  ├─ edges.json                            # 知识组织关系
│  │  └─ schema.json                           # schema
│  ├─ src/main/resources/mock-data/            # 当前演示用电力 mock 数据
│  └─ src/main/resources/static/index.html     # 主前端页面
├─ power-tools-mcp-server/                     # MCP 工具服务，端口 9901
├─ scripts/
│  ├─ start-gridops.ps1                        # 一键启动本地服务
│  ├─ status-gridops.ps1                       # 查看状态
│  ├─ stop-gridops.ps1                         # 停止服务
│  └─ bge_m3_embedding_server.py               # 本地 BGE-M3 embedding 服务
├─ docs/                                       # 系统文档和演示流程
├─ data/workflows/user-workflows.json          # 用户新增/复制的 Workflow 资产，首次保存后生成
├─ outputs/GridOpsAgent_v9_academic_progress_report/
│  ├─ index.html                               # 当前汇报 PPT
│  ├─ ppt.zip                                  # PPT 打包文件
│  ├─ PPT启动说明.md
│  └─ images/
├─ vector-database.yml                         # Milvus 编排
├─ docker-compose.yml
└─ README.md
```

## 快速启动

### 1. 准备 Docker 容器

推荐在 Docker Desktop 中先启动这些容器：

| 容器 | 端口 | 是否必需 |
| --- | --- | --- |
| `mysql-gridops` | `3307:3306` | 必需 |
| `milvus-standalone` | `19530:19530` | RAG 向量检索推荐启动 |
| `milvus-minio` | `9000:9000` / `9001:9001` | Milvus 依赖 |
| `milvus-etcd` | 内部端口 | Milvus 依赖 |
| `redis-gridops` | `6379:6379` | 当前核心流程非必需 |

也可以用命令启动：

```powershell
cd E:\code\电网agent项目\GridOpsAgent-main
docker start mysql-gridops
docker compose -f vector-database.yml up -d
```

如需 Redis：

```powershell
docker start redis-gridops
```

### 2. 设置 DeepSeek API Key

如果需要真实调用 LLM，设置环境变量：

```powershell
$env:DEEPSEEK_API_KEY="你的 DeepSeek API Key"
```

只查看页面、PPT、部分 mock 工具能力时可以不设置，但运行诊断和报告生成会受影响。

### 3. 启动本地服务

```powershell
cd E:\code\电网agent项目\GridOpsAgent-main
.\scripts\start-gridops.ps1 -SkipDocker
```

该脚本会启动：

- BGE-M3 embedding 服务：`http://127.0.0.1:9910`
- MCP 工具服务：`http://localhost:9901`
- GridOpsAgent 主应用：`http://localhost:9900`

启动完成后访问：

```text
http://localhost:9900/
```

### 4. 查看运行状态

```powershell
.\scripts\status-gridops.ps1
```

重点关注：

- `Main app 9900` 是否 `HTTP 200`
- `MCP server 9901` 是否 Listening
- `BGE-M3 embedding 9910` 是否 `HTTP 200`
- `MySQL 3307` 是否 Listening
- `Milvus 19530` 是否 Listening

### 5. 关闭服务

只关闭本地 Java / Python 服务：

```powershell
.\scripts\stop-gridops.ps1
```

连 Docker 基础设施一起关闭：

```powershell
.\scripts\stop-gridops.ps1 -WithDocker
```

## PPT 启动方式

当前汇报 PPT 是 HTML 横向翻页页面，不依赖后端服务。

目录：

```text
outputs/GridOpsAgent_v9_academic_progress_report/
```

方式一：直接打开 `index.html`。

方式二：用 Python 启动本地预览：

```powershell
cd E:\code\电网agent项目\GridOpsAgent-main\outputs\GridOpsAgent_v9_academic_progress_report
python -m http.server 8020
```

浏览器访问：

```text
http://localhost:8020/
```

关闭 PPT 预览服务：

```text
Ctrl + C
```

翻页方式：

- `→` / `PageDown` / 空格：下一页
- `←` / `PageUp`：上一页
- `ESC`：缩略图索引

## RAG 与数据边界

当前 RAG 链路已经支持本地运行：

- 上传原始文件保存到 `grid-ops-agent-app/uploads`
- 文档元数据、切片、处理状态保存到 MySQL
- 向量默认写入 Milvus collection
- Milvus 不可用时回退到内存向量库，页面会提示“当前使用内存向量库，重启会丢失”
- Embedding 默认使用本地 BGE-M3 服务，不消耗 DeepSeek token

一键 RAG 自检会执行：

```text
上传小测试文本 -> 等待切片与向量化 -> 检索验证 -> 返回展示用诊断结果
```

## Mock / Estimate 边界

当前系统用于阶段性演示和原型验证，部分数据和算子为 mock / estimate：

- MCP 电力工具默认返回模拟设备台账、状态、告警、日志、缺陷工单和安全规程数据。
- `calculatePowerFlowEstimate` 是潮流估算，不是真实 EMS/DTS 潮流计算。
- `checkOperationRisk` 是基于规则、mock 状态和知识图谱关系的风险推演，不是真实在线安全校核。
- `generateFaultScenario` 生成故障场景候选，用于预案推演和人工复核线索。
- 真实 EMS / DTS / SCADA / PMS / 工单系统接口仍需后续接入。

这些边界也已经在工具返回、页面提示和 PPT 的“完成情况与后续边界”中进行说明。

## 知识组织能力

当前版本迁入了知识组织资源，并进一步扩展为 Workflow / Skill / Agent 三层资产模型：

- `Workflow`：描述可治理的诊断或处置流程，包含适用场景、触发关键词、步骤顺序、证据要求和人工确认要求。
- `Skill`：描述可复用的专业能力单元，一个 Workflow 步骤可以绑定一个或多个 Skill，Skill 再调用工具、RAG、图谱或推演算子。
- `Agent Graph`：负责运行时动态执行 Workflow，完成实体抽取、计划生成、工具调用、证据复核、安全复核和报告生成。

系统不依赖原 Python demo 运行时。

主要资源位于：

```text
grid-ops-agent-app/src/main/resources/knowledge-organization/
```

提供接口：

| API | 作用 |
| --- | --- |
| `GET /api/knowledge-org/overview` | 模板、节点、边、技能、工具、文档统计 |
| `GET /api/knowledge-org/templates` | 流程模板列表 |
| `GET /api/knowledge-org/graph` | 知识组织图谱 |
| `POST /api/knowledge-org/match` | 自然语言任务匹配模板和候选节点 |
| `POST /api/knowledge-org/instant-plan` | 生成 GridOps 可执行计划草案 |

新增 Workflow/Skill 资产接口：

| API | 作用 |
| --- | --- |
| `GET /api/workflow-assets/overview` | Workflow、Skill、推演算子和治理能力概览 |
| `GET /api/workflow-assets/workflows` | 流程资产列表，包含资源种子和用户编辑流程 |
| `GET /api/workflow-assets/workflows/{id}` | 流程详情 |
| `POST /api/workflow-assets/workflows` | 新建可编辑 Workflow |
| `PUT /api/workflow-assets/workflows/{id}` | 更新可编辑 Workflow |
| `DELETE /api/workflow-assets/workflows/{id}` | 删除用户编辑 Workflow |
| `POST /api/workflow-assets/match` | 自然语言任务匹配 Workflow 并返回推荐 Skill |
| `GET /api/workflow-assets/skills` | Skill 能力中心 |
| `GET /api/workflow-assets/workflows/{id}/blueprint` | Workflow 到 Agent Graph 的执行蓝图 |

Graph 诊断流程中，`ContextLoadNode` 会加载 `WORKFLOW_SKILL_AGENT` 上下文，写入 `workflow_context` 和 `workflow_asset_context`；`PlannerNode` 会优先将 Workflow 步骤转换为当前系统可执行的 `PlanStep`，再由 `PlanValidator` 校验工具名、别名和可执行性。

主变油温异常主线已收口为：

```text
任务输入
→ 命中“主变油温异常诊断模板”
→ Workflow 步骤绑定设备台账查询、设备状态查询、历史告警检索、缺陷工单查询、规程检索、主变油温规则校核、诊断报告生成等 Skill
→ Agent Graph 执行底层工具并记录 StepResult
→ 最终报告引用状态量、告警/工单、规则校核、数据缺口和人工确认项
```

当前系统不接入真实电网控制接口，不直接执行设备操作；涉及降负荷、方式调整、转供或检修策略的内容只作为处置建议、工单草案或人工确认项输出。

## 当前前端页面

主页面包含七个工作区：

| 页面 | 作用 |
| --- | --- |
| 运行总览 | 查看 Workflow、Skill、推演算子、典型场景和系统运行入口 |
| 流程资产 | 查看资源种子流程，复制为可编辑 Workflow，保存用户流程 |
| 技能中心 | 查看 Skill、推荐工具、适用场景、绑定流程和推演边界 |
| 任务编排台 | 任务输入、Workflow 匹配、Skill 推荐、计划预览、关联图谱 |
| 运行诊断控制台 | Graph 执行轨迹、工具调用、RAG 检索、诊断报告 |
| 知识拓扑 | 流程模板、工具能力、知识实体关系浏览 |
| 知识库运维 | 文档上传、RAG 状态、自检、文档管理 |

后台知识库管理能力已整合为统一风格，不再跳转到旧蓝色页面作为主要入口。

## 测试

后端单元测试：

```powershell
mvn -pl grid-ops-agent-app test
```

推荐重点验证：

```text
KnowledgeOrganizationServiceTest
WorkflowAssetServiceTest
ToolResultValidatorTest
EvidenceQualityEvaluatorTest
```

如果首次运行时 Maven 需要下载 Spring Boot parent 或依赖，请保持网络可用。

## 常见问题

### Embedding API 显示异常怎么办？

确认本地 BGE-M3 服务是否启动：

```powershell
curl http://127.0.0.1:9910/health
```

如果未启动，运行：

```powershell
.\scripts\start-gridops.ps1 -SkipDocker
```

### Redis 一定要启动吗？

当前核心演示流程不直接依赖 Redis。README 和脚本保留 Redis 是为了后续缓存、会话和分布式能力扩展。需要测试 Redis 时可以启动 `redis-gridops`。

### Attu 一定要启动吗？

不需要。Attu 只是 Milvus 的可视化管理工具，不影响 GridOpsAgent 主流程。

### Milvus 不启动可以吗？

可以运行，但 RAG 会退回内存向量库，重启后向量会丢失。正式展示 RAG 上传到检索流程时，建议启动 Milvus。

## GitHub 中的 PPT

最终 PPT 已随项目提交：

```text
outputs/GridOpsAgent_v9_academic_progress_report/index.html
outputs/GridOpsAgent_v9_academic_progress_report/ppt.zip
```

如果从 GitHub 下载项目，可以直接打开 `index.html`，或解压 `ppt.zip` 后用 Python 启动本地服务查看。
