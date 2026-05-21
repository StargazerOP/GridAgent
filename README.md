# GridOpsAgent

GridOpsAgent 是一个面向电网智能运维场景的 Multi-Agent 平台。项目基于 Spring Boot 3.2、Spring AI、Spring AI Alibaba Graph、MCP、RAG、MySQL、Redis 和 Milvus 构建，提供电力知识问答、设备状态查询、告警诊断、知识库上传、审批与可观测性等能力。

当前仓库是 Maven 多模块项目，核心代码是 Java；本地的 `nari_demo_test/` 是已忽略的 Python demo，不再作为仓库内容提交。

## 功能概览

- 智能对话：支持普通问答、SSE 流式输出、会话历史与上下文记忆。
- 告警诊断：围绕告警事件执行实体抽取、RAG 检索、计划生成、工具调用、证据校验、综合诊断和风险复核。
- 知识库：支持上传 `txt`、`md`、`pdf`、`docx`、`xlsx`、`html` 文档，自动切片、向量化和检索。
- MCP 工具：独立的电力工具服务暴露设备状态、告警历史、设备日志、缺陷工单、设备台账等查询工具。
- 安全与治理：包含 RBAC、审批、Hook、审计日志、输入校验、工具结果校验、Resilience4j 重试与熔断。
- 可观测性：提供健康检查、Trace 查询、Micrometer/Prometheus 指标暴露。

## 项目结构

```text
GridOpsAgent-main/
├── grid-ops-agent-app/          # 主应用，端口 9900
├── power-tools-mcp-server/      # MCP 工具服务，端口 9901
├── aiops-docs/                  # 示例电力知识文档，可上传到知识库
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
| 数据存储 | MySQL 8、Redis 7、Milvus |
| 文档解析 | Apache POI、PDFBox、Jsoup |
| 稳定性 | Resilience4j Retry / CircuitBreaker |
| 可观测性 | Spring Boot Actuator、Micrometer、Prometheus |

## 模块说明

| 模块 | 端口 | 说明 |
| --- | --- | --- |
| `power-tools-mcp-server` | `9901` | MCP Server，提供电力运维工具查询能力，默认启用 mock 数据。 |
| `grid-ops-agent-app` | `9900` | 主应用，负责 Web/API、Agent/Graph 编排、RAG、知识库、审批、审计和可观测性。 |

主应用通过 Spring AI MCP Client 连接 `power-tools-mcp-server`，应用层仍通过统一的 ToolCallback 调用工具，不需要直接感知底层 MCP 细节。

## 环境要求

- JDK 17+
- Maven 3.9+
- Docker Desktop
- 可用的 DeepSeek API Key，设置到 `DEEPSEEK_API_KEY`
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

### 3. 启动基础设施

MySQL 和 Redis：

```bash
docker run -d --name mysql-gridops -p 3307:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=power_aiops mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
docker run -d --name redis-gridops -p 6379:6379 redis:7-alpine
```

如果容器已存在但停止了：

```bash
docker start mysql-gridops redis-gridops
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

### 4. 编译项目

```bash
mvn clean compile
```

### 5. 启动 MCP 工具服务

必须先启动 MCP Server：

```bash
mvn -pl power-tools-mcp-server spring-boot:run
```

服务地址：

```text
http://localhost:9901
```

### 6. 启动主应用

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

### 7. 验证服务

```bash
curl http://localhost:9900/actuator/health
curl http://localhost:9900/milvus/health
curl http://localhost:9901/sse -H "Accept: text/event-stream"
```

主应用健康检查返回 `{"status":"UP"}` 即表示服务启动成功。

## Windows 后台启动示例

PowerShell 后台启动两个服务：

```powershell
Start-Process powershell -ArgumentList "mvn -pl power-tools-mcp-server spring-boot:run *> mcp-server.log"
Start-Sleep -Seconds 15
Start-Process powershell -ArgumentList "mvn -pl grid-ops-agent-app spring-boot:run *> server.log"
```

查看日志：

```powershell
Get-Content .\mcp-server.log -Wait
Get-Content .\server.log -Wait
```

## Linux / macOS / Git Bash 辅助命令

仓库提供 `Makefile`，适合 Bash 环境：

```bash
make help
make up       # 启动 Milvus
make start    # 后台启动 MCP Server 和主应用
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

## Agent 与流程

主应用采用 Graph 编排，整体流程如下：

```text
用户输入
  -> 输入校验与安全检查
  -> 加载会话记忆、技能和历史上下文
  -> RouterAgent 识别意图
  -> 路由到 KnowledgeQA / Diagnosis / Chat 子流程
  -> 安全复核、Hook、审计
  -> 输出最终响应并保存记忆
```

当前主要 Agent：

| Agent | 职责 |
| --- | --- |
| RouterAgent | 意图识别与路由。 |
| ToolAgent | 统一调用本地工具和 MCP 工具。 |
| AnalysisAgent | 多维数据分析。 |
| DiagnosisAgent | 生成结构化诊断报告。 |
| RiskReviewAgent | 风险复核与行动建议。 |

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
| MySQL | `3307` |
| Redis | `6379` |
| Milvus | `19530` |
| Attu | `8001` |

请停止占用端口的进程，或修改对应 `application.yml` / Docker Compose 映射。

## 停止服务

停止 Spring Boot 服务：在启动它们的终端中按 `Ctrl+C`。

停止容器：

```bash
docker rm -f mysql-gridops redis-gridops
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
