package org.example.graph.subgraph.diagnosis;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.skill.model.Skill;
import org.example.graph.GraphStateKeys;
import org.example.graph.model.PlanStep;
import org.example.graph.validation.PlanValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlannerNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(PlannerNode.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final PlanValidator planValidator;
    private final ToolCallbackProvider tools;

    public PlannerNode(ChatClient chatClient, PlanValidator planValidator, ToolCallbackProvider tools) {
        this.chatClient = chatClient;
        this.planValidator = planValidator;
        this.tools = tools;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String input = state.value(GraphStateKeys.CLEANED_INPUT).map(Object::toString).orElse("");
        String ragContext = state.value(GraphStateKeys.EXECUTION_RESULT).map(Object::toString).orElse("");
        String skillContext = state.value("skill_context").map(Object::toString).orElse("");
        String workflowContext = state.value(GraphStateKeys.WORKFLOW_CONTEXT).map(Object::toString).orElse("");
        Map<String, String> entities = readEntities(state.value(GraphStateKeys.ENTITIES).orElse(Map.of()));

        logger.info("PlannerNode: generating structured diagnosis plan");

        List<Object> rawSteps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        rawSteps.addAll(readWorkflowSeedPlan(workflowContext, input, entities, warnings));
        try {
            if (!rawSteps.isEmpty()) {
                warnings.add("Workflow template plan seed was used as the executable plan draft.");
            } else {
            String llmResponse = chatClient.prompt()
                    .system(plannerPrompt())
                    .user(userMessage(input, ragContext, skillContext, workflowContext, entities, state))
                    .call()
                    .content();

            Map<String, Object> parsed = OBJECT_MAPPER.readValue(extractJson(llmResponse), new TypeReference<>() {});
            Object stepsObj = parsed.get(GraphStateKeys.PLAN_STEPS);
            if (stepsObj instanceof List<?> list) {
                rawSteps.addAll(list);
            }
            }
        } catch (Exception e) {
            warnings.add("LLM plan generation failed, falling back to planValidator defaults: " + e.getMessage());
            logger.warn("PlannerNode: LLM plan generation failed, planValidator will supply defaults, error={}", e.getMessage());
        }

        List<PlanStep> planSteps = planValidator.normalizeOrDefault(rawSteps, input, entities, tools, warnings);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(GraphStateKeys.PLAN_STEPS, planSteps);
        result.put("current_step_index", 0);
        if (!warnings.isEmpty()) {
            result.put(GraphStateKeys.VALIDATION_WARNINGS, warnings);
        }
        logger.info("PlannerNode: plan ready, steps={}, warnings={}", planSteps.size(), warnings.size());
        return result;
    }

    private List<Object> readWorkflowSeedPlan(String workflowContext, String input,
                                              Map<String, String> entities, List<String> warnings) {
        if (workflowContext == null || workflowContext.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> context = OBJECT_MAPPER.readValue(workflowContext, new TypeReference<>() {});
            double score = 0;
            Object scoreObj = context.get("match_score");
            if (scoreObj instanceof Number number) {
                score = number.doubleValue();
            } else if (scoreObj != null) {
                score = Double.parseDouble(String.valueOf(scoreObj));
            }
            if (score == 0 && context.get("legacy_match_score") instanceof Number legacyScore) {
                score = legacyScore.doubleValue();
            }
            Object workflowStepsObj = context.get("workflow_steps");
            if (workflowStepsObj instanceof List<?> list && !list.isEmpty()) {
                warnings.add("Workflow asset steps were converted into executable plan seed.");
                return workflowAssetStepsToPlan(list, input, entities);
            }
            Object planObj = context.get("plan_steps");
            if (!(planObj instanceof List<?>)) {
                Object legacyPlanObj = context.get("legacy_plan_steps");
                if (legacyPlanObj instanceof List<?>) {
                    planObj = legacyPlanObj;
                }
            }
            if (planObj instanceof List<?> list && !list.isEmpty()) {
                if (score >= 5.0) {
                    warnings.add("Workflow context matched strongly; using template plan seed, score=" + score);
                } else {
                    warnings.add("Workflow context matched weakly (score=" + score + "); template steps used as seed, planValidator will normalize");
                }
                return new ArrayList<>(list);
            }
        } catch (Exception e) {
            warnings.add("Workflow plan seed could not be parsed: " + e.getMessage());
        }
        return List.of();
    }

    private List<Object> workflowAssetStepsToPlan(List<?> workflowSteps, String input, Map<String, String> entities) {
        List<Object> result = new ArrayList<>();
        int index = 1;
        for (Object item : workflowSteps) {
            if (!(item instanceof Map<?, ?> step)) {
                continue;
            }
            String toolName = valueOr(step.get("tool_name"), "");
            if (toolName.isBlank()) {
                toolName = firstTool(step.get("recommended_tools"));
            }
            Map<String, Object> params = resolveParams(readParams(step.get("params")), input, entities);
            String skillId = firstSkill(step.get("skill_ids"));
            String skillName = valueOr(step.get("skill_name"), skillId);
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("step_id", valueOr(step.get("step_id"), String.format("workflow-step-%03d", index)));
            plan.put("step_no", index);
            plan.put("step_type", toolName == null || toolName.isBlank() ? "ANALYSIS" : "TOOL_CALL");
            plan.put("action", valueOr(step.get("name"), "执行流程步骤"));
            plan.put("tool_name", toolName);
            plan.put("tool", toolName);
            plan.put("business_skill_id", skillId);
            plan.put("business_skill_name", skillName);
            plan.put("params", params);
            plan.put("purpose", valueOr(step.get("purpose"), "执行 Workflow 资产步骤"));
            plan.put("expected", valueOr(step.get("evidence_requirement"), "结构化证据摘要"));
            plan.put("depends_on", index == 1 ? List.of() : List.of(String.format("workflow-step-%03d", index - 1)));
            plan.put("status", "PENDING");
            plan.put("retry_count", 0);
            plan.put("required", true);
            result.add(plan);
            index++;
        }
        return result;
    }

    private String firstSkill(Object skillIds) {
        if (skillIds instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            return first == null ? null : String.valueOf(first);
        }
        return null;
    }

    private String firstTool(Object tools) {
        if (tools instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            return first == null ? "" : String.valueOf(first);
        }
        return "";
    }

    private Map<String, Object> readParams(Object paramsObj) {
        if (paramsObj instanceof Map<?, ?> map) {
            Map<String, Object> params = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                params.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return params;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> resolveParams(Map<String, Object> params, String input, Map<String, String> entities) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("device_id", readEntity(entities, "deviceId", "device_id", "设备编号", "deviceName", "设备名称"));
        values.put("oil_temperature", readEntity(entities, "oilTemperature", "oil_temperature", "油温", "temperature"));
        values.put("threshold", readEntity(entities, "threshold", "阈值", "alarmThreshold"));
        if (values.get("device_id").isBlank()) {
            values.put("device_id", extractDeviceId(input));
        }
        if (values.get("oil_temperature").isBlank()) {
            values.put("oil_temperature", extractNumberBefore(input, "C", "℃", "摄氏"));
        }
        if (values.get("threshold").isBlank()) {
            values.put("threshold", extractThreshold(input));
        }
        if (values.get("device_id").isBlank() && input != null && (input.contains("主变") || input.toLowerCase().contains("transformer"))) {
            values.put("device_id", "TR-110KV-001");
        }
        if (values.get("threshold").isBlank() && input != null && (input.contains("油温") || input.toLowerCase().contains("oil"))) {
            values.put("threshold", "80");
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        params.forEach((key, value) -> resolved.put(key, resolveValue(value, values)));
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Object resolveValue(Object value, Map<String, String> values) {
        if (value instanceof String text) {
            String result = text;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    result = result.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), resolveValue(v, values)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> resolveValue(item, values)).toList();
        }
        return value;
    }

    private String readEntity(Map<String, String> entities, String... keys) {
        if (entities == null) {
            return "";
        }
        for (String key : keys) {
            String value = entities.get(key);
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "";
    }

    private String extractDeviceId(String input) {
        if (input == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b[A-Z]{1,8}-[0-9A-Z]+(?:-[0-9A-Z]+)*\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(input);
        return matcher.find() ? matcher.group().toUpperCase(java.util.Locale.ROOT) : "";
    }

    private String extractThreshold(String input) {
        if (input == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:阈值|超过|高于|告警值)[^0-9]*(\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(input);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractNumberBefore(String input, String... markers) {
        if (input == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)\\s*(?:℃|C|c|摄氏度?)")
                .matcher(input);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String valueOr(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private String plannerPrompt() {
        return """
                You are the Planner for a power grid operations agent.
                Generate a compact, executable diagnosis plan.

                Available tools:
                - getDeviceProfile(deviceId)
                - getDeviceStatus(deviceId, metrics, timeRange)
                - getAlarmHistory(deviceId, alarmType, timeRange, limit)
                - getDeviceLogs(deviceId, timeRange, keywords)
                - getDefectTickets(deviceId, defectType, timeRange)
                - queryInternalDocs(query)
                - searchSafetyRules(query, ruleType)
                - queryKnowledgeGraph(query, category, limit)
                - analyzeTopology(center, depth)
                - calculatePowerFlowEstimate(area, mode, scenario)
                - checkOperationRisk(operationType, target, context)
                - generateFaultScenario(faultEntity, faultType, context)
                - getCurrentDateTime()

                Output only JSON:
                {
                  "plan_steps": [
                    {
                      "step_id": "step-001",
                      "step_no": 1,
                      "step_type": "TOOL_CALL",
                      "action": "short action",
                      "tool_name": "tool name",
                      "params": {},
                      "purpose": "why this step is needed",
                      "expected": "expected evidence",
                      "depends_on": [],
                      "status": "PENDING",
                      "retry_count": 0,
                      "required": true
                    }
                  ]
                }
                Use 3 to 8 steps. Prefer device profile, status, alarm history, logs, tickets, safety rules,
                and the GridOps knowledge-organization tools. If a referenced workflow operator is mock/estimated,
                keep the evidence clearly marked and do not present it as real dispatch calculation.
                """;
    }

    private String userMessage(String input, String ragContext, String skillContext, String workflowContext,
                               Map<String, String> entities, OverAllState state) {
        StringBuilder message = new StringBuilder();
        message.append("Alarm or fault description:\n").append(input).append("\n\n");
        if (!entities.isEmpty()) {
            message.append("Extracted entities:\n");
            entities.forEach((key, value) -> message.append("- ").append(key).append(": ").append(value).append("\n"));
            message.append("\n");
        }
        if (!ragContext.isBlank()) {
            message.append("Retrieved operations knowledge:\n").append(truncate(ragContext, 1500)).append("\n\n");
        }
        if (!workflowContext.isBlank()) {
            message.append("Knowledge organization workflow context:\n")
                    .append(truncate(workflowContext, 2400))
                    .append("\n\n");
        }
        Object skillObj = state.value("matched_skill").orElse(null);
        if (skillObj instanceof Skill skill) {
            if (skill.getRecommendedTools() != null && !skill.getRecommendedTools().isEmpty()) {
                message.append("Recommended tools: ").append(String.join(", ", skill.getRecommendedTools())).append("\n");
            }
            if (skill.getDiagnosisWorkflow() != null && !skill.getDiagnosisWorkflow().isEmpty()) {
                message.append("Reference workflow: ").append(String.join("; ", skill.getDiagnosisWorkflow())).append("\n");
            }
        }
        if (!skillContext.isBlank()) {
            message.append("Business context:\n").append(skillContext).append("\n");
        }
        return message.toString();
    }

    private Map<String, String> readEntities(Object entitiesObj) {
        Map<String, String> entities = new HashMap<>();
        if (entitiesObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entities.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return entities;
    }

    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            return "{}";
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
