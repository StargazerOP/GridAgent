const state = {
  templates: [],
  nodes: [],
  edges: [],
  selectedNodeId: "",
  filter: "all",
  lastQuery: "",
  lastCandidates: [],
  apiAvailable: false,
  activeAssetType: "workflows",
  adminCatalog: {},
  graphZoom: 1,
  graphBaseViewBox: { width: 1200, height: 680 },
  workflowZoom: 1,
};

const categoryLabel = {
  skill_process: "流程节点",
  tool_call: "工具节点",
  knowledge_entity: "知识实体",
};

const subtypeLabel = {
  mechanism_tool_group: "机理工具",
  mechanism_operator_group: "机理算子大类",
  mechanism_operator: "机理算子",
  mechanism_model_tool: "机理模型工具",
  data_driven_model_tool_group: "数据驱动模型工具",
  data_driven_model_tool: "数据驱动模型工具",
  engineering_tool_group: "工程工具",
  data_query_tool_group: "数据查询工具大类",
  data_query_tool: "数据查询工具",
  engineering_tool: "工程工具",
};

const categoryClass = {
  skill_process: "skill",
  tool_call: "tool",
  knowledge_entity: "knowledge",
};

const groupRoles = new Set(["tool_group", "operator_group"]);
const callableToolRoles = new Set(["operator", "model_tool", "engineering_tool"]);
const domainKeywords = [
  "牛顿拉夫逊", "稀疏矩阵", "最优潮流", "连续潮流", "潮流",
  "偏微分方程", "稳定性判别", "稳定裕度", "稳定",
  "外部影响因素", "周期识别", "趋势预测", "随机波动", "时间序列",
  "拓扑相关性", "聚类", "空间特征", "不确定性抽样", "时空相关性",
  "故障场景", "整数优化", "随机优化", "鲁棒优化", "机组组合",
  "台风", "气象", "天气", "新能源", "断面", "送出线路", "线路", "风险",
];

const sampleQueries = [
  "奥体变负荷转供调整后做潮流越限校核",
  "奥体变当前断面潮流计算，输出节点电压和线路负载",
  "白云变全停后如何处置",
  "东善桥2号主变停役后发生N-1故障，帮我做风险校核",
  "综合评估台风天气下新能源场站、送出线路和断面风险",
];

const assetTypeLabel = {
  workflows: "工作流模板",
  skills: "Skills",
  mechanisms: "机理/算子",
  data_driven_models: "数据驱动模型",
  documents: "文档资料",
  data_assets: "数据资产表",
};

async function loadData() {
  const [templates, nodes, edges] = await Promise.all([
    fetch("../data/workflow_templates.json").then(r => r.json()),
    fetch("../graph/nodes.json").then(r => r.json()),
    fetch("../graph/edges.json").then(r => r.json()),
  ]);
  state.templates = templates;
  state.nodes = nodes;
  state.edges = edges;
  document.getElementById("statTemplates").textContent = templates.length;
  document.getElementById("statNodes").textContent = nodes.length;
  document.getElementById("statEdges").textContent = edges.length;
  renderQuickPrompts();
  renderWorkflow(templates[0]);
  renderFallbackCards(nodes.slice(0, 6));
  renderSubgraph(nodes[0]?.id);
  renderFullGraph();
  renderNodeCards();
  await checkApi();
  await loadAssets();
  setSummary(`默认展示：${templates[0].name}。输入任务后会根据关键词匹配工作流模板。`);
}

async function checkApi() {
  try {
    const res = await fetch("/api/health");
    state.apiAvailable = res.ok;
  } catch {
    state.apiAvailable = false;
  }
  const pill = document.querySelector(".status-pill");
  if (pill) {
    pill.innerHTML = `<span></span> ${state.apiAvailable ? "后端API已连接" : "静态演示模式"}`;
  }
}

function renderQuickPrompts() {
  const wrap = document.getElementById("quickPrompts");
  wrap.innerHTML = "";
  sampleQueries.forEach(text => {
    const btn = document.createElement("button");
    btn.className = "prompt-chip";
    btn.textContent = text;
    btn.addEventListener("click", () => {
      document.getElementById("queryInput").value = text;
      runSearch();
    });
    wrap.appendChild(btn);
  });
}

function normalizeText(text) {
  return String(text || "").toLowerCase();
}

function templateScore(query, template) {
  const q = normalizeText(query);
  const pieces = [
    template.name,
    template.description,
    template.scene,
    ...(template.keywords || []),
    ...(template.workflow || []).flatMap(step => [step.step, step.tool]),
  ].map(normalizeText);
  let score = 0;
  pieces.forEach(piece => {
    if (piece && q.includes(piece)) score += 3;
  });
  (template.keywords || []).forEach(key => {
    if (q.includes(normalizeText(key))) score += 4;
  });
  Array.from(new Set(q.split(/[\s，。；、,.;:：?？]+/).filter(Boolean))).forEach(token => {
    pieces.forEach(piece => {
      if (token.length > 1 && piece.includes(token)) score += 0.8;
    });
  });
  score += ruleBonus(q, template.template_id);
  return score;
}

function ruleBonus(q, id) {
  let score = 0;
  if ((q.includes("全停") || q.includes("全所失电")) && id === "fault_substation_blackout") score += 8;
  if ((q.includes("预案") || q.includes("处置")) && id.includes("fault")) score += 4;
  if ((q.includes("操作") || q.includes("校核")) && id.includes("operation")) score += 3;
  if (q.includes("保护") && id === "operation_protection_check") score += 8;
  if (q.includes("转供") && id === "operation_load_transfer_check") score += 8;
  if ((q.includes("n-1") || q.includes("n-2") || q.includes("停役") || q.includes("检修")) && id === "operation_n_minus_one_check") score += 8;
  if (q.includes("500kv") && q.includes("母线") && id === "fault_500kv_bus_fault") score += 8;
  if (q.includes("新能源") && id === "fault_wind_farm_blackout") score += 6;
  if (q.includes("潮流") && id === "power_flow_auto_calculation") score += 4;
  if (q.includes("潮流") && (q.includes("当前") || q.includes("实时") || q.includes("断面") || q.includes("节点电压")) && id === "power_flow_snapshot_calculation") score += 10;
  if (q.includes("潮流") && (q.includes("调整") || q.includes("停运") || q.includes("转供") || q.includes("越限") || q.includes("检修")) && id === "power_flow_operation_adjustment_check") score += 10;
  return score;
}

function runSearch() {
  const query = document.getElementById("queryInput").value.trim();
  state.lastQuery = query;
  hideInstantPlanActions();
  if (!query) {
    setSummary("请输入一个调度任务，例如：奥体变负荷转供调整后做潮流越限校核。");
    return;
  }
  const ranked = state.templates
    .map(template => ({ template, score: templateScore(query, template) }))
    .sort((a, b) => b.score - a.score);

  const best = ranked[0];
  if (best && best.score >= 5) {
    renderWorkflow(best.template);
    const relatedIds = workflowRelatedNodeIds(best.template);
    const cards = state.nodes.filter(node => relatedIds.has(node.id)).slice(0, 8);
    renderFallbackCards(cards.length ? cards : state.nodes.slice(0, 6));
    renderSubgraph(cards[0]?.id || relatedIds.values().next().value);
    setSummary(`命中流程：${best.template.name}。匹配分数 ${best.score.toFixed(1)}，共 ${best.template.workflow.length} 个步骤。`);
  } else {
    const candidates = findCandidateNodes(query);
    state.lastCandidates = candidates;
    renderWorkflow(null);
    renderFallbackCards(candidates);
    renderSubgraph(candidates[0]?.id);
    setSummary(`没有找到足够可信的固化流程。已返回 ${candidates.length} 个可能相关的节点，并在右侧展示相关子图。`);
    showInstantPlanActions();
  }
}

function workflowRelatedNodeIds(template) {
  const ids = new Set();
  const toolAliases = new Map(state.nodes.map(node => [node.id.replace(/^tool_/, "").replace(/^operator_/, ""), node.id]));
  const nameToNode = new Map(state.nodes.map(node => [node.name, node.id]));
  const workflowNode = state.nodes.find(node => node.template_id === template.template_id || node.name.includes(template.name.replace("模板", "")));
  if (workflowNode) ids.add(workflowNode.id);
  (template.workflow || []).forEach(step => {
    const tool = step.tool;
    if (toolAliases.has(tool)) ids.add(toolAliases.get(tool));
    if (toolAliases.has(tool.replace(/^mock_/, ""))) ids.add(toolAliases.get(tool.replace(/^mock_/, "")));
    const exact = state.nodes.find(node => node.id.endsWith(tool) || node.id === `tool_${tool}` || node.id === tool);
    if (exact) ids.add(exact.id);
  });
  if (template.name.includes("潮流")) {
    ["operator_power_flow_calculation", "rule_power_flow_constraint"].forEach(id => ids.add(id));
  }
  if (template.name.includes("预案") || template.name.includes("故障")) {
    ["operator_fault_scenario_generation", "operator_stability_calculation", "rule_dispatch_compliance"].forEach(id => ids.add(id));
  }
  nameToNode.forEach((id, name) => {
    if (template.description.includes(name)) ids.add(id);
  });
  return ids;
}

function findCandidateNodes(query) {
  const q = normalizeText(query);
  const scored = state.nodes.filter(node => !groupRoles.has(node.role) && node.category !== "skill_process").map(node => {
    const text = normalizeText([node.name, node.category, node.role, node.tool_subtype, node.description, ...(node.examples || [])].join(" "));
    let score = 0;
    Array.from(new Set(q.split(/[\s，。；、,.;:：?？]+/).filter(Boolean))).forEach(token => {
      if (token.length > 1 && text.includes(token)) score += 2;
    });
    domainKeywords.forEach(keyword => {
      if (q.includes(keyword) && text.includes(keyword)) score += 4;
    });
    if (q.includes("潮流") && (node.id.includes("power_flow") || node.id.includes("flow"))) score += 4;
    if (q.includes("风险") && (node.id.includes("stability") || node.id.includes("security") || node.name.includes("风险"))) score += 3;
    if ((q.includes("台风") || q.includes("气象") || q.includes("天气")) && (text.includes("气象") || text.includes("天气") || node.id.includes("weather"))) score += 5;
    if (q.includes("新能源") && (text.includes("新能源") || text.includes("出力") || node.id.includes("weather_power"))) score += 5;
    if ((q.includes("断面") || q.includes("送出线路") || q.includes("线路")) && (text.includes("断面") || text.includes("线路") || text.includes("拓扑") || node.id.includes("stability"))) score += 4;
    if (callableToolRoles.has(node.role)) score += 0.3;
    if (node.operator_level === "leaf") score += 1.2;
    if (node.operator_level === "family") score += 0.2;
    return { node, score };
  }).sort((a, b) => b.score - a.score);
  const candidates = scored.filter(item => item.score > 0).map(item => item.node).slice(0, 8);
  return candidates.length ? candidates : state.nodes.filter(node => !groupRoles.has(node.role) && node.category !== "skill_process").slice(0, 8);
}

function renderWorkflow(template) {
  const canvas = document.getElementById("workflowCanvas");
  canvas.innerHTML = "";
  if (!template) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.innerHTML = "未命中明确工作流。<br>请查看下方候选节点和右侧相关子图。";
    canvas.appendChild(empty);
    appendWorkflowHint(canvas);
    return;
  }
  const row = document.createElement("div");
  row.className = "flow-row";
  template.workflow.forEach((step, index) => {
    const card = document.createElement("div");
    card.className = "flow-card";
    const type = toolDisplayType(step.tool);
    card.innerHTML = `
      <div class="flow-index">${index + 1}</div>
      <div class="flow-step">${escapeHtml(step.step)}</div>
      <div class="tool-pill ${type.className}">${type.label} · ${escapeHtml(step.tool)}</div>
    `;
    row.appendChild(card);
  });
  canvas.appendChild(row);
  appendWorkflowHint(canvas);
  applyWorkflowZoom();
}

function renderInstantPlan(plan) {
  const canvas = document.getElementById("workflowCanvas");
  canvas.innerHTML = "";
  const banner = document.createElement("div");
  banner.className = "instant-banner";
  banner.textContent = `${plan.title || "即时规划流程"}：${plan.summary || "该流程未固化为模板，需要人工确认。"}`;
  canvas.appendChild(banner);

  const row = document.createElement("div");
  row.className = "flow-row";
  (plan.steps || []).forEach((step, index) => {
    const card = document.createElement("div");
    card.className = "flow-card instant";
    const type = toolDisplayType(step.tool || "");
    card.innerHTML = `
      <div class="flow-index">${index + 1}</div>
      <div class="flow-step">${escapeHtml(step.step || "")}</div>
      <div class="tool-pill ${type.className}">${type.label} · ${escapeHtml(step.tool || "")}</div>
      <p class="flow-note">${escapeHtml(step.expected_output || step.input || "")}</p>
    `;
    row.appendChild(card);
  });
  canvas.appendChild(row);
  appendWorkflowHint(canvas);
  applyWorkflowZoom();
}

function appendWorkflowHint(canvas) {
  const hint = document.createElement("div");
  hint.className = "workflow-shortcut-hint";
  hint.textContent = "快捷键缩放：Ctrl+- 缩小 · Ctrl+= 放大 · Ctrl+0 还原";
  canvas.appendChild(hint);
}

function toolDisplayType(tool) {
  if (tool.startsWith("operator_")) return { label: "机理算子", className: "operator" };
  if (["graph_query", "source_scanner", "slot_filler", "plan_retriever", "regulation_retriever", "llm", "bge_rag"].includes(tool)) {
    return { label: "工程工具", className: "engineering" };
  }
  if (tool.includes("power") || tool.includes("stability")) return { label: "模型工具", className: "operator" };
  return { label: "工具", className: "" };
}

function renderFallbackCards(nodes) {
  const wrap = document.getElementById("candidateCards");
  wrap.innerHTML = "";
  nodes.forEach(node => wrap.appendChild(createNodeCard(node, true)));
}

function showInstantPlanActions() {
  document.getElementById("instantPlanActions").classList.add("visible");
}

function hideInstantPlanActions() {
  document.getElementById("instantPlanActions").classList.remove("visible");
}

async function runInstantPlan() {
  const query = state.lastQuery || document.getElementById("queryInput").value.trim();
  if (!query) return;
  const useLlm = document.getElementById("instantUseLlm").checked;
  const btn = document.getElementById("instantPlanBtn");
  btn.disabled = true;
  btn.textContent = "规划中...";
  setProgress([
    ["active", "召回候选知识"],
    ["pending", "整理可用工具"],
    [useLlm ? "pending" : "done", useLlm ? "调用LLM规划" : "跳过LLM"],
    ["pending", "生成临时流程"],
  ]);
  try {
    let result;
    let usedLocalOnly = false;
    if (state.apiAvailable) {
      const res = await fetch("/api/plan/instant", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ query, use_llm: useLlm }),
      });
      result = await res.json();
      if (!res.ok || result.ok === false) throw new Error(result.error || "后端即时规划接口暂不可用");
    } else {
      result = { plan: localInstantPlan(query, state.lastCandidates) };
      usedLocalOnly = true;
    }
    setProgress([
      ["done", "召回候选知识"],
      ["done", "整理可用工具"],
      [useLlm ? "active" : "done", useLlm ? "调用LLM规划" : "跳过LLM"],
      ["active", "生成临时流程"],
    ]);
    renderInstantPlan(result.plan);
    const contextNodes = mergeCandidateNodes(result.context?.candidate_nodes || state.lastCandidates || [], result.context?.candidate_assets || []);
    renderFallbackCards(contextNodes.slice(0, 8));
    renderSubgraph(contextNodes[0]?.id);
    const statusText = instantPlanStatusText(result, useLlm, usedLocalOnly);
    setSummary(statusText);
    setProgress([
      ["done", "召回候选知识"],
      ["done", "整理可用工具"],
      [useLlm && result.llm_result?.ok ? "done" : "done", useLlm ? (result.llm_result?.ok ? "LLM已返回" : "LLM不可用") : "跳过LLM"],
      ["done", "生成临时流程"],
    ], statusText);
  } catch (error) {
    const fallback = localInstantPlan(query, state.lastCandidates);
    renderInstantPlan(fallback);
    setProgress([
      ["done", "召回候选知识"],
      ["done", "整理可用工具"],
      ["done", "本地兜底规划"],
    ], `LLM 服务或后端接口暂时连不上，已自动切换为本地兜底规划。这个结果只用于演示流程编排，正式使用前需要人工复核。`);
    console.warn("instant planning fallback:", error);
  } finally {
    btn.disabled = false;
    btn.textContent = "开启即时规划";
  }
}

function mergeCandidateNodes(nodes, assets) {
  const merged = [];
  const seen = new Set();
  [...nodes, ...assets].forEach(node => {
    if (!node || seen.has(node.id) || groupRoles.has(node.role)) return;
    seen.add(node.id);
    merged.push(node);
  });
  return merged;
}

function instantPlanStatusText(result, useLlm, usedLocalOnly) {
  const title = result.plan?.title || "临时流程";
  if (usedLocalOnly) {
    return `后端API未连接，已使用浏览器本地兜底规划：${title}。该结果只用于演示流程编排，正式使用前需要人工复核。`;
  }
  if (!useLlm || result.llm_result?.skipped) {
    return `后端API已连接；本次未调用LLM，已使用后端本地兜底规划：${title}。该结果未固化为模板，正式使用前需要人工复核。`;
  }
  if (result.llm_result?.ok) {
    return `后端API已连接，LLM已返回即时规划：${title}。该结果未固化为模板，正式使用前需要人工复核。`;
  }
  const error = result.llm_result?.error || "未知错误";
  return `后端API已连接，但LLM服务暂不可用（${error}），已使用后端本地兜底规划：${title}。这就是为什么你看到的规划会比较固定。`;
}

function setProgress(items, text = "") {
  const summary = document.getElementById("resultSummary");
  const html = items.map(([status, label]) => `<span class="progress-step ${status}">${escapeHtml(label)}</span>`).join("");
  summary.innerHTML = `<div class="progress-strip">${html}</div>${text ? `<div class="progress-text">${escapeHtml(text)}</div>` : ""}`;
}

function localInstantPlan(query, candidates) {
  const ids = (candidates || []).slice(0, 4).map(node => node.id);
  return {
    title: "未命中模板的即时规划流程",
    summary: "当前任务未命中固化模板，基于候选知识和可用工具生成临时规划。",
    steps: [
      { step: "解析任务对象和目标", tool: "tool_slot_filler", input: query, expected_output: "识别设备、场景和输出目标", evidence: ids },
      { step: "召回相关知识和数据资产", tool: "tool_source_scanner", input: "候选知识与数据资产目录", expected_output: "形成依据清单", evidence: ids },
      { step: "编排可调用工具链", tool: "tool_graph_query", input: "任务目标和候选节点", expected_output: "生成临时调用顺序", evidence: ids },
      { step: "生成规划说明和人工复核项", tool: "tool_llm", input: "临时流程草案", expected_output: "输出即时规划结果", evidence: ids },
    ],
    human_review: ["即时规划未固化为模板，需要人工确认。"],
    template_status: "instant_not_persisted",
  };
}

async function loadAssets(query = "") {
  const stats = document.getElementById("assetStats");
  const rows = document.getElementById("assetRows");
  if (!stats || !rows) return;
  if (!state.apiAvailable) {
    stats.innerHTML = `<div><b>后端未连接</b><small>启动 python main.py serve 后可查看真实资产目录</small></div>`;
    rows.innerHTML = `<tr><td colspan="4">当前为静态演示模式，无法读取 outputs/asset_registry.json。</td></tr>`;
    return;
  }
  try {
    await loadAdminOverview(false);
    if (state.activeAssetType !== "data_assets") {
      renderManagedAssets(query);
      return;
    }
    const url = `/api/assets?q=${encodeURIComponent(query)}&top_k=120`;
    const payload = await fetch(url).then(r => r.json());
    const summary = payload.summary?.summary || {};
    stats.innerHTML = `
      <div><b>${summary.data_tables || 0}</b><small>数据资产表</small></div>
      <div><b>${state.adminCatalog.workflows?.length || 0}</b><small>工作流模板</small></div>
      <div><b>${state.adminCatalog.documents?.length || 0}</b><small>扫描文档</small></div>
    `;
    renderAssetTypeTabs();
    rows.innerHTML = "";
    (payload.assets || []).forEach(item => {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td><b>${escapeHtml(item.name)}</b><p>${escapeHtml(item.description || "")}</p></td>
        <td>${escapeHtml(item.table_code || "-")}</td>
        <td>${escapeHtml(item.data_category || "")}<br><small>${escapeHtml(item.sub_category || "")}</small></td>
        <td><span class="tag tool">${item.header_registered ? "表头已登记" : "可模拟调用"}</span></td>
      `;
      tr.addEventListener("click", () => showAssetDetail({
        id: item.id,
        name: item.name,
        kind: "data_asset",
        description: item.description,
        path: (item.path || []).join(" / "),
        status: item.status,
        meta: { table_code: item.table_code, data_category: item.data_category, sub_category: item.sub_category },
      }));
      rows.appendChild(tr);
    });
  } catch (error) {
    stats.innerHTML = `<div><b>读取失败</b><small>${escapeHtml(error.message)}</small></div>`;
  }
}

async function loadAdminOverview(renderGuide = true) {
  const guide = document.getElementById("additionGuide");
  if (!state.apiAvailable) return;
  try {
    const payload = await fetch("/api/admin/overview").then(r => r.json());
    state.adminCatalog = payload.registry?.catalog || {};
    if (renderGuide && guide) {
      guide.innerHTML = "";
      Object.entries(payload.guide || {}).forEach(([kind, steps]) => {
        const block = document.createElement("div");
        block.className = "guide-block";
        block.innerHTML = `<h3>${kindLabel(kind)}</h3><ol>${steps.map(step => `<li>${escapeHtml(step)}</li>`).join("")}</ol>`;
        guide.appendChild(block);
      });
    }
  } catch {
    if (guide) guide.innerHTML = `<div class="empty-state">后端API未连接，无法读取新增流程。</div>`;
  }
}

function renderAssetTypeTabs() {
  const wrap = document.getElementById("assetTypeTabs");
  if (!wrap) return;
  const counts = {
    workflows: state.adminCatalog.workflows?.length || 0,
    skills: state.adminCatalog.skills?.length || 0,
    mechanisms: state.adminCatalog.mechanisms?.length || 0,
    data_driven_models: state.adminCatalog.data_driven_models?.length || 0,
    documents: state.adminCatalog.documents?.length || 0,
    data_assets: "2315+",
  };
  wrap.innerHTML = Object.keys(assetTypeLabel).map(type => `
    <button class="asset-type-chip ${state.activeAssetType === type ? "active" : ""}" data-asset-type="${type}">
      ${assetTypeLabel[type]} <span>${counts[type]}</span>
    </button>
  `).join("");
  wrap.querySelectorAll("button").forEach(btn => {
    btn.addEventListener("click", () => {
      state.activeAssetType = btn.dataset.assetType;
      loadAssets(document.getElementById("assetSearchInput").value.trim());
    });
  });
}

function renderManagedAssets(query = "") {
  const stats = document.getElementById("assetStats");
  const rows = document.getElementById("assetRows");
  renderAssetTypeTabs();
  stats.innerHTML = `
    <div><b>${state.adminCatalog.workflows?.length || 0}</b><small>工作流模板</small></div>
    <div><b>${state.adminCatalog.mechanisms?.length || 0}</b><small>机理/算子资产</small></div>
    <div><b>${state.adminCatalog.data_assets_count || "2315+"}</b><small>数据资产表</small></div>
    <div><b>${state.adminCatalog.documents?.length || 0}</b><small>文档资料</small></div>
  `;
  const term = normalizeText(query);
  const items = (state.adminCatalog[state.activeAssetType] || [])
    .filter(item => !term || normalizeText(`${item.name} ${item.description} ${item.path} ${JSON.stringify(item.meta || {})}`).includes(term))
    .slice(0, 160);
  rows.innerHTML = "";
  items.forEach(item => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td><b>${escapeHtml(item.name)}</b><p>${escapeHtml(item.description || "")}</p></td>
      <td>${escapeHtml(assetTypeLabel[state.activeAssetType] || item.kind)}<br><small>${escapeHtml(item.id || "")}</small></td>
      <td>${escapeHtml(item.path || "")}<br><small>${escapeHtml(JSON.stringify(item.meta || {}))}</small></td>
      <td><span class="tag tool">${escapeHtml(item.status || "registered")}</span></td>
    `;
    tr.addEventListener("click", () => showAssetDetail(item));
    rows.appendChild(tr);
  });
}

function kindLabel(kind) {
  return {
    document: "新增文档资料",
    workflow: "新增工作流模板",
    skill: "新增 Skill",
    mechanism: "新增机理工具/算子",
    data_asset: "新增数据资产",
    data_driven_model: "新增数据驱动模型",
  }[kind] || kind;
}

async function registerItem() {
  const payload = {
    kind: document.getElementById("registerKind").value,
    name: document.getElementById("registerName").value.trim(),
    source_path: document.getElementById("registerPath").value.trim(),
    description: document.getElementById("registerDescription").value.trim(),
  };
  const result = document.getElementById("registerResult");
  if (!payload.name) {
    result.textContent = "请填写名称。";
    return;
  }
  if (!state.apiAvailable) {
    result.textContent = "后端API未连接。请先运行 python main.py serve。";
    return;
  }
  try {
    const res = await fetch("/api/admin/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const data = await res.json();
    if (!res.ok || data.ok === false) throw new Error(data.error || "登记失败");
    result.textContent = `已登记：${data.item.name}。后续步骤已写入 data/custom_registry.json。`;
  } catch (error) {
    result.textContent = `登记失败：${error.message}`;
  }
}

function renderNodeCards() {
  const wrap = document.getElementById("nodeCards");
  const term = normalizeText(document.getElementById("nodeSearch").value);
  wrap.innerHTML = "";
  state.nodes
    .filter(node => state.filter === "all" || node.category === state.filter)
    .filter(node => !term || normalizeText(`${node.name} ${node.description} ${node.tool_subtype || ""}`).includes(term))
    .forEach(node => wrap.appendChild(createNodeCard(node, true)));
}

function createNodeCard(node, clickable) {
  const card = document.createElement("div");
  card.className = `node-card ${state.selectedNodeId === node.id ? "selected" : ""}`;
  const sub = node.category === "tool_call" && node.tool_subtype ? `<span class="tag sub">工具节点-${subtypeLabel[node.tool_subtype] || node.tool_subtype}</span>` : "";
  card.innerHTML = `
    <div class="node-card-title">${escapeHtml(node.name)}</div>
    <div class="tag-row">
      <span class="tag ${categoryClass[node.category]}">${categoryLabel[node.category] || node.category}</span>
      <span class="tag">${escapeHtml(node.role || "")}</span>
      ${sub}
    </div>
    <p>${escapeHtml(node.description || "")}</p>
  `;
  if (clickable) {
    card.addEventListener("click", () => {
      state.selectedNodeId = node.id;
      renderSubgraph(node.id);
      renderFullGraph();
      renderNodeCards();
      showNodeDetail(node);
    });
  }
  return card;
}

function showNodeDetail(node) {
  const drawer = document.getElementById("detailDrawer");
  document.getElementById("detailTitle").textContent = node.name || node.id;
  document.getElementById("detailSubtitle").textContent = `${categoryLabel[node.category] || node.category} · ${node.role || ""}${node.tool_subtype ? " · " + (subtypeLabel[node.tool_subtype] || node.tool_subtype) : ""}`;
  const rows = [
    ["ID", node.id],
    ["说明", node.description],
    ["关键词", (node.keywords || []).join("，")],
    ["典型步骤", (node.typical_steps || []).join(" → ")],
    ["输入", (node.inputs || []).join("，")],
    ["输出", (node.outputs || []).join("，")],
    ["示例/子算子", (node.examples || node.operator_families || []).join("，")],
  ].filter(([, value]) => value);
  document.getElementById("detailBody").innerHTML = rows.map(([key, value]) => `
    <div class="detail-row">
      <b>${escapeHtml(key)}</b>
      <span>${escapeHtml(value)}</span>
    </div>
  `).join("");
  drawer.classList.add("visible");
  drawer.setAttribute("aria-hidden", "false");
}

function showAssetDetail(item) {
  showNodeDetail({
    id: item.id,
    name: item.name,
    category: "knowledge_entity",
    role: item.kind,
    description: `${item.description || ""}\n\n路径：${item.path || ""}\n\n元信息：${JSON.stringify(item.meta || {}, null, 2)}`,
  });
}

function hideNodeDetail() {
  const drawer = document.getElementById("detailDrawer");
  drawer.classList.remove("visible");
  drawer.setAttribute("aria-hidden", "true");
}

function renderSubgraph(centerId) {
  const svg = document.getElementById("subgraphSvg");
  svg.innerHTML = "";
  if (!centerId) {
    svg.innerHTML = `<text x="320" y="210" fill="#6b7280">暂无子图</text>`;
    return;
  }
  const relatedEdges = state.edges.filter(edge => edge.source === centerId || edge.target === centerId).slice(0, 10);
  const ids = new Set([centerId]);
  relatedEdges.forEach(edge => {
    ids.add(edge.source);
    ids.add(edge.target);
  });
  const nodes = Array.from(ids).map(id => state.nodes.find(node => node.id === id)).filter(Boolean);
  const positions = radialPositions(nodes, 380, 210, 150, centerId);
  drawGraph(svg, nodes, relatedEdges, positions, centerId, { compact: false });
}

function renderFullGraph() {
  const svg = document.getElementById("fullGraphSvg");
  svg.innerHTML = "";
  const nodes = state.nodes.filter(node => state.filter === "all" || node.category === state.filter);
  const ids = new Set(nodes.map(node => node.id));
  const edges = state.edges.filter(edge => ids.has(edge.source) && ids.has(edge.target));
  const maxGroupSize = Math.max(
    nodes.filter(node => node.category === "skill_process").length,
    nodes.filter(node => node.category === "tool_call").length,
    nodes.filter(node => node.category === "knowledge_entity").length,
  );
  const width = 1280;
  const height = Math.max(900, maxGroupSize * 34 + 120);
  state.graphBaseViewBox = { width, height };
  applyGraphZoom();
  const positions = layeredPositions(nodes, width, height);
  drawGraph(svg, nodes, edges, positions, state.selectedNodeId, { compact: true });
}

function radialPositions(nodes, cx, cy, radius, centerId) {
  const positions = new Map();
  const center = nodes.find(node => node.id === centerId) || nodes[0];
  if (center) positions.set(center.id, { x: cx, y: cy });
  const outer = nodes.filter(node => node.id !== center?.id);
  outer.forEach((node, i) => {
    const angle = (Math.PI * 2 * i) / Math.max(outer.length, 1) - Math.PI / 2;
    positions.set(node.id, { x: cx + Math.cos(angle) * radius, y: cy + Math.sin(angle) * radius });
  });
  return positions;
}

function layeredPositions(nodes, width, height) {
  const groups = [
    nodes.filter(node => node.category === "skill_process"),
    nodes.filter(node => node.category === "tool_call"),
    nodes.filter(node => node.category === "knowledge_entity"),
  ];
  const xs = [180, 620, 1060];
  const positions = new Map();
  groups.forEach((group, gi) => {
    const gap = height / (group.length + 1);
    group.forEach((node, i) => positions.set(node.id, { x: xs[gi], y: gap * (i + 1) }));
  });
  nodes.forEach((node, i) => {
    if (!positions.has(node.id)) positions.set(node.id, { x: 80 + (i * 80) % (width - 160), y: 80 + (i * 60) % (height - 160) });
  });
  return positions;
}

function drawGraph(svg, nodes, edges, positions, highlightId, options = {}) {
  const compact = Boolean(options.compact);
  const defs = document.createElementNS("http://www.w3.org/2000/svg", "defs");
  defs.innerHTML = `<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#a8b8ca"></path></marker>`;
  svg.appendChild(defs);

  edges.forEach(edge => {
    const a = positions.get(edge.source);
    const b = positions.get(edge.target);
    if (!a || !b) return;
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    const midX = (a.x + b.x) / 2;
    path.setAttribute("d", `M ${a.x} ${a.y} C ${midX} ${a.y}, ${midX} ${b.y}, ${b.x} ${b.y}`);
    path.setAttribute("class", `edge ${edge.source === highlightId || edge.target === highlightId ? "highlight" : ""}`);
    path.setAttribute("marker-end", "url(#arrow)");
    svg.appendChild(path);
  });

  nodes.forEach(node => {
    const p = positions.get(node.id);
    if (!p) return;
    const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
    g.setAttribute("class", "graph-node");
    g.addEventListener("click", () => {
      state.selectedNodeId = node.id;
      renderSubgraph(node.id);
      renderFullGraph();
      renderNodeCards();
      showNodeDetail(node);
    });
    const color = nodeColor(node);
    const isSelected = node.id === highlightId;
    if (node.category === "skill_process") {
      const rect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      const rectWidth = compact ? 150 : 156;
      const rectHeight = compact ? 50 : 56;
      rect.setAttribute("x", p.x - rectWidth / 2);
      rect.setAttribute("y", p.y - rectHeight / 2);
      rect.setAttribute("width", String(rectWidth));
      rect.setAttribute("height", String(rectHeight));
      rect.setAttribute("rx", "8");
      rect.setAttribute("fill", color);
      rect.setAttribute("stroke", isSelected ? "#111827" : "#fff");
      g.appendChild(rect);
    } else {
      const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      circle.setAttribute("cx", p.x);
      circle.setAttribute("cy", p.y);
      const radius = compact ? (node.category === "tool_call" ? 21 : 20) : (node.category === "tool_call" ? 31 : 27);
      circle.setAttribute("r", String(radius));
      circle.setAttribute("fill", color);
      circle.setAttribute("stroke", isSelected ? "#111827" : "#fff");
      g.appendChild(circle);
    }
    const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
    label.setAttribute("x", p.x);
    label.setAttribute("y", p.y + (node.category === "skill_process" ? (compact ? -1 : -2) : (compact ? 3 : 4)));
    label.setAttribute("text-anchor", "middle");
    label.setAttribute("class", `graph-label ${compact ? "compact" : ""}`);
    label.textContent = truncate(node.name, compact ? (node.category === "skill_process" ? 11 : 7) : (node.category === "skill_process" ? 12 : 8));
    g.appendChild(label);

    const sub = document.createElementNS("http://www.w3.org/2000/svg", "text");
    sub.setAttribute("x", p.x);
    sub.setAttribute("y", p.y + (node.category === "skill_process" ? (compact ? 14 : 15) : (compact ? 31 : 43)));
    sub.setAttribute("text-anchor", "middle");
    sub.setAttribute("class", `graph-sub ${compact ? "compact" : ""}`);
    sub.textContent = categoryLabel[node.category] || "";
    g.appendChild(sub);
    svg.appendChild(g);
  });
}

function applyGraphZoom() {
  const svg = document.getElementById("fullGraphSvg");
  if (!svg) return;
  const width = state.graphBaseViewBox.width / state.graphZoom;
  const height = state.graphBaseViewBox.height / state.graphZoom;
  const x = (state.graphBaseViewBox.width - width) / 2;
  const y = (state.graphBaseViewBox.height - height) / 2;
  svg.setAttribute("viewBox", `${x} ${y} ${width} ${height}`);
  svg.style.setProperty("--graph-label-scale", String(Math.min(1.8, Math.max(1, state.graphZoom))));
}

function adjustGraphZoom(delta) {
  state.graphZoom = Math.min(3, Math.max(0.55, Number((state.graphZoom + delta).toFixed(2))));
  applyGraphZoom();
}

function applyWorkflowZoom() {
  const canvas = document.getElementById("workflowCanvas");
  const row = canvas?.querySelector(".flow-row");
  if (!row) return;
  row.style.transform = `scale(${state.workflowZoom})`;
  row.style.transformOrigin = "top left";
  row.style.paddingRight = `${Math.max(24, (state.workflowZoom - 1) * 260)}px`;
  canvas.style.setProperty("--workflow-label-scale", String(Math.min(1.5, Math.max(0.85, state.workflowZoom))));
}

function adjustWorkflowZoom(delta) {
  state.workflowZoom = Math.min(2.2, Math.max(0.6, Number((state.workflowZoom + delta).toFixed(2))));
  applyWorkflowZoom();
}

function nodeColor(node) {
  if (node.category === "skill_process") return "#dbeafe";
  if (node.category === "knowledge_entity") return "#fff0df";
  if (node.tool_subtype === "mechanism_operator" || node.tool_subtype === "mechanism_operator_group" || node.tool_subtype === "mechanism_tool_group" || node.tool_subtype === "mechanism_model_tool") return "#eeebff";
  if (node.tool_subtype === "data_driven_model_tool" || node.tool_subtype === "data_driven_model_tool_group") return "#e7f0ff";
  if (node.tool_subtype === "data_query_tool" || node.tool_subtype === "data_query_tool_group") return "#d7f2e8";
  if (node.category === "tool_call") return "#d7f2e8";
  return "#edf2f7";
}

function setSummary(text) {
  document.getElementById("resultSummary").textContent = text;
}

function truncate(text, max) {
  return text.length > max ? text.slice(0, max - 1) + "…" : text;
}

function escapeHtml(text) {
  return String(text || "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function bindEvents() {
  document.querySelectorAll(".nav-item").forEach(btn => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".nav-item").forEach(item => item.classList.remove("active"));
      btn.classList.add("active");
      document.querySelectorAll(".tab-panel").forEach(panel => panel.classList.remove("active"));
      document.getElementById(`tab-${btn.dataset.tab}`).classList.add("active");
      if (btn.dataset.tab === "graph") renderFullGraph();
      if (btn.dataset.tab === "assets") {
        loadAssets();
        loadAdminOverview();
      }
    });
  });
  document.getElementById("searchBtn").addEventListener("click", runSearch);
  document.getElementById("queryInput").addEventListener("keydown", event => {
    if (event.key === "Enter") runSearch();
  });
  document.getElementById("resetQuery").addEventListener("click", () => {
    document.getElementById("queryInput").value = "";
    renderWorkflow(state.templates[0]);
    renderFallbackCards(state.nodes.slice(0, 6));
    renderSubgraph(state.nodes[0]?.id);
    setSummary(`默认展示：${state.templates[0].name}。`);
  });
  document.querySelectorAll(".filter-chip").forEach(btn => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".filter-chip").forEach(item => item.classList.remove("active"));
      btn.classList.add("active");
      state.filter = btn.dataset.filter;
      renderFullGraph();
      renderNodeCards();
    });
  });
  document.getElementById("nodeSearch").addEventListener("input", renderNodeCards);
  document.addEventListener("keydown", event => {
    if (!event.ctrlKey) return;
    const graphActive = document.getElementById("tab-graph").classList.contains("active");
    const workspaceActive = document.getElementById("tab-workspace").classList.contains("active");
    if (!graphActive && !workspaceActive) return;
    if (event.key === "-" || event.key === "_") {
      event.preventDefault();
      if (graphActive) adjustGraphZoom(-0.12);
      if (workspaceActive) adjustWorkflowZoom(-0.1);
    }
    if (event.key === "=" || event.key === "+") {
      event.preventDefault();
      if (graphActive) adjustGraphZoom(0.12);
      if (workspaceActive) adjustWorkflowZoom(0.1);
    }
    if (event.key === "0") {
      event.preventDefault();
      if (graphActive) {
        state.graphZoom = 1;
        applyGraphZoom();
      }
      if (workspaceActive) {
        state.workflowZoom = 1;
        applyWorkflowZoom();
      }
    }
  });
  document.getElementById("closeDetail").addEventListener("click", hideNodeDetail);
  document.getElementById("detailDrawer").addEventListener("click", event => {
    if (event.target.id === "detailDrawer") hideNodeDetail();
  });
  document.getElementById("instantPlanBtn").addEventListener("click", runInstantPlan);
  document.getElementById("refreshAssets").addEventListener("click", () => {
    loadAssets(document.getElementById("assetSearchInput").value.trim());
    loadAdminOverview();
  });
  document.getElementById("assetSearchBtn").addEventListener("click", () => loadAssets(document.getElementById("assetSearchInput").value.trim()));
  document.getElementById("assetSearchInput").addEventListener("keydown", event => {
    if (event.key === "Enter") loadAssets(document.getElementById("assetSearchInput").value.trim());
  });
  document.getElementById("registerBtn").addEventListener("click", registerItem);
}

bindEvents();
loadData().catch(error => {
  console.error(error);
  setSummary("数据加载失败。请通过本地HTTP服务打开 web_ui/index.html。");
});
