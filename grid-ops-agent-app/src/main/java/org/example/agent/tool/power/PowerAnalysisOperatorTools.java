package org.example.agent.tool.power;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.knowledge.KnowledgeGraphNode;
import org.example.knowledge.KnowledgeOrganizationService;
import org.example.service.MockDataLoader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class PowerAnalysisOperatorTools {

    private final KnowledgeOrganizationService knowledgeOrganizationService;
    private final ObjectMapper objectMapper;
    private final MockDataLoader mockDataLoader;
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("\\b[A-Z]{1,8}-[0-9A-Z]+(?:-[0-9A-Z]+)*\\b", Pattern.CASE_INSENSITIVE);

    public PowerAnalysisOperatorTools(KnowledgeOrganizationService knowledgeOrganizationService,
                                      ObjectMapper objectMapper,
                                      MockDataLoader mockDataLoader) {
        this.knowledgeOrganizationService = knowledgeOrganizationService;
        this.objectMapper = objectMapper;
        this.mockDataLoader = mockDataLoader;
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
            String deviceId = inferDeviceId(area, mode, scenario);
            Map<String, Object> status = deviceId == null ? Map.of() : mockDataLoader.getDeviceStatusMock(deviceId);
            Map<String, Object> profile = deviceId == null ? Map.of() : mockDataLoader.getDeviceProfileOrDefault(deviceId);
            int loadRate = parsePercent(status.get("loadRate"), scenarioContains(scenario, "转供", "transfer") ? 84 : 72);
            int estimatedLoading = Math.min(128, Math.max(45, loadRate + loadingDelta(mode, scenario)));
            double voltage = Math.max(0.91, Math.round((1.02 - Math.max(0, estimatedLoading - 70) * 0.0022) * 1000.0) / 1000.0);
            int thermalViolations = estimatedLoading >= 95 ? 1 : 0;
            int voltageViolations = voltage < 0.95 ? 1 : 0;
            List<Map<String, Object>> relatedNodes = deviceId == null ? List.of()
                    : knowledgeOrganizationService.relatedNodes(deviceId, 2).stream()
                    .limit(6)
                    .map(node -> Map.<String, Object>of("id", node.getId(), "name", node.getName(), "category", node.getCategory()))
                    .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "SEMI_REAL_RULE_ESTIMATE");
            result.put("operator", "calculatePowerFlowEstimate");
            result.put("device_id", deviceId);
            result.put("area", area);
            result.put("operation_mode", mode);
            result.put("scenario", scenario);
            result.put("timestamp", LocalDateTime.now().toString());
            result.put("summary", "基于演示设备状态、知识图谱邻接关系和规则阈值的潮流影响估算，不等同于 EMS/DTS 在线潮流。");
            result.put("metrics", Map.of(
                    "max_line_loading_percent", estimatedLoading,
                    "source_load_rate_percent", loadRate,
                    "min_bus_voltage_pu", voltage,
                    "voltage_violation_count", voltageViolations,
                    "thermal_violation_count", thermalViolations,
                    "convergence", "estimated_converged"));
            result.put("risk_points", buildPowerFlowRiskPoints(estimatedLoading, voltage, status, scenario));
            result.put("evidence_snapshot", Map.of(
                    "profile", compact(profile, "deviceName", "deviceType", "station", "ratedCapacity", "normalLoadRate"),
                    "status", compact(status, "loadRate", "oilTemperature", "coolerStatus", "alarmPresent", "diagnosticFlags"),
                    "graph_related_nodes", relatedNodes));
            result.put("data_quality", "mock_data_plus_graph_rule_estimate");
            result.put("availability", "requires_ems_integration");
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
            String deviceId = inferDeviceId(operationType, target, context);
            Map<String, Object> status = deviceId == null ? Map.of() : mockDataLoader.getDeviceStatusMock(deviceId);
            Map<String, Object> profile = deviceId == null ? Map.of() : mockDataLoader.getDeviceProfileOrDefault(deviceId);
            List<Map<String, Object>> alarms = deviceId == null ? List.of() : mockDataLoader.getAlarmHistory(deviceId);
            List<Map<String, Object>> tickets = deviceId == null ? List.of() : mockDataLoader.getDefectTickets(deviceId);
            int score = riskScore(operationType, context, status, alarms, tickets);
            List<Map<String, Object>> findings = buildRiskFindings(score, status, alarms, tickets, operationType, context);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "SEMI_REAL_RULE_RISK_CHECK");
            result.put("operator", "checkOperationRisk");
            result.put("device_id", deviceId);
            result.put("operation_type", operationType);
            result.put("target", target);
            result.put("context", context);
            result.put("risk_score", score);
            result.put("risk_level", riskLevel(score));
            result.put("findings", findings);
            result.put("evidence_snapshot", Map.of(
                    "profile", compact(profile, "deviceName", "deviceType", "station", "oilTemperatureAlarmThreshold", "normalLoadRate"),
                    "status", compact(status, "oilTemperature", "loadRate", "coolerStatus", "coolerFan2", "alarmPresent", "diagnosticFlags"),
                    "unclosed_alarm_count", countOpen(alarms, "status"),
                    "open_defect_count", countOpen(tickets, "status")));
            result.put("recommended_tools", List.of("queryKnowledgeGraph", "queryInternalDocs", "searchSafetyRules", "calculatePowerFlowEstimate"));
            result.put("data_quality", "mock_data_plus_graph_rule_estimate");
            result.put("availability", "requires_ems_integration");
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return error(e);
        }
    }

    @Tool(description = "Assess transformer oil-temperature risk using demonstration device status and rule thresholds. This is a rule-based engineering check, not a real SCADA control action.")
    public String assessTransformerOilTempRisk(
            @ToolParam(description = "Transformer device id, for example TR-110KV-001.") String deviceId,
            @ToolParam(description = "Observed oil temperature, for example 86C or 86℃.") String oilTemperature,
            @ToolParam(description = "Alarm threshold, for example 80C or 80℃.") String threshold) {
        try {
            String resolvedDeviceId = inferDeviceId(deviceId);
            if (resolvedDeviceId == null || resolvedDeviceId.isBlank()) {
                resolvedDeviceId = "TR-110KV-001";
            }
            Map<String, Object> status = mockDataLoader.getDeviceStatusMock(resolvedDeviceId);
            Map<String, Object> profile = mockDataLoader.getDeviceProfileOrDefault(resolvedDeviceId);
            List<Map<String, Object>> alarms = mockDataLoader.getAlarmHistory(resolvedDeviceId);
            List<Map<String, Object>> tickets = mockDataLoader.getDefectTickets(resolvedDeviceId);

            double oilTemp = parseTemperature(oilTemperature, parseTemperature(status.get("oilTemperature"), 0));
            double thresholdValue = parseTemperature(threshold, parseTemperature(status.get("oilTemperatureThreshold"), 80));
            int loadRate = parsePercent(status.get("loadRate"), 0);
            double margin = Math.round((oilTemp - thresholdValue) * 10.0) / 10.0;
            int riskScore = 10;
            if (margin >= 0) riskScore += 35;
            if (margin >= 5) riskScore += 12;
            if (loadRate >= 90) riskScore += 18;
            if (scenarioContains(String.valueOf(status.get("coolerStatus")), "异常")) riskScore += 22;
            riskScore += Math.min(10, countOpen(alarms, "status") * 4);
            riskScore += Math.min(12, countOpen(tickets, "status") * 6);
            riskScore = Math.min(100, riskScore);

            List<Map<String, Object>> findings = new ArrayList<>();
            findings.add(Map.of(
                    "item", "油温阈值校核",
                    "level", margin >= 0 ? "HIGH" : "LOW",
                    "message", "当前油温 " + oilTemp + "℃，阈值 " + thresholdValue + "℃，裕度 " + (margin >= 0 ? "+" : "") + margin + "℃。"));
            findings.add(Map.of(
                    "item", "负载率校核",
                    "level", loadRate >= 90 ? "MEDIUM" : "LOW",
                    "message", "当前负荷率约 " + loadRate + "%，高负荷会放大温升风险。"));
            findings.add(Map.of(
                    "item", "冷却系统校核",
                    "level", scenarioContains(String.valueOf(status.get("coolerStatus")), "异常") ? "HIGH" : "LOW",
                    "message", "冷却器状态：" + status.getOrDefault("coolerStatus", "未返回") + "；风机2：" + status.getOrDefault("coolerFan2", "未返回") + "。"));
            findings.add(Map.of(
                    "item", "历史缺陷与告警",
                    "level", countOpen(tickets, "status") > 0 || countOpen(alarms, "status") > 0 ? "MEDIUM" : "LOW",
                    "message", "未闭环告警 " + countOpen(alarms, "status") + " 条，待处理缺陷 " + countOpen(tickets, "status") + " 条。"));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "SEMI_REAL_RULE_MECHANISM_CHECK");
            result.put("operator", "assessTransformerOilTempRisk");
            result.put("device_id", resolvedDeviceId);
            result.put("risk_score", riskScore);
            result.put("risk_level", riskLevel(riskScore));
            result.put("calculation", Map.of(
                    "oil_temperature_celsius", oilTemp,
                    "threshold_celsius", thresholdValue,
                    "temperature_margin_celsius", margin,
                    "load_rate_percent", loadRate));
            result.put("findings", findings);
            result.put("human_confirmation_items", List.of(
                    "现场确认冷却器风机、油泵和温控回路状态",
                    "核对最近24小时油温、负荷和环境温度曲线",
                    "涉及降负荷或方式调整时由值班负责人复核确认"));
            result.put("evidence_snapshot", Map.of(
                    "profile", compact(profile, "deviceName", "deviceType", "station", "ratedCapacity", "coolingType", "oilTemperatureAlarmThreshold"),
                    "status", compact(status, "oilTemperature", "oilTemperatureThreshold", "loadRate", "environmentTemp", "coolerStatus", "coolerFan2", "alarmPresent"),
                    "alarm_count", alarms.size(),
                    "defect_ticket_count", tickets.size()));
            result.put("data_quality", "mock_device_status_plus_rule_threshold");
            result.put("control_boundary", "advisory_only_no_device_control");
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
            String deviceId = inferDeviceId(faultEntity, faultType, context);
            Map<String, Object> status = deviceId == null ? Map.of() : mockDataLoader.getDeviceStatusMock(deviceId);
            Map<String, Object> profile = deviceId == null ? Map.of() : mockDataLoader.getDeviceProfileOrDefault(deviceId);
            List<Map<String, Object>> alarms = deviceId == null ? List.of() : mockDataLoader.getAlarmHistory(deviceId);
            List<Map<String, Object>> tickets = deviceId == null ? List.of() : mockDataLoader.getDefectTickets(deviceId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "SEMI_REAL_RULE_SCENARIO_GENERATION");
            result.put("operator", "generateFaultScenario");
            result.put("device_id", deviceId);
            result.put("fault_entity", faultEntity);
            result.put("fault_type", faultType);
            result.put("context", context);
            result.put("scenarios", buildScenarios(faultType, context, status, alarms, tickets));
            result.put("evidence_snapshot", Map.of(
                    "profile", compact(profile, "deviceName", "deviceType", "station", "bay", "coolingType"),
                    "status", compact(status, "oilTemperature", "loadRate", "coolerStatus", "diagnosticFlags"),
                    "recent_alarm_count", alarms.size(),
                    "open_defect_count", countOpen(tickets, "status")));
            result.put("required_evidence", List.of("device status", "alarm history", "defect tickets", "safety rules", "workflow graph context"));
            result.put("data_quality", "mock_data_plus_graph_rule_estimate");
            result.put("availability", "requires_ems_integration");
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

    private String inferDeviceId(String... texts) {
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = DEVICE_ID_PATTERN.matcher(text);
            if (matcher.find()) {
                return matcher.group().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private boolean scenarioContains(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private int loadingDelta(String mode, String scenario) {
        String text = ((mode == null ? "" : mode) + " " + (scenario == null ? "" : scenario)).toLowerCase(Locale.ROOT);
        if (text.contains("n-1") || text.contains("故障") || text.contains("fault")) {
            return 14;
        }
        if (text.contains("转供") || text.contains("transfer") || text.contains("检修")) {
            return 9;
        }
        return 4;
    }

    private int parsePercent(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        Matcher matcher = Pattern.compile("(\\d+)").matcher(String.valueOf(value));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private double parseTemperature(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(String.valueOf(value));
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : fallback;
    }

    private List<String> buildPowerFlowRiskPoints(int loading, double voltage, Map<String, Object> status, String scenario) {
        List<String> points = new ArrayList<>();
        if (loading >= 95) {
            points.add("估算最大载流率超过 95%，应在真实潮流中复核断面热稳定裕度。");
        }
        if (voltage < 0.95) {
            points.add("估算最低母线电压低于 0.95pu，应校核无功支撑和电压约束。");
        }
        if (scenarioContains(String.valueOf(status.get("coolerStatus")), "异常")) {
            points.add("设备状态显示冷却系统异常，潮流转移或继续高负荷运行会放大热风险。");
        }
        if (points.isEmpty()) {
            points.add("未触发热稳定或电压越限规则，但仍需以 EMS/DTS 潮流结果作为正式依据。");
        }
        return points;
    }

    private int riskScore(String operationType, String context, Map<String, Object> status,
                          List<Map<String, Object>> alarms, List<Map<String, Object>> tickets) {
        int score = 10;
        double oilTemp = parseTemperature(status.get("oilTemperature"), 0);
        double threshold = parseTemperature(status.get("oilTemperatureThreshold"), 80);
        int loadRate = parsePercent(status.get("loadRate"), 0);
        if (oilTemp > 0 && oilTemp >= threshold) score += 35;
        if (loadRate >= 90) score += 18;
        if (scenarioContains(String.valueOf(status.get("coolerStatus")), "异常")) score += 22;
        score += Math.min(15, countOpen(alarms, "status") * 5);
        score += Math.min(15, countOpen(tickets, "status") * 6);
        if (scenarioContains(operationType + " " + context, "n-1", "故障", "fault", "全停")) score += 18;
        return Math.min(100, score);
    }

    private String riskLevel(int score) {
        if (score >= 80) return "CRITICAL";
        if (score >= 55) return "HIGH";
        if (score >= 30) return "MEDIUM";
        return "LOW";
    }

    private List<Map<String, Object>> buildRiskFindings(int score, Map<String, Object> status,
                                                        List<Map<String, Object>> alarms,
                                                        List<Map<String, Object>> tickets,
                                                        String operationType, String context) {
        List<Map<String, Object>> findings = new ArrayList<>();
        double oilTemp = parseTemperature(status.get("oilTemperature"), 0);
        double threshold = parseTemperature(status.get("oilTemperatureThreshold"), 80);
        int loadRate = parsePercent(status.get("loadRate"), 0);
        if (oilTemp >= threshold && oilTemp > 0) {
            findings.add(Map.of("type", "device_state", "level", "HIGH", "message", "油温 " + oilTemp + "℃ 已达到或超过阈值 " + threshold + "℃。"));
        }
        if (loadRate >= 90) {
            findings.add(Map.of("type", "loading", "level", "MEDIUM", "message", "负荷率约 " + loadRate + "%，接近高负荷区间。"));
        }
        if (scenarioContains(String.valueOf(status.get("coolerStatus")), "异常")) {
            findings.add(Map.of("type", "cooling", "level", "HIGH", "message", "冷却系统状态异常，应优先核查风机、接触器和控制回路。"));
        }
        int openAlarms = countOpen(alarms, "status");
        int openTickets = countOpen(tickets, "status");
        if (openAlarms > 0) {
            findings.add(Map.of("type", "alarm_history", "level", "MEDIUM", "message", "存在 " + openAlarms + " 条未处理或未确认告警。"));
        }
        if (openTickets > 0) {
            findings.add(Map.of("type", "defect_ticket", "level", "MEDIUM", "message", "存在 " + openTickets + " 条待处理缺陷工单。"));
        }
        if (scenarioContains(operationType + " " + context, "n-1", "故障", "fault", "全停")) {
            findings.add(Map.of("type", "contingency", "level", "HIGH", "message", "任务含故障或 N-1 场景，应补充断面、保护和潮流校核。"));
        }
        if (findings.isEmpty()) {
            findings.add(Map.of("type", "rule_check", "level", riskLevel(score), "message", "未命中明显异常状态，建议以实时遥测和运行方式复核。"));
        }
        findings.add(Map.of("type", "human_review", "level", "HIGH", "message", "涉及设备操作或方式调整时仍需值班负责人复核。"));
        return findings;
    }

    private List<Map<String, Object>> buildScenarios(String faultType, String context, Map<String, Object> status,
                                                     List<Map<String, Object>> alarms, List<Map<String, Object>> tickets) {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        if (parseTemperature(status.get("oilTemperature"), 0) >= parseTemperature(status.get("oilTemperatureThreshold"), 80)
                || scenarioContains(String.valueOf(status.get("coolerStatus")), "异常")) {
            scenarios.add(Map.of(
                    "name", "cooling_capacity_degradation",
                    "probability", "high",
                    "focus", "冷却器故障叠加高负荷导致油温持续升高，优先核查风机、接触器和温控回路。"));
        }
        if (scenarioContains(faultType + " " + context, "n-1", "故障", "fault")) {
            scenarios.add(Map.of(
                    "name", "single_contingency_overload",
                    "probability", "medium",
                    "focus", "单一元件退出后检查相邻线路、主变和母线断面是否越限。"));
        }
        if (countOpen(tickets, "status") > 0 || countOpen(alarms, "status") > 0) {
            scenarios.add(Map.of(
                    "name", "latent_defect_escalation",
                    "probability", "medium",
                    "focus", "未处理告警或缺陷可能扩大为保护动作、降负荷或停运事件。"));
        }
        if (scenarios.isEmpty()) {
            scenarios.add(Map.of(
                    "name", "baseline_operation_check",
                    "probability", "low",
                    "focus", "当前样例数据未显示明显异常，按状态、告警、缺陷、规程顺序完成复核。"));
        }
        return scenarios;
    }

    private int countOpen(List<Map<String, Object>> rows, String field) {
        int count = 0;
        for (Map<String, Object> row : rows) {
            String status = String.valueOf(row.getOrDefault(field, ""));
            if (status.contains("未处理") || status.contains("待处理") || status.contains("未确认")) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> compact(Map<String, Object> source, String... keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) {
            if (source.containsKey(key)) {
                result.put(key, source.get(key));
            }
        }
        return result;
    }

    private String error(Exception e) {
        try {
            return objectMapper.writeValueAsString(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception ignored) {
            return "{\"status\":\"error\"}";
        }
    }
}
