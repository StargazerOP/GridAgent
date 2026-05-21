# 电网科学计算智能体流程编排平台说明

本项目是一个面向电网分析决策任务的本地演示平台。系统把“工作流模板、可调用工具、知识实体、数据资产”组织成可视化图谱，并提供任务检索、流程编排、即时规划和资产登记能力。

当前平台用于展示智能体如何基于“流程牵引、工具支撑、知识约束”组织任务。它不是已经接入真实调度系统的生产程序；其中模拟工具、兜底规划和未接入真实接口的能力都会按演示能力处理，正式使用前需要人工复核。

## 1. 快速安装指南

### 1.1 进入项目目录

```powershell
cd <project_root>
```

### 1.2 安装基础环境

基础 Web/API 服务、图谱展示、资产管理和本地兜底即时规划只依赖 Python 标准库。如果只运行网页演示，可以直接跳到 1.4。

如需使用 BGE-M3 向量检索、RAG 或模板向量路由，建议创建独立环境：

```powershell
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
```

不建议把依赖安装到 base 环境。

### 1.3 检查关键路径

关键路径在 [config.py](config.py) 中配置：

```text
项目根目录：<project_root>，也就是解压后的 demo_test 目录
工作区目录：<workspace_root>，默认是 <project_root> 的上一级目录
原始资料库：<workspace_root>\调规知识问答
BGE-M3 模型：由使用者自行下载，并在 config.py 中指定 BGE_M3_MODEL_DIR
LLM 接口：http://202.117.43.44:8848/v1
```

建议把新增资源按下面路径组织，后续也可以直接修改 [config.py](config.py) 改成自己的目录：

```text
<workspace_root>\调规知识问答\新增资料                    新增 Word/PDF/TXT/CSV/JSON 等原始资料
<workspace_root>\结题\nari_mechanism_data_knowledge_tree.txt  机理-数据-知识树文件
<任意本地模型目录>\bge-m3                                  使用者自行准备的 BGE-M3 模型目录
```

如果没有这些外部资料目录，网页主体仍可运行；文档扫描、RAG、数据资产表抽取会受影响。首次部署时建议先创建 `<workspace_root>\调规知识问答\新增资料`，把后续补充资料放进去。

BGE-M3 模型不包含在交付压缩包中。需要向量检索、RAG 或模板向量路由时，请在目标机器自行下载/放置模型，并在 [config.py](config.py) 中配置：

```python
from pathlib import Path

BGE_M3_MODEL_DIR = WORKSPACE_DIR / "models" / "bge-m3"
```

也可以沿用默认相对目录：

```python
BGE_M3_MODEL_DIR = WORKSPACE_DIR / "bge-m3"
```

如果目标机器没有 BGE-M3 或暂时连不上 LLM，网页仍可运行图谱、资产目录和本地兜底即时规划；BGE-M3 RAG、模板向量索引相关命令会不可用。

### 1.4 启动网页和 API

```powershell
python main.py serve --host 127.0.0.1 --port 8765
```

浏览器打开：

```text
http://127.0.0.1:8765/web_ui/index.html
```

如果端口被占用，可以换端口：

```powershell
python main.py serve --host 127.0.0.1 --port 8768
```

### 1.5 首次启动后建议检查

打开网页后依次查看：

```text
流程检索：输入调度任务，查看是否命中工作流模板
图谱知识库：查看完整图谱、节点卡片和节点详情
资产管理：查看工作流、Skill、机理/算子、文档、数据资产表
```

后端接口连接正常时，页面右上或状态区域会显示 API 已连接。LLM 不可用时，即时规划区域会显示具体错误，并自动使用本地兜底规划。

## 2. 目录结构

```text
demo_test/
  main.py                         命令行入口
  api_server.py                   网页 API 服务
  config.py                       路径、模型和 LLM 接口配置
  requirements.txt                可选增强能力依赖
  README_demo_test.md             当前说明文档
  plan_route_map_road_way_icon_143882.svg

  web_ui/
    index.html                    网页入口
    styles.css                    页面样式
    app.js                        前端交互、图谱绘制、即时规划调用

  data/
    demo_cases.json               内置演示案例
    workflow_templates.json       固化工作流模板
    custom_registry.json          新增资产登记结果
    README_data.md

  graph/
    nodes.json                    图谱节点
    edges.json                    图谱关系
    schema.json                   节点和关系类型说明
    README_graph.md

  tools/
    asset_registry.py             从树文件生成数据资产目录
    instant_planner.py            未命中模板时的即时规划
    registry_manager.py           资产管理后台和新增登记
    template_retriever.py         BGE-M3 工作流模板检索
    bge_rag.py                    BGE-M3 文档 RAG
    llm_client.py                 OpenAI 兼容 LLM 调用
    mock_power_check.py           潮流/越限模拟校核
    mock_stability_check.py       稳定/断面风险模拟校核
    ...

  scripts/
    rebuild_graph_taxonomy.js     重建图谱分类、节点和关系

  outputs/
    asset_registry.json           生成的数据资产目录
    run_logs/                     命令运行日志
    rag_index/                    BGE-M3 RAG 索引
    template_index/               工作流模板向量索引
```

几个最常改的文件：

```text
data/workflow_templates.json       增加或修改工作流模板
graph/nodes.json                   正式图谱节点
graph/edges.json                   正式图谱关系
scripts/rebuild_graph_taxonomy.js  批量重建图谱
data/custom_registry.json          网页或命令登记的新增资产
```

## 3. 如何新增各种资产

新增内容建议遵循统一流程：

```text
登记原始资产
-> 更新结构化配置
-> 重建图谱或索引
-> 在网页检查
-> 用命令行或页面做一次检索验证
```

### 3.1 新增文档资料

可通过两种方式接入文档：

1. 放入 `<workspace_root>\调规知识问答\新增资料`
2. 使用 `register-item` 登记外部路径

示例：

```powershell
python main.py register-item --kind document --name "新调规文档" --source-path "<资料目录>\新文档.docx" --description "用于补充调度规程依据"
```

登记结果保存到：

```text
data/custom_registry.json
```

如果需要语义检索，重建 RAG 索引：

```powershell
python main.py rag-build --max-files 80
```

如果需要从文档中沉淀图谱候选：

```powershell
python main.py build-graph --max-files 80
```

候选结果会写入：

```text
outputs/incremental_graph_candidates.json
```

该文件需要人工审核后再合并到正式图谱。

### 3.2 新增工作流模板

编辑 [data/workflow_templates.json](data/workflow_templates.json)，新增类似结构：

```json
{
  "template_id": "unique_template_id",
  "scene": "operation_check",
  "name": "模板名称",
  "description": "适用任务说明",
  "keywords": ["关键词1", "关键词2"],
  "slots": {},
  "workflow": [
    {
      "step": "步骤名称",
      "tool": "tool_or_operator_id",
      "params": {}
    }
  ]
}
```

然后同步图谱：

```powershell
node scripts/rebuild_graph_taxonomy.js
```

如果使用 BGE-M3 模板路由，再重建模板索引：

```powershell
python main.py template-build
```

注意：如果新增模板 ID，目前还需要在 [scripts/rebuild_graph_taxonomy.js](scripts/rebuild_graph_taxonomy.js) 的 `workflowNodeMap` 中补一条映射，否则重建图谱时无法生成对应流程节点。

### 3.3 新增 Skill

建议在 `skills/<skill_name>/` 下放置：

```text
skill.py
prompt.md
demos.json
```

然后：

1. 在 `main.py` 或后续 API 层登记调用入口。
2. 在图谱中登记该 skill 会使用的工具。
3. 写一个最小可运行样例。
4. 在资产管理页确认能看到该 skill。

### 3.4 新增机理工具或算子

机理类能力应放在：

```text
机理工具
  -> 机理算子大类
  -> 具体机理算子
  -> 机理模型工具
```

建议在 [scripts/rebuild_graph_taxonomy.js](scripts/rebuild_graph_taxonomy.js) 中维护正式图谱定义，再运行：

```powershell
node scripts/rebuild_graph_taxonomy.js
```

新增机理节点时至少写清：

```text
名称
说明
输入
输出
适用边界
父级算子或所属工具类型
依赖的数据资产或规则
是否已接入真实接口
```

如果只是演示占位，要明确说明“仅注册可调用能力，未真实执行计算”。

### 3.5 新增数据资产

优先更新树文件：

```text
<workspace_root>\结题\nari_mechanism_data_knowledge_tree.txt
```

然后运行：

```powershell
python main.py asset-build
```

当前阶段只要求登记数据资产目录和表名，不要求补真实表头。

### 3.6 新增数据驱动模型

数据驱动模型指模型能力，不是真实数据表。应登记为：

```text
数据驱动模型工具
```

建议写清：

```text
模型名称
输入
输出
训练或推理依赖的数据资产
适用场景
适用边界
是否已接入真实推理接口
```

并在图谱中通过：

```text
depends_on 关联数据资产
orchestrates 关联工作流
supports 关联算子或流程
```

### 3.7 网页登记入口

网页“资产管理”页提供新增项登记表。登记后会写入：

```text
data/custom_registry.json
```

它适合做资产台账登记；如果新增内容要参与图谱检索和流程编排，还需要同步修改 `workflow_templates.json`、`nodes.json`、`edges.json` 或 `rebuild_graph_taxonomy.js`。

## 4. 详细功能说明

### 4.1 流程检索

输入自然语言任务后，系统优先匹配 [data/workflow_templates.json](data/workflow_templates.json) 中的固化模板。命中后，页面会显示：

- 工作流步骤
- 每一步对应的工具或算子
- 关联图谱子图
- 相关节点卡片

当前有 9 个工作流模板：

```text
变电站全停故障处置流程
负荷转供操作校核流程
继电保护投退方案校核流程
检修方式 N-1 风险校核流程
500kV 母线检修方式故障处置流程
潮流自动计算流程
断面潮流自动计算流程
运行方式调整后潮流越限校核流程
新能源场站全场停电处置流程
```

### 4.2 未命中模板后的即时规划

当任务没有命中足够可信的固化模板时，系统会先返回可能相关的具体叶子节点，例如具体算子、模型工具、规则、经验或数据资产。

用户点击“开启即时规划”后：

```text
召回候选知识、工具和数据资产
-> 整理可用工具
-> 尝试调用后端 LLM
-> LLM 可用则返回临时规划
-> LLM 不可用则返回本地兜底规划
```

命令行测试：

```powershell
python main.py instant-plan "综合评估台风天气下新能源场站、送出线路和断面风险" --no-llm
python main.py instant-plan "综合评估台风天气下新能源场站、送出线路和断面风险"
```

说明：

- `--no-llm` 表示不调用 LLM，只使用本地兜底规划。
- 不加 `--no-llm` 时会调用 [tools/llm_client.py](tools/llm_client.py) 中配置的 LLM 服务。
- 即时规划结果没有写入正式模板，正式使用前必须人工复核。

### 4.3 完整图谱知识库

图谱位于 [graph](graph)。当前规模：

```text
总节点：71
关系边：255
流程节点 skill_process：9
工具节点 tool_call：41
知识实体 knowledge_entity：21
```

工具节点包括：

```text
机理工具
  - 机理算子大类：6
  - 机理叶子算子：18
  - 机理模型工具：2

数据驱动模型工具
  - 潮流快速估计模型工具
  - 气象与新能源出力预测模型工具

工程工具
  - 数据查询工具大类
  - 统一数据源查询工具
  - 图谱查询工具
  - 槽位解析、拓扑分析、预案召回、调规检索、RAG、报告生成等工具
```

机理叶子算子来自 `<workspace_root>\结题\nari_mechanism_data_knowledge_tree.txt` 中“机理”部分：

```text
潮流计算：牛顿拉夫逊、稀疏矩阵、最优潮流、连续潮流
稳定计算：偏微分方程、稳定性判别、稳定裕度计算
时间序列预测：外部影响因素、周期识别、趋势预测、随机波动生成
空间特征分析：拓扑相关性、聚类
故障场景生成：不确定性抽样、时空相关性生成
机组组合优化：整数优化、随机优化、鲁棒优化
```

### 4.4 资产管理

资产管理页用于统一查看和登记：

```text
工作流模板
Skills（当前 3 个，包含电网潮流计算场景）
机理/算子资产
数据驱动模型
文档资料
数据资产表（当前 2315 个）
```

点击资产行或图谱节点卡片可以查看详情。

### 4.5 数据资产目录

数据资产目录来自：

```text
<workspace_root>\结题\nari_mechanism_data_knowledge_tree.txt
```

这个文件中“数据”部分是真实数据资产目录。本项目会从树文件中抽取表名、编码、分类路径和说明，生成统一资产目录：

```powershell
python main.py asset-build
```

输出：

```text
outputs/asset_registry.json
```

当前抽取结果：

```text
数据资产类目：9
数据叶子资产/表：2315
```

这些资产当前只是登记为可通过 `统一数据源查询工具 tool_source_scanner` 模拟调用，暂不维护真实表头、数据库连接和字段级查询。

检索示例：

```powershell
python main.py asset-search --query 潮流 --top-k 10
python main.py asset-search --query 气象 --top-k 10
python main.py asset-search --query SG_CON_PWRGRID_R_TOPO --top-k 5
```

## 5. 图谱维护原则

图谱不是随意堆节点，建议保持以下约定：

- `skill_process`：只放可复用的工作流模板。
- `tool_call`：只放可被智能体选择或调用的能力。
- `knowledge_entity`：放设备、线路、厂站、规则、经验、文档、案例、数据资产等解释依据。
- `数据驱动模型工具` 与 `数据资产` 分开：前者是模型能力，后者是真实表/目录。
- 机理工具下允许保留“算子大类”，但即时规划候选应优先返回具体叶子算子。
- 所有模拟工具、占位工具、兜底规划都要明确标注，不要包装成真实执行结果。

常用关系：

```text
orchestrates：流程调用工具
contains：大类包含子类或叶子节点
depends_on：工具依赖数据、规则、对象或参数
constrains：知识约束流程
supports：工具、算子或知识提供支撑
retrieves：检索工具召回文档、规则或案例
mentions：文档或案例提及对象
connected_to：设备或对象之间有关联
```

## 6. 常用命令

启动服务：

```powershell
python main.py serve --host 127.0.0.1 --port 8765
```

查看内置案例：

```powershell
python main.py list
```

运行单个案例：

```powershell
python main.py run fault_aoti_blackout_plan --no-llm
```

运行模板路由：

```powershell
python main.py route "奥体变负荷转供调整后做潮流越限校核" --no-llm
```

扫描原始资料：

```powershell
python main.py scan-source --query 白云变全停 --top-k 5
```

生成数据资产目录：

```powershell
python main.py asset-build
```

检索数据资产：

```powershell
python main.py asset-search --query 气象 --top-k 10
```

即时规划：

```powershell
python main.py instant-plan "综合评估台风天气下新能源场站、送出线路和断面风险" --no-llm
```

构建 BGE-M3 文档 RAG 索引：

```powershell
python main.py rag-build --max-files 80
```

RAG 问答：

```powershell
python main.py rag-ask "奥体变全停后如何恢复重要用户供电？" --no-llm
```

构建工作流模板向量索引：

```powershell
python main.py template-build
```

重建图谱：

```powershell
node scripts/rebuild_graph_taxonomy.js
```

## 7. 部署和交付注意事项

- `outputs/asset_registry.json` 是从树文件生成的，可以重建。
- `outputs/run_logs/*.json` 是运行日志，可以删除后重新生成。
- `outputs/rag_index/` 和 `outputs/template_index/` 可能较大，交付时可按需要决定是否包含。
- 如果目标机器没有 BGE-M3，网页仍可展示图谱、资产目录和本地兜底即时规划。
- 如果目标机器连不上 LLM，页面会提示 LLM 不可用，并使用本地兜底规划。
- 真实调度操作、保护投退、风险评估、操作建议必须人工复核。

## 8. 常见问题

### 页面显示还是旧内容

可能是旧端口上的服务进程还在运行。换一个端口启动，或关闭旧服务后重启：

```powershell
python main.py serve --host 127.0.0.1 --port 8768
```

### LLM 一直返回类似的规划

先看页面提示。如果显示 LLM 不可用、HTTP 502 或连接超时，说明当前走的是本地兜底规划，不是 LLM 真实生成。

### 图谱节点太密

完整图谱页支持快捷键：

```text
Ctrl+-  缩小
Ctrl+=  放大
Ctrl+0  还原
```

### Windows 中文路径显示乱码

建议优先使用 ASCII 路径，例如：

```text
demo_workspace\demo_test
```

不要在脚本中硬编码旧的中文根目录。
