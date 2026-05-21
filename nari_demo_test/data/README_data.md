# data 目录说明

本目录保存 demo 的轻量结构化数据。

- `demo_cases.json`：操作校核、潮流计算、故障处置三个场景下的演示任务。
- `entities.json`：演示用电网实体，包括变电站、主变、风电场、馈线和重要用户。
- `contingency_plans.json`：结构化事故预案摘要，用于快速模板召回。
- `regulations.json`：结构化调规/继保/安全校核依据摘要。
- `workflow_templates.json`：技能与流程节点的参数化落地形式，目前包含 9 个模板，用于 BGE-M3 模板检索、槽位填充、变体任务路由和工具编排。

注意：

这些数据是演示层的“精选索引”。系统同时支持通过 `tools/source_scanner.py` 实时扫描 `config.py` 中配置的原始资料库，默认是 `<workspace_root>\调规知识问答`，因此原始文件夹更新后也可以被检索到。路径可在项目根目录的 `config.py` 中修改。

当前图谱建模口径是“流程牵引、工具支撑、知识约束”：

- `workflow_templates.json` 对应技能与流程节点；
- `entities.json`、`contingency_plans.json`、`regulations.json` 对应知识实体节点的精选索引；
- `tools/` 下的可执行模块对应工具调用节点。
