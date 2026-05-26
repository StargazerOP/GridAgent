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
        rawSteps.addAll(readWorkflowSeedPlan(workflowContext, warnings));
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

    private List<Object> readWorkflowSeedPlan(String workflowContext, List<String> warnings) {
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
            Object planObj = context.get("plan_steps");
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
