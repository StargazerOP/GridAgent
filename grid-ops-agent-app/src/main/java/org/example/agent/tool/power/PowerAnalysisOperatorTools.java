package org.example.agent.tool.power;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.knowledge.KnowledgeGraphNode;
import org.example.knowledge.KnowledgeOrganizationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PowerAnalysisOperatorTools {

    private final KnowledgeOrganizationService knowledgeOrganizationService;
    private final ObjectMapper objectMapper;

    public PowerAnalysisOperatorTools(KnowledgeOrganizationService knowledgeOrganizationService,
                                      ObjectMapper objectMapper) {
        this.knowledgeOrganizationService = knowledgeOrganizationService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Query the migrated GridOps knowledge organization graph for workflows, tools, equipment, rules, documents and data assets.")
    public String queryKnowledgeGraph(
            @ToolParam(description = "Natural language query, entity name, workflow name, tool name or keyword.") String query,
            @ToolParam(description = "Optional node category filter: skill_process, tool_call, knowledge_entity.") String category,
            @ToolParam(description = "Maximum number of nodes to return.") Integer limit) {
        try {
            Map<String, Object> graph = knowledgeOrganizationService.graph(category, query);
            List<?> nodes = ((List<?>) graph.getOrDefault("nodes", List.of())).stream()
                    .limit(limit == null || limit <= 0 ? 20 : limit)
                    .toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "RESOURCE_GRAPH_QUERY");
            result.put("query", query);
            result.put("category", category);
            result.put("nodes", nodes);
            result.put("edges", graph.getOrDefault("edges", List.of()));
            result.put("source", "classpath:knowledge-organization");
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return error(e);
        }
    }

    @Tool(description = "Analyze graph topology around a power grid entity or workflow and return related nodes, relation edges and weak-link hints.")
    public String analyzeTopology(
            @ToolParam(description = "Center node id or entity/workflow keyword.") String center,
            @ToolParam(description = "Traversal depth, usually 1 or 2.") Integer depth) {
        try {
            KnowledgeGraphNode centerNode = resolveCenter(center);
            List<KnowledgeGraphNode> related = centerNode == null
                    ? List.of()
                    : knowledgeOrganizationService.relatedNodes(centerNode.getId(), depth == null ? 2 : depth);
            Set<String> ids = related.stream().map(KnowledgeGraphNode::getId).collect(Collectors.toSet());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "RESOURCE_TOPOLOGY_ANALYSIS");
            result.put("center", center);
            result.put("center_node", centerNode);
            result.put("related_nodes", related);
            result.put("related_edges", knowledgeOrganizationService.relatedEdges(ids));
            result.put("weak_link_hints", related.stream()
                    .filter(node -> "knowledge_entity".equals(node.getCategory()))
                    .limit(5)
                    .map(node -> Map.of(
                            "node_id", node.getId(),
                            "name", node.getName(),
                            "reason", "Related knowledge/rule/entity may constrain the operation path."))
                    .toList());
            result.put("source", "classpath:knowledge-organization");
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return error(e);
        }
    }

    @Tool(description = "Return a mock/estimated power-flow calculation result for operation planning demos. The result is not a real EMS calculation.")
    public String calculatePowerFlowEstimate(
            @ToolParam(description = "Calculation area, station, line, transformer or affected region.") String area,
            @ToolParam(description = "Operation mode, adjustment mode or scenario name.") String mode,
            @ToolParam(description = "Optional scenario description.") String scenario) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "MOCK_ESTIMATE");
            result.put("operator", "calculatePowerFlowEstimate");
            result.put("area", area);
            result.put("operation_mode", mode);
            result.put("scenario", scenario);
            result.put("timestamp", LocalDateTime.now().toString());
            result.put("summary", "Estimated load-flow impact based on migrated workflow knowledge and mock engineering assumptions.");
            result.put("metrics", Map.of(
                    "max_line_loading_percent", 86,
                    "min_bus_voltage_pu", 0.965,
                    "voltage_violation_count", 0,
                    "thermal_violation_count", 1,
                    "convergence", "estimated_converged"));
            result.put("risk_points", List.of(
                    "Monitor overloaded tie-line or transformer after load transfer.",
                    "Run a real EMS/DTS power-flow calculation before dispatch execution.",
                    "Review N-1 constraints for the affected area."));
            result.put("data_quality", "mock_first_version");
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return error(e);
        }
    }

    @Tool(description = "Check operation risk using migrated workflow knowledge, graph context and mock engineering rules.")
    public String checkOperationRisk(
            @ToolParam(description = "Operation type or contingency type.") String operationType,
            @ToolParam(description = "Target entity, device, station, line or affected area.") String target,
            @ToolParam(description = "Additional query or context.") String context) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "MOCK_RISK_CHECK");
            result.put("operator", "checkOperationRisk");
            result.put("operation_type", operationType);
            result.put("target", target);
            result.put("context", context);
            result.put("risk_level", inferRiskLevel(operationType, context));
            result.put("findings", List.of(
                    Map.of("type", "constraint", "level", "MEDIUM", "message", "Check dispatch rules, protection coordination and load-flow limits before execution."),
                    Map.of("type", "evidence", "level", "LOW", "message", "Use real-time device status, alarm history and safety rules as required evidence."),
                    Map.of("type", "human_review", "level", "HIGH", "message", "Manual review is required before any switching or dispatch operation.")));
            result.put("recommended_tools", List.of("queryKnowledgeGraph", "queryInternalDocs", "searchSafetyRules", "calculatePowerFlowEstimate"));
            result.put("data_quality", "mock_first_version");
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return error(e);
        }
    }

    @Tool(description = "Generate mock fault scenarios and handling focus points for planning and diagnosis.")
    public String generateFaultScenario(
            @ToolParam(description = "Fault entity, station, line, transformer or area.") String faultEntity,
            @ToolParam(description = "Fault type or contingency type.") String faultType,
            @ToolParam(description = "Optional extra context.") String context) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "MOCK_SCENARIO_GENERATION");
            result.put("operator", "generateFaultScenario");
            result.put("fault_entity", faultEntity);
            result.put("fault_type", faultType);
            result.put("context", context);
            result.put("scenarios", List.of(
                    Map.of("name", "base_contingency", "probability", "medium", "focus", "Confirm affected devices and protection action sequence."),
                    Map.of("name", "expanded_outage", "probability", "low", "focus", "Assess load transfer path and important-user restoration priority."),
                    Map.of("name", "recovery_with_constraint", "probability", "medium", "focus", "Check voltage, thermal and safety-rule constraints before restoration.")));
            result.put("required_evidence", List.of("device status", "alarm history", "defect tickets", "safety rules", "workflow graph context"));
            result.put("data_quality", "mock_first_version");
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return error(e);
        }
    }

    private KnowledgeGraphNode resolveCenter(String center) {
        if (center == null || center.isBlank()) {
            return null;
        }
        return knowledgeOrganizationService.node(center)
                .orElseGet(() -> ((List<KnowledgeGraphNode>) knowledgeOrganizationService.graph(null, center)
                        .getOrDefault("nodes", List.<KnowledgeGraphNode>of()))
                        .stream()
                        .findFirst()
                        .orElse(null));
    }

    private String inferRiskLevel(String operationType, String context) {
        String text = ((operationType == null ? "" : operationType) + " " + (context == null ? "" : context)).toLowerCase();
        if (text.contains("n-1") || text.contains("fault") || text.contains("故障") || text.contains("全停")) {
            return "HIGH";
        }
        if (text.contains("transfer") || text.contains("转供") || text.contains("检修")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String error(Exception e) {
        try {
            return objectMapper.writeValueAsString(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception ignored) {
            return "{\"status\":\"error\"}";
        }
    }
}
