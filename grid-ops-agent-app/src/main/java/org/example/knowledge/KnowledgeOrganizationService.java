package org.example.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.example.graph.model.PlanStep;
import org.example.service.MockDataLoader;
import org.example.service.VectorEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class KnowledgeOrganizationService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeOrganizationService.class);
    private static final String BASE_PATH = "knowledge-organization/";
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("\\b[A-Z]{1,8}-[0-9A-Z]+(?:-[0-9A-Z]+)*\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPERATURE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:℃|C|c)");

    private static final Map<String, String> TOOL_ALIASES = Map.ofEntries(
            Map.entry("regulation_retriever", "searchSafetyRules"),
            Map.entry("source_scanner", "queryInternalDocs"),
            Map.entry("bge_rag", "queryInternalDocs"),
            Map.entry("plan_retriever", "queryInternalDocs"),
            Map.entry("graph_query", "queryKnowledgeGraph"),
            Map.entry("topology_analyzer", "analyzeTopology"),
            Map.entry("mock_power_check", "checkOperationRisk"),
            Map.entry("mock_stability_check", "checkOperationRisk"),
            Map.entry("operator_power_flow_calculation", "calculatePowerFlowEstimate"),
            Map.entry("power_flow_fast_estimator", "calculatePowerFlowEstimate"),
            Map.entry("operator_stability_calculation", "checkOperationRisk"),
            Map.entry("operator_fault_scenario_generation", "generateFaultScenario"),
            Map.entry("operator_spatial_feature_analysis", "analyzeTopology"),
            Map.entry("operator_time_series_prediction", "generateFaultScenario"),
            Map.entry("operator_unit_commitment_optimization", "checkOperationRisk"),
            Map.entry("operator_integer_optimization", "checkOperationRisk"),
            Map.entry("operator_stochastic_optimization", "checkOperationRisk"),
            Map.entry("operator_robust_optimization", "checkOperationRisk"),
            Map.entry("tool_weather_power_prediction", "generateFaultScenario")
    );

    private final ObjectMapper objectMapper;
    private final VectorEmbeddingService embeddingService;
    private final MockDataLoader mockDataLoader;
    private List<WorkflowTemplate> templates = List.of();
    private List<KnowledgeGraphNode> nodes = List.of();
    private List<KnowledgeGraphEdge> edges = List.of();
    private Map<String, Object> schema = Map.of();
    private Map<String, KnowledgeGraphNode> nodeById = Map.of();
    private Map<String, List<Float>> templateEmbeddings = Map.of();
    private Map<String, List<Float>> nodeEmbeddings = Map.of();
    private volatile boolean semanticIndexReady = false;

    public KnowledgeOrganizationService(ObjectMapper objectMapper,
                                        VectorEmbeddingService embeddingService,
                                        MockDataLoader mockDataLoader) {
        this.objectMapper = objectMapper;
        this.embeddingService = embeddingService;
        this.mockDataLoader = mockDataLoader;
    }

    @PostConstruct
    public void load() {
        try {
            templates = readJson("workflow_templates.json", new TypeReference<>() {});
            nodes = readJson("nodes.json", new TypeReference<>() {});
            edges = readJson("edges.json", new TypeReference<>() {});
            schema = readJson("schema.json", new TypeReference<>() {});
            nodeById = nodes.stream()
                    .filter(node -> node.getId() != null && !node.getId().isBlank())
                    .collect(Collectors.toMap(KnowledgeGraphNode::getId, node -> node, (a, b) -> a, LinkedHashMap::new));
            validateResources();
            logger.info("Knowledge organization loaded: templates={}, nodes={}, edges={}",
                    templates.size(), nodes.size(), edges.size());
        } catch (Exception e) {
            logger.error("Failed to load knowledge organization resources", e);
            templates = List.of();
            nodes = List.of();
            edges = List.of();
            schema = Map.of();
            nodeById = Map.of();
        }
    }

    public Map<String, Object> overview() {
        Map<String, Long> categories = nodes.stream()
                .collect(Collectors.groupingBy(node -> valueOr(node.getCategory(), "unknown"),
                        LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> roles = nodes.stream()
                .collect(Collectors.groupingBy(node -> valueOr(node.getRole(), "unknown"),
                        LinkedHashMap::new, Collectors.counting()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templates", templates.size());
        result.put("nodes", nodes.size());
        result.put("edges", edges.size());
        result.put("categories", categories);
        result.put("roles", roles);
        result.put("schema", schema);
        return result;
    }

    public List<WorkflowTemplate> templates() {
        return templates;
    }

    public Map<String, Object> graph(String category, String query) {
        String q = normalize(query);
        List<KnowledgeGraphNode> filteredNodes = nodes.stream()
                .filter(node -> category == null || category.isBlank() || category.equals(node.getCategory()))
                .filter(node -> q.isBlank() || searchableText(node).contains(q))
                .toList();
        Set<String> ids = filteredNodes.stream().map(KnowledgeGraphNode::getId).collect(Collectors.toSet());
        List<KnowledgeGraphEdge> filteredEdges = edges.stream()
                .filter(edge -> ids.contains(edge.getSource()) && ids.contains(edge.getTarget()))
                .toList();
        return Map.of("nodes", filteredNodes, "edges", filteredEdges);
    }

    public KnowledgeOrgMatch match(String query) {
        KnowledgeOrgMatch match = new KnowledgeOrgMatch();
        match.setQuery(query);

        Optional<Map.Entry<WorkflowTemplate, Double>> best = templates.stream()
                .map(template -> Map.entry(template, scoreTemplate(query, template)))
                .max(Comparator.comparingDouble(Map.Entry::getValue));
        if (best.isPresent() && best.get().getValue() > 0) {
            match.setTemplate(best.get().getKey());
            match.setScore(round(best.get().getValue()));
        } else if (!templates.isEmpty()) {
            match.setTemplate(templates.get(0));
            match.setScore(0);
            match.getWarnings().add("No strong workflow match; using the first template as a display fallback.");
        }

        List<KnowledgeGraphNode> candidates = candidateNodes(query, match.getTemplate());
        match.setCandidateNodes(candidates);
        match.setRecommendedTools(recommendedTools(match.getTemplate(), candidates));
        match.setExecutableSteps(executableStepSummary(match.getTemplate()));
        match.setPlanSteps(createExecutablePlan(match.getTemplate(), query));
        return match;
    }

    public Map<String, Object> instantPlan(String query) {
        KnowledgeOrgMatch match = match(query);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("query", query);
        result.put("template", match.getTemplate());
        result.put("score", match.getScore());
        result.put("candidate_nodes", match.getCandidateNodes());
        result.put("recommended_tools", match.getRecommendedTools());
        result.put("plan_steps", match.getPlanSteps());
        result.put("execution_graph", inferExecutionGraph(query));
        result.put("execution_precheck", buildExecutionPrecheck(query, match));
        result.put("warnings", match.getWarnings());
        result.put("match_strategy", "BGE_M3_SEMANTIC_PLUS_KEYWORD");
        result.put("template_status", "resource_seed_runtime_loaded");
        return result;
    }

    public String buildWorkflowContext(String query) {
        try {
            KnowledgeOrgMatch match = match(query);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("matched_template", templateSummary(match.getTemplate()));
            context.put("match_score", match.getScore());
            context.put("recommended_tools", match.getRecommendedTools());
            context.put("executable_steps", match.getExecutableSteps());
            context.put("plan_steps", match.getPlanSteps());
            context.put("execution_precheck", buildExecutionPrecheck(query, match));
            context.put("candidate_nodes", match.getCandidateNodes().stream().limit(8).toList());
            context.put("notes", List.of(
                    "Use this workflow as planning guidance.",
                    "Generate only executable GridOps tools or internal analysis steps.",
                    "Simulation operators must be treated as MOCK_ESTIMATE evidence."
            ));
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            logger.warn("Failed to build workflow context: {}", e.getMessage());
            return "";
        }
    }

    private Map<String, Object> templateSummary(WorkflowTemplate template) {
        if (template == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("template_id", template.getTemplateId());
        summary.put("scene", template.getScene());
        summary.put("name", template.getName());
        summary.put("description", template.getDescription());
        summary.put("keywords", template.getKeywords());
        return summary;
    }

    public Optional<KnowledgeGraphNode> node(String id) {
        return Optional.ofNullable(nodeById.get(id));
    }

    public List<KnowledgeGraphNode> relatedNodes(String centerId, int depth) {
        if (centerId == null || centerId.isBlank() || !nodeById.containsKey(centerId)) {
            return List.of();
        }
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        visited.add(centerId);
        queue.add(centerId);
        int remainingDepth = Math.max(1, depth);
        while (!queue.isEmpty() && remainingDepth-- > 0) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String current = queue.poll();
                for (KnowledgeGraphEdge edge : edges) {
                    String next = null;
                    if (Objects.equals(edge.getSource(), current)) {
                        next = edge.getTarget();
                    } else if (Objects.equals(edge.getTarget(), current)) {
                        next = edge.getSource();
                    }
                    if (next != null && visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
        }
        return visited.stream().map(nodeById::get).filter(Objects::nonNull).toList();
    }

    public List<KnowledgeGraphEdge> relatedEdges(Set<String> ids) {
        return edges.stream()
                .filter(edge -> ids.contains(edge.getSource()) && ids.contains(edge.getTarget()))
                .toList();
    }

    public static String mapToolName(String nariTool) {
        if (nariTool == null || nariTool.isBlank() || "slot_filler".equals(nariTool) || "llm".equalsIgnoreCase(nariTool)) {
            return null;
        }
        return TOOL_ALIASES.getOrDefault(nariTool, nariTool);
    }

    private List<Map<String, Object>> executableStepSummary(WorkflowTemplate template) {
        if (template == null) {
            return List.of();
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        int i = 1;
        for (WorkflowStep step : template.getWorkflow()) {
            String mapped = mapToolName(step.getTool());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order", i++);
            row.put("step", step.getStep());
            row.put("source_tool", step.getTool());
            row.put("gridops_tool", mapped);
            row.put("executable", mapped != null);
            row.put("mock_or_estimate", mapped != null && Set.of(
                    "calculatePowerFlowEstimate", "checkOperationRisk", "generateFaultScenario").contains(mapped));
            steps.add(row);
        }
        return steps;
    }

    private List<PlanStep> createExecutablePlan(WorkflowTemplate template, String query) {
        if (template == null) {
            return List.of();
        }
        List<PlanStep> plan = new ArrayList<>();
        int index = 1;
        Set<String> seen = new HashSet<>();
        Map<String, String> slotValues = inferSlotValues(template, query);
        for (WorkflowStep step : template.getWorkflow()) {
            String tool = mapToolName(step.getTool());
            if (tool == null || !seen.add(index + ":" + tool)) {
                continue;
            }
            Map<String, Object> params = replacePlaceholders(step.getParams(), slotValues);
            params.putIfAbsent("query", query);
            plan.add(PlanStep.builder()
                    .stepId(String.format("workflow-%03d", index))
                    .stepNo(index)
                    .step(index)
                    .stepType("TOOL_CALL")
                    .action(valueOr(step.getStep(), "Execute workflow step"))
                    .toolName(tool)
                    .tool(tool)
                    .params(params)
                    .purpose("Mapped from knowledge organization workflow: " + template.getName())
                    .expected("Structured evidence for diagnosis or operation analysis")
                    .dependsOn(index == 1 ? List.of() : List.of(String.format("workflow-%03d", index - 1)))
                    .status("PENDING")
                    .retryCount(0)
                    .required(!Set.of("calculatePowerFlowEstimate", "checkOperationRisk", "generateFaultScenario").contains(tool))
                    .build());
            index++;
            if (plan.size() >= 8) {
                break;
            }
        }
        return plan;
    }

    private Map<String, Object> buildExecutionPrecheck(String query, KnowledgeOrgMatch match) {
        List<PlanStep> steps = match.getPlanSteps();
        String graphName = inferExecutionGraph(query);
        String deviceId = inferSlotValues(match.getTemplate(), query).get("device_id");
        List<Map<String, Object>> checks = new ArrayList<>();
        int ready = 0;
        int estimate = 0;
        int missing = 0;
        for (PlanStep step : steps) {
            Map<String, Object> check = checkPlanStepReadiness(step, deviceId, query);
            checks.add(check);
            String status = String.valueOf(check.get("status"));
            if ("READY".equals(status)) {
                ready++;
            } else if ("SEMI_REAL_ESTIMATE".equals(status) || "RAG_RUNTIME".equals(status)) {
                estimate++;
            } else {
                missing++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_steps", checks.size());
        summary.put("ready_steps", ready);
        summary.put("semi_real_or_runtime_steps", estimate);
        summary.put("missing_steps", missing);
        summary.put("device_id", deviceId);
        summary.put("matched_template", match.getTemplate() == null ? "" : match.getTemplate().getName());

        Map<String, Object> precheck = new LinkedHashMap<>();
        precheck.put("graph_name", graphName);
        precheck.put("summary", summary);
        precheck.put("checks", checks);
        precheck.put("handoff", "运行诊断控制台将按该 Graph 路由重新装载上下文并实际执行工具。");
        return precheck;
    }

    private Map<String, Object> checkPlanStepReadiness(PlanStep step, String deviceId, String query) {
        String tool = step.effectiveToolName();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("step_no", step.getStepNo());
        row.put("action", step.getAction());
        row.put("tool", tool);
        row.put("tool_label", toolLabel(tool));
        row.put("required", step.effectiveRequired());

        if (tool == null || tool.isBlank()) {
            row.put("status", "DATA_MISSING");
            row.put("status_label", "不可执行");
            row.put("evidence", "未映射到 GridOps 工具");
            row.put("blocking", true);
            return row;
        }
        if (Set.of("calculatePowerFlowEstimate", "checkOperationRisk", "generateFaultScenario").contains(tool)) {
            row.put("status", "SEMI_REAL_ESTIMATE");
            row.put("status_label", "半真实推演");
            row.put("evidence", "基于 mock-data、知识图谱和规则阈值估算，正式结论需 EMS/DTS 复核");
            row.put("blocking", false);
            return row;
        }
        if ("queryInternalDocs".equals(tool)) {
            row.put("status", "RAG_RUNTIME");
            row.put("status_label", "运行时检索");
            row.put("evidence", "进入 Graph 后调用本地 BGE/Milvus RAG，当前检查仅确认链路类型");
            row.put("blocking", false);
            return row;
        }
        if ("queryKnowledgeGraph".equals(tool) || "analyzeTopology".equals(tool)) {
            boolean hit = !candidateNodes(query, null).isEmpty();
            row.put("status", hit ? "READY" : "DATA_MISSING");
            row.put("status_label", hit ? "图谱可用" : "图谱未命中");
            row.put("evidence", hit ? "知识组织图谱存在候选节点" : "需补充设备台账或拓扑节点");
            row.put("blocking", false);
            return row;
        }
        if ("searchSafetyRules".equals(tool)) {
            boolean hit = !mockDataLoader.searchSafetyRules(query, "").isEmpty();
            row.put("status", hit ? "READY" : "DATA_MISSING");
            row.put("status_label", hit ? "规程可用" : "规程缺失");
            row.put("evidence", hit ? "mock-data/safety_rules.json 可命中相关规程" : "安全规程样例未命中当前任务");
            row.put("blocking", !hit && step.effectiveRequired());
            return row;
        }
        boolean hasDevice = deviceId != null && mockDataLoader.getDeviceProfile(deviceId).isPresent();
        boolean hasStatus = deviceId != null && !mockDataLoader.getDeviceStatusMock(deviceId).isEmpty();
        boolean hasAlarms = deviceId != null && !mockDataLoader.getAlarmHistory(deviceId).isEmpty();
        boolean hasLogs = deviceId != null && !mockDataLoader.getDeviceLogs(deviceId).isEmpty();
        boolean hasTickets = deviceId != null && !mockDataLoader.getDefectTickets(deviceId).isEmpty();
        boolean ready = switch (tool) {
            case "getDeviceProfile" -> hasDevice;
            case "getDeviceStatus" -> hasStatus;
            case "getAlarmHistory" -> hasAlarms;
            case "getDeviceLogs" -> hasLogs;
            case "getDefectTickets" -> hasTickets;
            default -> true;
        };
        row.put("status", ready ? "READY" : "DATA_MISSING");
        row.put("status_label", ready ? "样例数据可用" : "样例数据缺失");
        row.put("evidence", ready
                ? "mock-data 已包含 " + valueOr(deviceId, "相关对象") + " 的可查询样例"
                : "当前样例库缺少 " + valueOr(deviceId, "目标对象") + " 对应数据");
        row.put("blocking", !ready && step.effectiveRequired());
        return row;
    }

    private String inferExecutionGraph(String query) {
        String normalized = normalize(query);
        if (normalized.contains("告警") || normalized.contains("油温") || normalized.contains("阈值") || normalized.contains("alarm")) {
            return "alarm_diagnosis_graph";
        }
        return "power_ops_workflow";
    }

    private String toolLabel(String tool) {
        if (tool == null) {
            return "未映射工具";
        }
        return switch (tool) {
            case "getDeviceProfile" -> "设备档案查询";
            case "getDeviceStatus" -> "设备状态查询";
            case "getAlarmHistory" -> "历史告警查询";
            case "getDeviceLogs" -> "运行日志查询";
            case "getDefectTickets" -> "缺陷工单查询";
            case "searchSafetyRules" -> "安全规程检索";
            case "queryInternalDocs" -> "知识库检索";
            case "queryKnowledgeGraph" -> "知识图谱查询";
            case "analyzeTopology" -> "拓扑影响分析";
            case "calculatePowerFlowEstimate" -> "潮流影响估算";
            case "checkOperationRisk" -> "运行风险校核";
            case "generateFaultScenario" -> "故障场景推演";
            default -> tool;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> replacePlaceholders(Object value, Map<String, String> slotValues) {
        Object replaced = replacePlaceholderValue(value, slotValues);
        if (replaced instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private Object replacePlaceholderValue(Object value, Map<String, String> slotValues) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            String replaced = text;
            for (Map.Entry<String, String> entry : slotValues.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    replaced = replaced.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }
            return replaced;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> replaced = new LinkedHashMap<>();
            map.forEach((key, item) -> replaced.put(String.valueOf(key), replacePlaceholderValue(item, slotValues)));
            return replaced;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> replacePlaceholderValue(item, slotValues)).toList();
        }
        return value;
    }

    private Map<String, String> inferSlotValues(WorkflowTemplate template, String query) {
        Map<String, String> values = new LinkedHashMap<>();
        String safeQuery = valueOr(query, "");
        String normalized = safeQuery.toLowerCase(Locale.ROOT);

        Matcher deviceMatcher = DEVICE_ID_PATTERN.matcher(safeQuery);
        if (deviceMatcher.find()) {
            String deviceId = deviceMatcher.group().toUpperCase(Locale.ROOT);
            values.put("device_id", deviceId);
            values.put("related_entity", deviceId);
            values.put("source_entity", deviceId);
            values.put("fault_entity", deviceId);
            values.put("outage_entity", deviceId);
            values.put("adjustment_object", deviceId);
            values.put("calculation_area", deviceId);
        } else {
            firstDefaultEntity(template).ifPresent(entity -> {
                values.put("device_id", entity);
                values.put("related_entity", entity);
                values.put("source_entity", entity);
            });
        }

        List<String> temperatures = new ArrayList<>();
        Matcher tempMatcher = TEMPERATURE_PATTERN.matcher(safeQuery);
        while (tempMatcher.find()) {
            temperatures.add(tempMatcher.group(1));
        }
        if (!temperatures.isEmpty()) {
            values.put("oil_temperature", temperatures.get(0));
        }
        if (temperatures.size() > 1) {
            values.put("threshold", temperatures.get(1));
        } else if (normalized.contains("80") && (normalized.contains("阈值") || normalized.contains("threshold"))) {
            values.put("threshold", "80");
        }

        if (normalized.contains("n-1")) {
            values.put("contingency_type", "N-1");
            values.put("fault_type", "N-1");
            values.put("check_items", "N-1 risk check");
        }
        if (normalized.contains("油温")) {
            values.put("fault_type", "oil temperature abnormal");
            values.put("operation_type", "transformer oil temperature diagnosis");
        }
        values.putIfAbsent("station", safeQuery);
        values.putIfAbsent("affected_area", safeQuery);
        values.putIfAbsent("target_entities", safeQuery);
        values.putIfAbsent("operation_mode", safeQuery);
        values.putIfAbsent("snapshot_time", "current");
        values.putIfAbsent("calculation_goal", safeQuery);
        return values;
    }

    private Optional<String> firstDefaultEntity(WorkflowTemplate template) {
        Object defaultEntities = template.getExtra().get("default_entities");
        if (defaultEntities instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first != null && !String.valueOf(first).isBlank()) {
                return Optional.of(String.valueOf(first));
            }
        }
        return Optional.empty();
    }

    private List<String> recommendedTools(WorkflowTemplate template, List<KnowledgeGraphNode> candidates) {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        if (template != null) {
            template.getWorkflow().forEach(step -> {
                String mapped = mapToolName(step.getTool());
                if (mapped != null) {
                    tools.add(mapped);
                }
            });
        }
        candidates.stream()
                .filter(node -> "tool_call".equals(node.getCategory()))
                .map(KnowledgeGraphNode::getId)
                .map(id -> id.replaceFirst("^tool_", ""))
                .map(KnowledgeOrganizationService::mapToolName)
                .filter(Objects::nonNull)
                .forEach(tools::add);
        return tools.stream().limit(10).toList();
    }

    private List<KnowledgeGraphNode> candidateNodes(String query, WorkflowTemplate template) {
        ensureSemanticIndex();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (template != null) {
            String workflowId = "workflow_" + template.getTemplateId();
            ids.add(workflowId);
            edges.stream()
                    .filter(edge -> workflowId.equals(edge.getSource()) || workflowId.equals(edge.getTarget()))
                    .sorted(Comparator.comparing(edge -> edge.getOrder() == null ? 999 : edge.getOrder()))
                    .forEach(edge -> {
                        ids.add(edge.getSource());
                        ids.add(edge.getTarget());
                    });
        }
        String q = normalize(query);
        List<Float> queryVector = embeddingOrNull(query);
        nodes.stream()
                .map(node -> Map.entry(node, scoreNode(q, queryVector, node)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(12)
                .forEach(entry -> ids.add(entry.getKey().getId()));
        return ids.stream().map(nodeById::get).filter(Objects::nonNull).limit(16).toList();
    }

    private double scoreTemplate(String query, WorkflowTemplate template) {
        ensureSemanticIndex();
        String q = normalize(query);
        if (q.isBlank()) {
            return 0;
        }
        double lexical = 0;
        lexical += textScore(q, template.getName(), 4);
        lexical += textScore(q, template.getDescription(), 2);
        lexical += textScore(q, template.getScene(), 2);
        for (String keyword : template.getKeywords()) {
            lexical += textScore(q, keyword, 5);
        }
        for (WorkflowStep step : template.getWorkflow()) {
            lexical += textScore(q, step.getStep(), 1.5);
            lexical += textScore(q, step.getTool(), 1);
        }
        double semantic = semanticScore(query, templateEmbeddings.get(template.getTemplateId()));
        return combinedScore(semantic, lexical);
    }

    private double scoreNode(String normalizedQuery, List<Float> queryVector, KnowledgeGraphNode node) {
        if (normalizedQuery.isBlank()) {
            return 0;
        }
        double lexical = textScore(normalizedQuery, node.getName(), 3);
        lexical += textScore(normalizedQuery, node.getDescription(), 1.5);
        lexical += textScore(normalizedQuery, node.getCategory(), 1);
        lexical += textScore(normalizedQuery, node.getRole(), 1);
        for (String keyword : node.getKeywords()) {
            lexical += textScore(normalizedQuery, keyword, 3);
        }
        double semantic = 0;
        if (queryVector != null) {
            List<Float> nodeVector = nodeEmbeddings.get(node.getId());
            if (nodeVector != null && nodeVector.size() == queryVector.size()) {
                semantic = Math.max(0, embeddingService.calculateCosineSimilarity(queryVector, nodeVector));
            }
        }
        return combinedScore(semantic, lexical);
    }

    private void ensureSemanticIndex() {
        if (semanticIndexReady) {
            return;
        }
        synchronized (this) {
            if (semanticIndexReady) {
                return;
            }
            try {
                Map<String, List<Float>> templateIndex = new LinkedHashMap<>();
                for (WorkflowTemplate template : templates) {
                    if (template.getTemplateId() != null) {
                        templateIndex.put(template.getTemplateId(), embeddingService.generateEmbedding(templateSemanticText(template)));
                    }
                }
                Map<String, List<Float>> nodeIndex = new LinkedHashMap<>();
                for (KnowledgeGraphNode node : nodes) {
                    if (node.getId() != null) {
                        nodeIndex.put(node.getId(), embeddingService.generateEmbedding(searchableText(node)));
                    }
                }
                templateEmbeddings = templateIndex;
                nodeEmbeddings = nodeIndex;
                semanticIndexReady = true;
                logger.info("Knowledge organization semantic index ready: templates={}, nodes={}",
                        templateEmbeddings.size(), nodeEmbeddings.size());
            } catch (Exception e) {
                semanticIndexReady = true;
                templateEmbeddings = Map.of();
                nodeEmbeddings = Map.of();
                logger.warn("Knowledge organization semantic index unavailable, falling back to keyword match: {}", e.getMessage());
            }
        }
    }

    private double semanticScore(String query, List<Float> targetVector) {
        if (targetVector == null || targetVector.isEmpty()) {
            return 0;
        }
        List<Float> queryVector = embeddingOrNull(query);
        if (queryVector == null || queryVector.size() != targetVector.size()) {
            return 0;
        }
        return Math.max(0, embeddingService.calculateCosineSimilarity(queryVector, targetVector));
    }

    private List<Float> embeddingOrNull(String text) {
        try {
            return embeddingService.generateEmbedding(text);
        } catch (Exception e) {
            logger.debug("Failed to generate semantic match embedding: {}", e.getMessage());
            return null;
        }
    }

    private double combinedScore(double semantic, double lexical) {
        double lexicalNormalized = lexical <= 0 ? 0 : lexical / (lexical + 6.0);
        return semantic * 8.0 + lexicalNormalized * 4.0;
    }

    private String templateSemanticText(WorkflowTemplate template) {
        StringBuilder text = new StringBuilder();
        text.append(valueOr(template.getName(), "")).append("。")
                .append(valueOr(template.getScene(), "")).append("。")
                .append(valueOr(template.getDescription(), "")).append("。")
                .append(String.join("，", template.getKeywords())).append("。");
        for (WorkflowStep step : template.getWorkflow()) {
            text.append(valueOr(step.getStep(), "")).append("。")
                    .append(valueOr(step.getTool(), "")).append("。");
        }
        return text.toString();
    }

    private double textScore(String normalizedQuery, String text, double weight) {
        String normalizedText = normalize(text);
        if (normalizedText.isBlank()) {
            return 0;
        }
        double score = 0;
        if (normalizedQuery.contains(normalizedText) || normalizedText.contains(normalizedQuery)) {
            score += weight;
        }
        for (String token : normalizedQuery.split("[\\s,.;:，。；：、/\\\\()（）\\[\\]{}]+")) {
            if (token.length() > 1 && normalizedText.contains(token)) {
                score += weight * 0.35;
            }
        }
        return score;
    }

    private String searchableText(KnowledgeGraphNode node) {
        return normalize(String.join(" ",
                valueOr(node.getId(), ""),
                valueOr(node.getName(), ""),
                valueOr(node.getCategory(), ""),
                valueOr(node.getRole(), ""),
                valueOr(node.getDescription(), ""),
                String.join(" ", node.getKeywords())));
    }

    private void validateResources() {
        List<String> warnings = new ArrayList<>();
        for (KnowledgeGraphEdge edge : edges) {
            if (!nodeById.containsKey(edge.getSource()) || !nodeById.containsKey(edge.getTarget())) {
                warnings.add("Broken edge: " + edge.getSource() + " -> " + edge.getTarget());
            }
        }
        for (WorkflowTemplate template : templates) {
            if (template.getTemplateId() == null || template.getTemplateId().isBlank()) {
                warnings.add("Workflow template without template_id");
            }
            if (template.getWorkflow().isEmpty()) {
                warnings.add("Workflow template without steps: " + template.getTemplateId());
            }
        }
        if (!warnings.isEmpty()) {
            logger.warn("Knowledge organization validation warnings: {}", warnings);
        }
    }

    private <T> T readJson(String name, TypeReference<T> type) throws Exception {
        ClassPathResource resource = new ClassPathResource(BASE_PATH + name);
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, type);
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
