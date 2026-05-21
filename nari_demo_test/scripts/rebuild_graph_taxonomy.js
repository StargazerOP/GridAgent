const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const graphDir = path.join(root, "graph");
const templates = JSON.parse(fs.readFileSync(path.join(root, "data/workflow_templates.json"), "utf8"));
const currentNodes = JSON.parse(fs.readFileSync(path.join(graphDir, "nodes.json"), "utf8"));

const byId = Object.fromEntries(currentNodes.map((node) => [node.id, node]));

function node(id, name, category, role, description, extra = {}) {
  return { id, name, category, role, description, ...extra };
}

function existing(id, fallback) {
  const value = byId[id];
  if (!value || String(value.name || "").includes("?")) return fallback;
  return value;
}

const workflowNodeMap = {
  fault_substation_blackout: ["workflow_fault_substation_blackout", "变电站全停故障处置流程"],
  operation_load_transfer_check: ["workflow_operation_load_transfer_check", "负荷转供操作校核流程"],
  operation_protection_check: ["workflow_operation_protection_check", "继电保护投退方案校核流程"],
  operation_n_minus_one_check: ["workflow_operation_n_minus_one_check", "检修方式N-1风险校核流程"],
  fault_500kv_bus_fault: ["workflow_fault_500kv_bus_fault", "500kV母线检修方式故障处置流程"],
  power_flow_auto_calculation: ["workflow_power_flow_auto_calculation", "潮流自动计算流程"],
  power_flow_snapshot_calculation: ["workflow_power_flow_snapshot_calculation", "断面潮流自动计算流程"],
  power_flow_operation_adjustment_check: ["workflow_power_flow_operation_adjustment_check", "运行方式调整后潮流越限校核流程"],
  fault_wind_farm_blackout: ["workflow_fault_wind_farm_blackout", "新能源场站全场停电处置流程"],
};

const fallbackNodes = {
  operator_power_flow_calculation: node("operator_power_flow_calculation", "潮流计算算子", "tool_call", "operator", "包含牛顿拉夫逊算子、稀疏矩阵计算算子、最优潮流算子和连续潮流算子等，用于根据网络拓扑、设备参数和运行方式计算潮流分布、节点电压和线路负载。", {
    tool_subtype: "mechanism_operator",
    operator_level: "family",
    examples: ["牛顿拉夫逊算子", "稀疏矩阵计算算子", "最优潮流算子", "连续潮流算子"],
    inputs: ["网络拓扑", "设备参数", "运行方式", "负荷和出力"],
    outputs: ["节点电压", "线路潮流", "越限信息", "潮流收敛状态"],
  }),
  operator_stability_calculation: node("operator_stability_calculation", "稳定计算算子", "tool_call", "operator", "包含偏微分方程算子、稳定性判别算子和稳定裕度计算算子等，用于评估暂态稳定、频率安全、断面安全和控制裕度。", {
    tool_subtype: "mechanism_operator",
    operator_level: "family",
    examples: ["偏微分方程算子", "稳定性判别算子", "稳定裕度计算算子"],
    inputs: ["故障场景", "运行断面", "控制策略", "稳定约束"],
    outputs: ["稳定风险等级", "稳定裕度", "控制建议", "人工复核项"],
  }),
  operator_time_series_prediction: node("operator_time_series_prediction", "时间序列预测算子", "tool_call", "operator", "包含外部影响因素算子、周期识别算子、趋势预测算子和随机波动生成算子等，用于刻画负荷、新能源出力、气象和运行状态随时间变化的规律。", {
    tool_subtype: "mechanism_operator",
    operator_level: "family",
    examples: ["外部影响因素算子", "周期识别算子", "趋势预测算子", "随机波动生成算子"],
    inputs: ["历史时序数据", "气象因素", "节假日和周期信息", "运行状态"],
    outputs: ["预测曲线", "波动范围", "风险提示", "置信区间"],
  }),
  operator_spatial_feature_analysis: node("operator_spatial_feature_analysis", "空间特征分析算子", "tool_call", "operator", "包含拓扑相关性算子和聚类算子等，用于分析电网拓扑、区域耦合、设备关联和故障传播路径。", {
    tool_subtype: "mechanism_operator",
    operator_level: "family",
    examples: ["拓扑相关性算子", "聚类算子"],
    inputs: ["电网拓扑", "设备关系", "区域分区", "运行特征"],
    outputs: ["关联设备集合", "区域聚类结果", "传播路径", "影响范围"],
  }),
  operator_fault_scenario_generation: node("operator_fault_scenario_generation", "故障场景生成算子", "tool_call", "operator", "包含不确定性抽样算子和时空相关性生成算子等，用于生成检修、故障、波动和极端天气等候选场景。", {
    tool_subtype: "mechanism_operator",
    operator_level: "family",
    examples: ["不确定性抽样算子", "时空相关性生成算子"],
    inputs: ["设备状态", "历史故障", "气象风险", "运行方式"],
    outputs: ["候选故障场景", "扰动组合", "风险排序", "仿真输入"],
  }),
  operator_unit_commitment_optimization: node("operator_unit_commitment_optimization", "机组组合优化算子", "tool_call", "operator", "包含整数优化算子、随机优化算子和鲁棒优化算子等，用于发用电平衡、机组启停、备用安排和新能源消纳优化。", {
    tool_subtype: "mechanism_operator",
    operator_level: "family",
    examples: ["整数优化算子", "随机优化算子", "鲁棒优化算子"],
    inputs: ["负荷预测", "新能源预测", "机组约束", "备用需求"],
    outputs: ["机组组合方案", "出力计划", "备用安排", "优化目标值"],
  }),
  entity_aoti_substation: node("entity_aoti_substation", "奥体变", "knowledge_entity", "entity", "110kV变电站，演示中的全停事故、所用电恢复和重要用户供电恢复对象。"),
  entity_fucheng_substation: node("entity_fucheng_substation", "富城变", "knowledge_entity", "entity", "与奥体变相关的220kV变电站，可作为部分转供来源。"),
  entity_jiaqing_substation: node("entity_jiaqing_substation", "嘉庆变", "knowledge_entity", "entity", "与奥体变重要用户恢复相关的变电站。"),
  entity_sanguandian_substation: node("entity_sanguandian_substation", "三官殿变", "knowledge_entity", "entity", "500kV母线检修方式下故障处置演示对象。"),
  entity_dongshanqiao_transformer: node("entity_dongshanqiao_transformer", "东善桥2号主变", "knowledge_entity", "entity", "N-1检修方式风险校核演示对象。"),
  doc_aoti_blackout_plan: node("doc_aoti_blackout_plan", "110千伏奥体变全停事故处理预案", "knowledge_entity", "case", "包含奥体变全停影响、恢复所用电、恢复重要用户和汇报地调步骤的历史预案案例。"),
  doc_sanguandian_bus_fault_plan: node("doc_sanguandian_bus_fault_plan", "三官殿500kV母线检修方式下故障事故预案", "knowledge_entity", "case", "包含500kV母线故障、主变失去、分区发用电平衡和机组出力调整的预案案例。"),
  rule_power_flow_constraint: node("rule_power_flow_constraint", "潮流与线路热稳约束", "knowledge_entity", "rule", "操作后潮流、电流、电压应满足设备安全约束，是潮流计算和越限校核工具的重要边界。"),
  rule_n_minus_one: node("rule_n_minus_one", "N-1安全约束", "knowledge_entity", "rule", "检修或故障后应关注单一元件再故障引起的供电和稳定风险。"),
  rule_dispatch_compliance: node("rule_dispatch_compliance", "调度操作合规约束", "knowledge_entity", "rule", "操作方案应包含许可、保护投退、重要用户保障、汇报和恢复方式等调度合规要素。"),
  experience_dispatch_adjustment: node("experience_dispatch_adjustment", "调度员调整经验", "knowledge_entity", "experience", "调度员长期积累的经验可影响流程选择、工具调用和结果校核，例如调整机组出力、改变潮流分布、控制断面负载和安排储能充放电。"),
  rule_manual_review: node("rule_manual_review", "人工复核要求", "knowledge_entity", "rule", "凡涉及真实调度操作、保护投退、重要用户恢复和安全边界变化的结果，均需人工复核。"),
};

const mechanismLeafOperators = [
  {
    parent: "operator_power_flow_calculation",
    id: "operator_newton_raphson_power_flow",
    name: "牛顿拉夫逊算子",
    description: "用于电力系统潮流计算的非线性方程迭代求解算子，适合常规交流潮流方程求解和收敛性判断。",
    inputs: ["节点导纳矩阵", "节点注入功率", "平衡节点", "初始电压"],
    outputs: ["节点电压", "相角", "潮流收敛状态"],
  },
  {
    parent: "operator_power_flow_calculation",
    id: "operator_sparse_matrix_solver",
    name: "稀疏矩阵计算算子",
    description: "支撑潮流计算中大规模稀疏线性方程组高效求解的算子，用于雅可比矩阵构造、分解和迭代修正。",
    inputs: ["稀疏雅可比矩阵", "功率不平衡量", "迭代精度"],
    outputs: ["修正量", "线性方程求解状态", "矩阵条件提示"],
  },
  {
    parent: "operator_power_flow_calculation",
    id: "operator_optimal_power_flow",
    name: "最优潮流算子",
    description: "实现考虑经济性与安全约束的最优潮流求解算子，用于发电出力、无功、电压和断面约束协同优化。",
    inputs: ["网络拓扑", "发电成本", "负荷预测", "安全约束"],
    outputs: ["优化出力", "节点电压", "约束裕度", "目标函数值"],
  },
  {
    parent: "operator_power_flow_calculation",
    id: "operator_continuation_power_flow",
    name: "连续潮流算子",
    description: "用于分析系统接近电压稳定极限的连续潮流追踪算子，支撑负荷增长路径和电压崩溃临界点分析。",
    inputs: ["基态潮流", "负荷增长方向", "控制变量", "追踪步长"],
    outputs: ["PV曲线", "临界负荷点", "电压稳定裕度"],
  },
  {
    parent: "operator_stability_calculation",
    id: "operator_partial_differential_equation",
    name: "偏微分方程算子",
    description: "支撑电力系统动态过程建模与仿真的偏微分方程求解算子，用于连续动态过程和设备响应刻画。",
    inputs: ["动态模型参数", "初始状态", "扰动条件", "仿真步长"],
    outputs: ["状态轨迹", "动态响应曲线", "数值稳定性提示"],
  },
  {
    parent: "operator_stability_calculation",
    id: "operator_stability_discrimination",
    name: "稳定性判别算子",
    description: "基于特征值或时域响应判定系统稳定性的核心算子，用于小扰动稳定、暂态稳定和频率安全初筛。",
    inputs: ["线性化模型", "时域响应", "稳定判据", "故障清除时间"],
    outputs: ["稳定/失稳判别", "风险等级", "关键模态或关键设备"],
  },
  {
    parent: "operator_stability_calculation",
    id: "operator_stability_margin",
    name: "稳定裕度计算算子",
    description: "量化系统小干扰或暂态稳定裕度的关键计算算子，用于断面控制、备用安排和风险预警。",
    inputs: ["运行断面", "稳定约束", "候选故障", "控制策略"],
    outputs: ["稳定裕度", "断面限额建议", "控制裕度"],
  },
  {
    parent: "operator_time_series_prediction",
    id: "operator_external_factor_fusion",
    name: "外部影响因素算子",
    description: "融合气象、负荷、节假日和运行状态等外部变量提升预测精度的算子。",
    inputs: ["历史时序", "气象变量", "节假日标签", "运行状态"],
    outputs: ["融合特征", "影响因子权重", "预测修正量"],
  },
  {
    parent: "operator_time_series_prediction",
    id: "operator_period_identification",
    name: "周期识别算子",
    description: "自动识别负荷或新能源出力中多尺度周期特征的算子，支撑日前、日内和超短期预测。",
    inputs: ["历史时序数据", "采样间隔", "候选周期"],
    outputs: ["周期成分", "周期强度", "异常周期提示"],
  },
  {
    parent: "operator_time_series_prediction",
    id: "operator_trend_prediction",
    name: "趋势预测算子",
    description: "提取并外推时间序列长期变化趋势的核心预测算子，用于负荷、新能源出力和运行状态趋势判断。",
    inputs: ["历史曲线", "外部变量", "预测时域"],
    outputs: ["趋势曲线", "预测区间", "趋势变化点"],
  },
  {
    parent: "operator_time_series_prediction",
    id: "operator_random_fluctuation_generation",
    name: "随机波动生成算子",
    description: "模拟不确定性扰动下随机波动场景的生成算子，用于新能源波动、负荷扰动和风险场景扩展。",
    inputs: ["概率分布", "波动边界", "相关性参数", "场景数量"],
    outputs: ["随机波动场景", "置信区间", "极端样本"],
  },
  {
    parent: "operator_spatial_feature_analysis",
    id: "operator_topology_correlation",
    name: "拓扑相关性算子",
    description: "刻画电网元件间拓扑连接关系及其电气相关性的算子，用于影响范围分析、薄弱环节识别和故障传播判断。",
    inputs: ["电网拓扑", "设备关系", "电气距离", "运行断面"],
    outputs: ["相关设备集合", "电气关联强度", "传播路径"],
  },
  {
    parent: "operator_spatial_feature_analysis",
    id: "operator_clustering",
    name: "聚类算子",
    description: "用于空间特征分析的聚类计算机制，支持负荷、拓扑、设备状态等多维数据分组识别。",
    inputs: ["空间特征", "运行特征", "聚类参数"],
    outputs: ["聚类结果", "区域分组", "异常簇提示"],
  },
  {
    parent: "operator_fault_scenario_generation",
    id: "operator_uncertainty_sampling",
    name: "不确定性抽样算子",
    description: "面向故障场景生成的不确定性建模与抽样机制，支撑概率性风险评估和候选扰动生成。",
    inputs: ["不确定变量", "概率分布", "运行方式", "抽样规模"],
    outputs: ["候选场景集", "概率权重", "风险排序"],
  },
  {
    parent: "operator_fault_scenario_generation",
    id: "operator_spatiotemporal_correlation_generation",
    name: "时空相关性生成算子",
    description: "构建故障事件时空关联关系的生成机制，提升场景真实性与覆盖度，适合极端天气和连锁故障分析。",
    inputs: ["历史故障", "气象风险", "设备空间关系", "时间窗口"],
    outputs: ["时空关联场景", "关联强度", "连锁风险提示"],
  },
  {
    parent: "operator_unit_commitment_optimization",
    id: "operator_integer_optimization",
    name: "整数优化算子",
    description: "求解机组启停决策的整数规划优化机制，保障调度方案可行性。",
    inputs: ["机组启停约束", "负荷预测", "备用需求", "成本参数"],
    outputs: ["启停计划", "出力安排", "可行性状态"],
  },
  {
    parent: "operator_unit_commitment_optimization",
    id: "operator_stochastic_optimization",
    name: "随机优化算子",
    description: "应对预测不确定性的随机优化机制，支持含新能源的机组组合和备用安排。",
    inputs: ["随机场景", "新能源预测", "负荷预测", "机会约束"],
    outputs: ["随机优化方案", "期望成本", "风险约束满足情况"],
  },
  {
    parent: "operator_unit_commitment_optimization",
    id: "operator_robust_optimization",
    name: "鲁棒优化算子",
    description: "基于不确定集的鲁棒优化机制，确保最坏场景下机组组合安全经济。",
    inputs: ["不确定集", "机组约束", "安全约束", "备用需求"],
    outputs: ["鲁棒机组组合", "最坏场景裕度", "保守性评估"],
  },
].map((item) => node(item.id, item.name, "tool_call", "operator", item.description, {
  tool_subtype: "mechanism_operator",
  operator_level: "leaf",
  parent_operator: item.parent,
  inputs: item.inputs,
  outputs: item.outputs,
}));

const nodes = [];
for (const template of templates) {
  const [id, name] = workflowNodeMap[template.template_id];
  nodes.push(node(id, name, "skill_process", "workflow", template.description, {
    template_id: template.template_id,
    scene: template.scene,
    keywords: template.keywords || [],
    typical_steps: (template.workflow || []).map((step) => step.step),
  }));
}

nodes.push(
  node("tool_mechanism_tool_group", "机理工具", "tool_call", "tool_group", "面向电网物理机理、约束校核和分析决策的可调用工具集合，下设机理模型工具、机理算子大类和具体机理算子。", { tool_subtype: "mechanism_tool_group" }),
  node("tool_mechanism_operator_group", "机理算子大类", "tool_call", "operator_group", "用于组织潮流计算、稳定计算、时间序列预测、空间特征分析、故障场景生成和机组组合优化等可调用机理算子。", { tool_subtype: "mechanism_operator_group" }),
  fallbackNodes.operator_power_flow_calculation,
  fallbackNodes.operator_stability_calculation,
  fallbackNodes.operator_time_series_prediction,
  fallbackNodes.operator_spatial_feature_analysis,
  fallbackNodes.operator_fault_scenario_generation,
  fallbackNodes.operator_unit_commitment_optimization,
  ...mechanismLeafOperators,
  node("tool_mock_power_check", "潮流与越限校核工具", "tool_call", "model_tool", "以可替换接口形式模拟线路载流量、电压、转供风险、N-1和越限校核，正式系统可替换为课题2潮流计算或在线安全校核算子。", { tool_subtype: "mechanism_model_tool" }),
  node("tool_mock_stability_check", "稳定与断面风险评估工具", "tool_call", "model_tool", "以可替换接口形式模拟稳定、断面、频率和发用电平衡风险，正式系统可替换为课题2稳定分析和安全校核工具。", { tool_subtype: "mechanism_model_tool" }),
  node("tool_data_driven_model_group", "数据驱动模型工具", "tool_call", "tool_group", "由运行数据、仿真数据或外部环境数据训练或驱动的模型能力集合，用于预测、估计、初值生成、风险提示和快速筛查。", { tool_subtype: "data_driven_model_tool_group" }),
  node("tool_power_flow_fast_estimator", "潮流快速估计模型工具", "tool_call", "model_tool", "面向课题4潮流自动计算场景的快速估计能力，可根据运行断面、拓扑和历史样本生成潮流初值、风险提示或快速筛查结果。", { tool_subtype: "data_driven_model_tool" }),
  node("tool_weather_power_prediction", "气象与新能源出力预测模型工具", "tool_call", "model_tool", "面向新能源场站、负荷波动和外部环境影响的预测能力，可为故障处置、潮流计算和安全校核提供风险提示。", { tool_subtype: "data_driven_model_tool" }),
  node("tool_engineering_tool_group", "工程工具", "tool_call", "tool_group", "支撑数据接入、查询、检索、图谱访问、结果组织和报告生成的普通工程能力集合。", { tool_subtype: "engineering_tool_group" }),
  node("tool_data_query_group", "数据查询工具大类", "tool_call", "tool_group", "对齐真实数据资产目录的数据查询入口，先注册可调用能力，不展开具体表头；用于查询元数据、数据资产、设备台账、运行量测、气象、计划检修、保护故障和仿真参数。", { tool_subtype: "data_query_tool_group" }),
  node("tool_source_scanner", "统一数据源查询工具", "tool_call", "engineering_tool", "面向断面、量测、历史库、设备台账和运行方式等真实数据资产的统一查询入口，当前 demo 仅注册可调用能力，不绑定具体表头。", { tool_subtype: "data_query_tool" }),
  node("tool_graph_query", "图谱查询工具", "tool_call", "engineering_tool", "查询变电站、线路、主变、母线、重要用户、规则和文档等图谱节点及其关系。", { tool_subtype: "data_query_tool" }),
  node("tool_slot_filler", "任务槽位解析工具", "tool_call", "engineering_tool", "从调度员自然语言任务中识别故障对象、设备、运行方式、计算区域、时间断面和校核项目等结构化槽位。", { tool_subtype: "engineering_tool" }),
  node("tool_topology_analyzer", "拓扑影响分析工具", "tool_call", "engineering_tool", "围绕故障或操作对象展开邻接关系、供电路径、重要用户和影响范围分析。", { tool_subtype: "engineering_tool" }),
  node("tool_plan_retriever", "历史案例与预案召回工具", "tool_call", "engineering_tool", "从结构化预案和原始资料库中召回相似事故预案、历史案例和处置原则。", { tool_subtype: "engineering_tool" }),
  node("tool_regulation_retriever", "调规依据检索工具", "tool_call", "engineering_tool", "检索调度规程、继保运行规定、安全自动装置规定和事故处置依据片段。", { tool_subtype: "engineering_tool" }),
  node("tool_bge_rag", "向量检索与RAG工具", "tool_call", "engineering_tool", "使用BGE-M3完成文档切块、embedding、向量索引、top-k召回和普通RAG问答。", { tool_subtype: "engineering_tool" }),
  node("tool_llm", "大模型报告生成工具", "tool_call", "engineering_tool", "调用服务器vLLM上的Qwen3.5-4B，负责摘要、解释、工作流结果组织和报告生成。", { tool_subtype: "engineering_tool" }),
  node("data_asset_metadata", "元数据与数据资产", "knowledge_entity", "data_asset", "对齐知识库树中“数据-元数据与数据资产”类目，包含数据库元模型、数据资产目录及资产与电网对象关联信息。"),
  node("data_asset_dispatch_object", "组织机构与调度对象数据", "knowledge_entity", "data_asset", "对齐知识库树中“数据-组织机构与调度对象”类目，包含电网公司、调控机构、发电与检修机构、用户与市场主体等数据资产。"),
  node("data_asset_grid_topology", "电网模型与拓扑数据", "knowledge_entity", "data_asset", "对齐知识库树中“数据-电网模型与拓扑”类目，包含电网与控制区模型、厂站、断面、联络线、负荷与等值模型。"),
  node("data_asset_equipment_ledger", "一次设备台账数据", "knowledge_entity", "data_asset", "对齐知识库树中“一次设备台账”类目，包含发电设备、交流线路、直流输电、变电主设备、无功补偿和新型负荷设施等台账。"),
  node("data_asset_operation_measurement", "运行量测与状态数据", "knowledge_entity", "data_asset", "对齐知识库树中“运行量测与状态”类目，包含实时量测、历史量测、运行统计和报表。"),
  node("data_asset_weather_environment", "气象环境与外部影响数据", "knowledge_entity", "data_asset", "对齐知识库树中“气象环境与外部影响”类目，包含气象站、天气预报、雷电、台风、山火、水文和辐照数据。"),
  node("data_asset_plan_outage", "计划方式与检修停电数据", "knowledge_entity", "data_asset", "对齐知识库树中“计划方式与检修停电”类目，包含运行方式、计划、检修和停电事件数据。"),
  node("data_asset_protection_fault", "保护安控与故障事件数据", "knowledge_entity", "data_asset", "对齐知识库树中“保护安控与故障事件”类目，包含保护设备、保护配置、故障事件和故障模板。"),
  node("data_asset_simulation_parameter", "仿真计算与分析参数数据", "knowledge_entity", "data_asset", "对齐知识库树中“仿真计算与分析参数”类目，包含潮流与稳定计算模型、断面限额和安全约束参数。"),
  fallbackNodes.entity_aoti_substation,
  fallbackNodes.entity_fucheng_substation,
  fallbackNodes.entity_jiaqing_substation,
  fallbackNodes.entity_sanguandian_substation,
  fallbackNodes.entity_dongshanqiao_transformer,
  fallbackNodes.doc_aoti_blackout_plan,
  fallbackNodes.doc_sanguandian_bus_fault_plan,
  fallbackNodes.rule_power_flow_constraint,
  fallbackNodes.rule_n_minus_one,
  fallbackNodes.rule_dispatch_compliance,
  fallbackNodes.experience_dispatch_adjustment,
  fallbackNodes.rule_manual_review,
);

const idSet = new Set(nodes.map((item) => item.id));
const edges = [];
function add(source, target, type, extra = {}) {
  if (!idSet.has(source) || !idSet.has(target)) {
    throw new Error(`bad edge ${source} -> ${target}`);
  }
  edges.push({ source, target, type, ...extra });
}

const toolMap = {
  slot_filler: "tool_slot_filler",
  plan_retriever: "tool_plan_retriever",
  graph_query: "tool_graph_query",
  source_scanner: "tool_source_scanner",
  topology_analyzer: "tool_topology_analyzer",
  operator_fault_scenario_generation: "operator_fault_scenario_generation",
  operator_stability_calculation: "operator_stability_calculation",
  operator_unit_commitment_optimization: "operator_unit_commitment_optimization",
  operator_power_flow_calculation: "operator_power_flow_calculation",
  operator_spatial_feature_analysis: "operator_spatial_feature_analysis",
  operator_time_series_prediction: "operator_time_series_prediction",
  power_flow_fast_estimator: "tool_power_flow_fast_estimator",
  mock_power_check: "tool_mock_power_check",
  mock_stability_check: "tool_mock_stability_check",
  regulation_retriever: "tool_regulation_retriever",
  bge_rag: "tool_bge_rag",
  llm: "tool_llm",
};

for (const template of templates) {
  const workflowId = workflowNodeMap[template.template_id][0];
  for (const [index, step] of (template.workflow || []).entries()) {
    add(workflowId, toolMap[step.tool], "orchestrates", { order: index + 1 });
  }
}

add("tool_mechanism_tool_group", "tool_mechanism_operator_group", "contains");
add("tool_mechanism_tool_group", "tool_mock_power_check", "contains");
add("tool_mechanism_tool_group", "tool_mock_stability_check", "contains");
for (const operatorId of [
  "operator_power_flow_calculation",
  "operator_stability_calculation",
  "operator_time_series_prediction",
  "operator_spatial_feature_analysis",
  "operator_fault_scenario_generation",
  "operator_unit_commitment_optimization",
]) {
  add("tool_mechanism_operator_group", operatorId, "contains");
}
for (const item of mechanismLeafOperators) {
  add(item.parent_operator, item.id, "contains");
}

add("tool_data_driven_model_group", "tool_power_flow_fast_estimator", "contains");
add("tool_data_driven_model_group", "tool_weather_power_prediction", "contains");

for (const toolId of [
  "tool_data_query_group",
  "tool_slot_filler",
  "tool_topology_analyzer",
  "tool_plan_retriever",
  "tool_regulation_retriever",
  "tool_bge_rag",
  "tool_llm",
]) {
  add("tool_engineering_tool_group", toolId, "contains");
}
add("tool_data_query_group", "tool_source_scanner", "contains");
add("tool_data_query_group", "tool_graph_query", "contains");

const dataAssets = [
  "data_asset_metadata",
  "data_asset_dispatch_object",
  "data_asset_grid_topology",
  "data_asset_equipment_ledger",
  "data_asset_operation_measurement",
  "data_asset_weather_environment",
  "data_asset_plan_outage",
  "data_asset_protection_fault",
  "data_asset_simulation_parameter",
];
for (const dataId of dataAssets) add("tool_source_scanner", dataId, "depends_on");
for (const dataId of ["data_asset_metadata", "data_asset_grid_topology", "data_asset_equipment_ledger", "data_asset_simulation_parameter"]) add("tool_graph_query", dataId, "depends_on");
for (const dataId of ["data_asset_grid_topology", "data_asset_equipment_ledger", "data_asset_operation_measurement", "data_asset_simulation_parameter"]) add("operator_power_flow_calculation", dataId, "depends_on");
for (const operatorId of [
  "operator_newton_raphson_power_flow",
  "operator_sparse_matrix_solver",
  "operator_optimal_power_flow",
  "operator_continuation_power_flow",
]) {
  for (const dataId of ["data_asset_grid_topology", "data_asset_equipment_ledger", "data_asset_operation_measurement", "data_asset_simulation_parameter"]) add(operatorId, dataId, "depends_on");
}
for (const operatorId of [
  "operator_partial_differential_equation",
  "operator_stability_discrimination",
  "operator_stability_margin",
]) {
  for (const dataId of ["data_asset_grid_topology", "data_asset_operation_measurement", "data_asset_protection_fault", "data_asset_simulation_parameter"]) add(operatorId, dataId, "depends_on");
}
for (const operatorId of [
  "operator_external_factor_fusion",
  "operator_period_identification",
  "operator_trend_prediction",
  "operator_random_fluctuation_generation",
]) {
  for (const dataId of ["data_asset_weather_environment", "data_asset_operation_measurement"]) add(operatorId, dataId, "depends_on");
}
for (const operatorId of ["operator_topology_correlation", "operator_clustering"]) {
  for (const dataId of ["data_asset_grid_topology", "data_asset_equipment_ledger", "data_asset_operation_measurement"]) add(operatorId, dataId, "depends_on");
}
for (const operatorId of ["operator_uncertainty_sampling", "operator_spatiotemporal_correlation_generation"]) {
  for (const dataId of ["data_asset_plan_outage", "data_asset_protection_fault", "data_asset_weather_environment", "data_asset_simulation_parameter"]) add(operatorId, dataId, "depends_on");
}
for (const operatorId of ["operator_integer_optimization", "operator_stochastic_optimization", "operator_robust_optimization"]) {
  for (const dataId of ["data_asset_operation_measurement", "data_asset_weather_environment", "data_asset_simulation_parameter"]) add(operatorId, dataId, "depends_on");
}
for (const dataId of ["data_asset_operation_measurement", "data_asset_grid_topology", "data_asset_simulation_parameter"]) add("tool_power_flow_fast_estimator", dataId, "depends_on");
for (const dataId of ["data_asset_weather_environment", "data_asset_operation_measurement"]) {
  add("tool_weather_power_prediction", dataId, "depends_on");
  add("operator_time_series_prediction", dataId, "depends_on");
}
for (const dataId of ["data_asset_plan_outage", "data_asset_protection_fault", "data_asset_weather_environment"]) add("operator_fault_scenario_generation", dataId, "depends_on");

for (const [source, target, type] of [
  ["tool_graph_query", "entity_aoti_substation", "depends_on"],
  ["tool_graph_query", "entity_sanguandian_substation", "depends_on"],
  ["tool_topology_analyzer", "entity_aoti_substation", "depends_on"],
  ["tool_mock_power_check", "rule_power_flow_constraint", "depends_on"],
  ["tool_mock_power_check", "rule_n_minus_one", "depends_on"],
  ["tool_mock_stability_check", "rule_n_minus_one", "depends_on"],
  ["tool_plan_retriever", "doc_aoti_blackout_plan", "retrieves"],
  ["tool_plan_retriever", "doc_sanguandian_bus_fault_plan", "retrieves"],
  ["tool_regulation_retriever", "rule_dispatch_compliance", "retrieves"],
  ["operator_power_flow_calculation", "rule_power_flow_constraint", "depends_on"],
  ["operator_power_flow_calculation", "entity_aoti_substation", "depends_on"],
  ["operator_newton_raphson_power_flow", "rule_power_flow_constraint", "depends_on"],
  ["operator_sparse_matrix_solver", "rule_power_flow_constraint", "depends_on"],
  ["operator_optimal_power_flow", "rule_power_flow_constraint", "depends_on"],
  ["operator_continuation_power_flow", "rule_power_flow_constraint", "depends_on"],
  ["operator_stability_calculation", "rule_n_minus_one", "depends_on"],
  ["operator_stability_calculation", "rule_dispatch_compliance", "depends_on"],
  ["operator_stability_discrimination", "rule_n_minus_one", "depends_on"],
  ["operator_stability_margin", "rule_n_minus_one", "depends_on"],
  ["operator_time_series_prediction", "tool_weather_power_prediction", "supports"],
  ["operator_external_factor_fusion", "tool_weather_power_prediction", "supports"],
  ["operator_trend_prediction", "tool_weather_power_prediction", "supports"],
  ["operator_spatial_feature_analysis", "tool_topology_analyzer", "supports"],
  ["operator_topology_correlation", "tool_topology_analyzer", "supports"],
  ["operator_fault_scenario_generation", "workflow_fault_substation_blackout", "supports"],
  ["operator_fault_scenario_generation", "workflow_fault_500kv_bus_fault", "supports"],
  ["operator_fault_scenario_generation", "workflow_fault_wind_farm_blackout", "supports"],
  ["operator_uncertainty_sampling", "workflow_operation_n_minus_one_check", "supports"],
  ["operator_spatiotemporal_correlation_generation", "workflow_fault_wind_farm_blackout", "supports"],
  ["operator_unit_commitment_optimization", "workflow_fault_500kv_bus_fault", "supports"],
  ["operator_integer_optimization", "workflow_fault_500kv_bus_fault", "supports"],
  ["operator_stochastic_optimization", "workflow_fault_500kv_bus_fault", "supports"],
  ["operator_robust_optimization", "workflow_fault_500kv_bus_fault", "supports"],
  ["tool_weather_power_prediction", "workflow_fault_wind_farm_blackout", "supports"],
]) {
  add(source, target, type);
}

for (const workflowId of [
  "workflow_operation_load_transfer_check",
  "workflow_operation_protection_check",
  "workflow_operation_n_minus_one_check",
  "workflow_fault_500kv_bus_fault",
  "workflow_power_flow_auto_calculation",
  "workflow_power_flow_snapshot_calculation",
  "workflow_power_flow_operation_adjustment_check",
]) add("rule_power_flow_constraint", workflowId, "constrains");

for (const workflowId of [
  "workflow_operation_load_transfer_check",
  "workflow_operation_protection_check",
  "workflow_operation_n_minus_one_check",
  "workflow_fault_substation_blackout",
  "workflow_fault_500kv_bus_fault",
  "workflow_fault_wind_farm_blackout",
]) add("rule_dispatch_compliance", workflowId, "constrains");

add("rule_n_minus_one", "workflow_operation_n_minus_one_check", "constrains");
add("rule_n_minus_one", "workflow_fault_500kv_bus_fault", "constrains");
for (const workflowId of ["workflow_operation_load_transfer_check", "workflow_fault_substation_blackout", "workflow_fault_500kv_bus_fault"]) add("experience_dispatch_adjustment", workflowId, "constrains");
for (const [workflowId] of Object.values(workflowNodeMap)) add("rule_manual_review", workflowId, "constrains");
add("entity_aoti_substation", "entity_fucheng_substation", "connected_to");
add("entity_aoti_substation", "entity_jiaqing_substation", "connected_to");
add("doc_aoti_blackout_plan", "entity_aoti_substation", "mentions");
add("doc_sanguandian_bus_fault_plan", "entity_sanguandian_substation", "mentions");

const schema = {
  node_categories: {
    skill_process: "面向典型电网分析决策任务沉淀的形式化、规模化、模板化处理流程，与工作流模板文件保持一一对应。",
    tool_call: "可以被智能体检索、选择和调用的能力节点，包括机理工具、数据驱动模型工具和工程工具。工程工具下包含对齐真实数据资产的数据查询工具大类。",
    knowledge_entity: "设备、线路、厂站、数据资产、调度规程、运行规则、专家经验、历史案例、处置原则和风险偏好等对象、规则、经验与解释依据。",
  },
  tool_subtypes: {
    mechanism_tool_group: "机理工具总类，用于组织机理模型工具、机理算子大类和具体机理算子。",
    mechanism_operator_group: "机理算子大类，用于组织潮流计算、稳定计算、时间序列预测、空间特征分析、故障场景生成和机组组合优化等可调用算子。",
    mechanism_operator: "可被工作流像工具一样选择和调用的机理算子节点；其中 operator_level=family 表示树中的算子大类，operator_level=leaf 表示从 nari_mechanism_data_knowledge_tree.txt 展开的具体叶子算子。",
    mechanism_model_tool: "来自课题2或可替换机理接口的潮流计算、稳定分析、优化决策、安全校核等机理模型工具。",
    data_driven_model_tool_group: "数据驱动模型工具总类，用于组织由运行数据、仿真数据或外部环境数据驱动的预测、估计和风险识别能力。",
    data_driven_model_tool: "气象预测、新能源出力预测、潮流快速估计、状态预测和风险识别等数据驱动模型工具。",
    engineering_tool_group: "工程工具总类，用于组织查询、检索、图谱访问、槽位解析、报告生成等工程支撑能力。",
    data_query_tool_group: "工程工具下的数据查询大类，对齐真实数据资产目录，当前先注册可调用入口，不展开具体表头。",
    data_query_tool: "面向元数据、台账、量测、气象、计划检修、保护故障和仿真参数等真实数据资产的查询工具。",
    engineering_tool: "槽位解析、拓扑分析、向量检索、预案召回、调规检索、报告生成等普通工程工具。",
  },
  node_roles: ["workflow", "tool_group", "operator_group", "operator", "model_tool", "engineering_tool", "data_asset", "entity", "document", "rule", "experience", "case"],
  edge_types: {
    orchestrates: "流程牵引工具调用节点，规定调用顺序、输入输出衔接和校核逻辑。",
    contains: "工具大类或算子大类包含下级工具、算子或工具分组。",
    depends_on: "工具调用节点依赖知识实体、数据资产、规则、对象或参数来源。",
    constrains: "知识实体节点约束流程节点，影响流程选择、结果解释、风险提示和人工复核条件。",
    supports: "知识实体节点、工具调用节点或流程节点为其他节点提供支撑。",
    retrieves: "检索工具召回文档、规则、案例或知识片段。",
    mentions: "文档或案例中提及某个知识实体。",
    connected_to: "设备、线路、厂站和用户之间存在拓扑或业务关联。",
    checks: "校核工具依据规则、约束或模型进行检查。",
  },
  organization_principle: "当前图谱后端按工作流模板、工具能力和知识/数据资产组织。流程节点与9个工作流模板对齐；机理工具内部再细分机理模型工具、机理算子大类和具体叶子算子；数据驱动模型工具与真实数据资产区分；真实数据通过工程工具下的数据查询大类注册为可调用能力。",
};

const readme = `# graph 目录说明

本目录保存“流程牵引、工具支撑、知识约束”的融合图谱定义。

- \`schema.json\`：定义节点类型、工具子类型和关系类型。
- \`nodes.json\`：演示图谱节点。
- \`edges.json\`：演示图谱关系。

## 当前后端分类

### 流程节点

\`skill_process\` 与 \`data/workflow_templates.json\` 的 9 个工作流模板一一对应，包括变电站全停、负荷转供、保护投退、N-1风险校核、500kV母线故障、潮流自动计算、断面潮流计算、运行方式调整后潮流越限校核、新能源场站全场停电处置。

### 工具调用节点

工具节点先分为三组：

- \`机理工具\`：下设机理模型工具、机理算子大类和具体机理算子。
- \`数据驱动模型工具\`：表示由运行数据、仿真数据或外部环境数据驱动的预测、估计和风险识别能力，避免与真实数据资产混淆。
- \`工程工具\`：包含槽位解析、拓扑分析、检索、报告生成，以及 \`数据查询工具大类\`。

\`机理算子大类\` 对齐 \`<workspace_root>\\结题\\nari_mechanism_data_knowledge_tree.txt\` 中“机理”下的树结构：潮流计算、稳定计算、时间序列预测、空间特征分析、故障场景生成、机组组合优化仍作为算子大类保留；牛顿拉夫逊算子、稀疏矩阵计算算子、最优潮流算子、连续潮流算子等具体条目展开为可检索、可点击、可被即时规划召回的叶子算子。

\`数据查询工具大类\` 对齐 \`<workspace_root>\\结题\\nari_mechanism_data_knowledge_tree.txt\` 中“数据”下的真实数据资产目录。当前 demo 不需要真实运行，因此只注册可调用的数据查询入口，不展开具体表头。

### 知识实体节点

\`knowledge_entity\` 既包含设备、预案、规则、经验，也包含先注册进来的数据资产类目，例如元数据与数据资产、电网模型与拓扑、一次设备台账、运行量测与状态、气象环境与外部影响、计划方式与检修停电、保护安控与故障事件、仿真计算与分析参数。

## 关系类型

- \`orchestrates\`：流程牵引工具，规定工具调用顺序。
- \`contains\`：工具大类包含工具分组、模型工具或算子。
- \`depends_on\`：工具依赖知识实体、数据资产、规则、对象或参数来源。
- \`constrains\`：知识约束流程，影响流程选择、结果解释、风险提示和人工复核。
- \`supports\`：节点为其他节点提供支撑。
- \`retrieves\`、\`mentions\`、\`connected_to\`、\`checks\`：用于描述检索、提及、拓扑连接和校核关系。
`;

fs.writeFileSync(path.join(graphDir, "nodes.json"), JSON.stringify(nodes, null, 2) + "\n", "utf8");
fs.writeFileSync(path.join(graphDir, "edges.json"), JSON.stringify(edges, null, 2) + "\n", "utf8");
fs.writeFileSync(path.join(graphDir, "schema.json"), JSON.stringify(schema, null, 2) + "\n", "utf8");
fs.writeFileSync(path.join(graphDir, "README_graph.md"), readme, "utf8");

console.log(JSON.stringify({ nodes: nodes.length, edges: edges.length, workflows: templates.length }, null, 2));
