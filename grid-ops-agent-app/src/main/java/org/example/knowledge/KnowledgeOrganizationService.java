package org.example.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.example.graph.model.PlanStep;
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
import java.util.stream.Collectors;

@Service
public class KnowledgeOrganizationService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeOrganizationService.class);
    private static final String BASE_PATH = "knowledge-organization/";

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
            Map.entry("tool_weather_power_prediction", "generateFaultScenario"),
            Map.entry("llm", "queryInternalDocs")
    );

    private final ObjectMapper objectMapper;
    private List<WorkflowTemplate> templates = List.of();
    private List<KnowledgeGraphNode> nodes = List.of();
    private List<KnowledgeGraphEdge> edges = List.of();
    private Map<String, Object> schema = Map.of();
    private Map<String, KnowledgeGraphNode> nodeById = Map.of();

    public KnowledgeOrganizationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        result.put("warnings", match.getWarnings());
        result.put("template_status", "resource_seed_not_persisted");
        return result;
    }

    public String buildWorkflowContext(String query) {
        try {
            KnowledgeOrgMatch match = match(query);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("matched_template", match.getTemplate());
            context.put("match_score", match.getScore());
            context.put("recommended_tools", match.getRecommendedTools());
            context.put("executable_steps", match.getExecutableSteps());
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
        if (nariTool == null || nariTool.isBlank() || "slot_filler".equals(nariTool)) {
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
        for (WorkflowStep step : template.getWorkflow()) {
            String tool = mapToolName(step.getTool());
            if (tool == null || !seen.add(index + ":" + tool)) {
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>(step.getParams());
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
        nodes.stream()
                .map(node -> Map.entry(node, scoreNode(q, node)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(12)
                .forEach(entry -> ids.add(entry.getKey().getId()));
        return ids.stream().map(nodeById::get).filter(Objects::nonNull).limit(16).toList();
    }

    private double scoreTemplate(String query, WorkflowTemplate template) {
        String q = normalize(query);
        if (q.isBlank()) {
            return 0;
        }
        double score = 0;
        score += textScore(q, template.getName(), 4);
        score += textScore(q, template.getDescription(), 2);
        score += textScore(q, template.getScene(), 2);
        for (String keyword : template.getKeywords()) {
            score += textScore(q, keyword, 5);
        }
        for (WorkflowStep step : template.getWorkflow()) {
            score += textScore(q, step.getStep(), 1.5);
            score += textScore(q, step.getTool(), 1);
        }
        return score;
    }

    private double scoreNode(String normalizedQuery, KnowledgeGraphNode node) {
        if (normalizedQuery.isBlank()) {
            return 0;
        }
        double score = textScore(normalizedQuery, node.getName(), 3);
        score += textScore(normalizedQuery, node.getDescription(), 1.5);
        score += textScore(normalizedQuery, node.getCategory(), 1);
        score += textScore(normalizedQuery, node.getRole(), 1);
        for (String keyword : node.getKeywords()) {
            score += textScore(normalizedQuery, keyword, 3);
        }
        return score;
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
