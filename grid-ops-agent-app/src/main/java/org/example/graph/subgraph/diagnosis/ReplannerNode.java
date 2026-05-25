package org.example.graph.subgraph.diagnosis;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.*;

public class ReplannerNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ReplannerNode.class);
    private static final int MAX_LOOP = 3;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatClient chatClient;

    public ReplannerNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String executionResult = state.value("execution_result").map(Object::toString).orElse("");
        String evidence = state.value("evidence").map(Object::toString).orElse("");
        String input = state.value("cleaned_input").map(Object::toString).orElse("");
        String riskLevel = state.value("risk_level").map(Object::toString).orElse("MEDIUM");
        int loopCount = state.value("loop_count").map(v -> Integer.parseInt(v.toString())).orElse(0);

        logger.info("ReplannerNode: LLM重规划决策, loopCount={}", loopCount);

        if ("CRITICAL".equals(riskLevel) || "HIGH".equals(riskLevel)) {
            logger.info("ReplannerNode: 高风险，触发人工审批");
            return Map.of("next_action", "HUMAN_APPROVAL", "loop_count", loopCount);
        }

        if (loopCount >= MAX_LOOP) {
            logger.info("ReplannerNode: 达到最大循环次数，继续执行");
            if (evidence.isEmpty() || evidence.length() < 50) {
                return Map.of("next_action", "FALLBACK", "loop_count", loopCount);
            }
            return Map.of("next_action", "CONTINUE", "loop_count", loopCount);
        }

        Object stepResultsObj = state.value("step_results").orElse(List.of());
        List<Map<String, Object>> stepResults = new ArrayList<>();
        if (stepResultsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    stepResults.add((Map<String, Object>) map);
                }
            }
        }

        String replannerPrompt = """
                你是电力智能运维平台的重规划专家（Replanner）。你的职责是评估当前排查进度，决定下一步行动。

                评估逻辑：
                - CONTINUE: 证据充分，可以得出诊断结论
                - REPLAN: 证据不足，需要补充调查（请给出补充步骤）
                - FALLBACK: 无法继续，降级处理

                判断标准：
                - 如果所有步骤都成功执行且结果符合预期，选择CONTINUE
                - 如果已有实时状态、知识库/规程、日志/告警中的任意两类有效证据，且能形成初步判断，选择CONTINUE
                - 只有关键证据完全缺失时才选择REPLAN并给出少量补充步骤
                - 如果多次补充仍无法获取有效信息，选择FALLBACK
                - 不要重复查询已经失败过的同一工具和同一参数
                - additional_steps最多2个

                请严格按以下JSON格式输出，不要输出其他内容：
                {"next_action": "CONTINUE或REPLAN或FALLBACK", "reason": "决策理由", "additional_steps": [{"step": 步骤编号, "action": "步骤描述", "tool": "工具名称", "params": {"参数名": "参数值"}, "purpose": "步骤目的", "expected": "预期结果"}]}

                如果选择CONTINUE或FALLBACK，additional_steps为空数组[]。
                """;

        StringBuilder userMessage = new StringBuilder();
        userMessage.append("原始告警：").append(input).append("\n\n");

        if (!stepResults.isEmpty()) {
            userMessage.append("已执行的步骤及结果：\n");
            for (Map<String, Object> step : stepResults) {
                userMessage.append("步骤").append(first(step, "stepNo", "step_no", "step", "?")).append(": ")
                        .append(step.getOrDefault("action", "?")).append(" [")
                        .append(step.getOrDefault("status", "?")).append("]\n");
                if (step.containsKey("result")) {
                    String result = String.valueOf(step.get("result"));
                    userMessage.append("  结果: ").append(result, 0, Math.min(result.length(), 200)).append("\n");
                }
                userMessage.append("  工具: ").append(first(step, "toolName", "tool_name", "tool", "?")).append("\n");
                userMessage.append("  符合预期: ").append(first(step, "matchExpected", "match_expected", "?")).append("\n");
            }
            userMessage.append("\n");
        } else if (!evidence.isEmpty()) {
            userMessage.append("已收集的证据：\n").append(evidence, 0, Math.min(evidence.length(), 500)).append("\n\n");
        }

        userMessage.append("当前循环次数：").append(loopCount).append("/").append(MAX_LOOP);

        try {
            String llmResponse = chatClient.prompt()
                    .system(replannerPrompt)
                    .user(userMessage.toString())
                    .call()
                    .content();

            String jsonStr = llmResponse;
            if (llmResponse.contains("{")) {
                jsonStr = llmResponse.substring(llmResponse.indexOf("{"));
                if (jsonStr.contains("}")) {
                    jsonStr = jsonStr.substring(0, jsonStr.lastIndexOf("}") + 1);
                }
            }

            Map<String, Object> parsed = objectMapper.readValue(jsonStr, new TypeReference<>() {});
            String nextAction = (String) parsed.getOrDefault("next_action", "CONTINUE");
            String reason = (String) parsed.getOrDefault("reason", "");

            Map<String, Object> result = new HashMap<>();
            result.put("next_action", nextAction);
            result.put("loop_count", loopCount + 1);

            if ("REPLAN".equals(nextAction)) {
                Object additionalStepsObj = parsed.get("additional_steps");
                List<Map<String, Object>> additionalSteps = new ArrayList<>();
                if (additionalStepsObj instanceof List<?> list) {
                    Object existingPlanObj = state.value("plan_steps").orElse(List.of());
                    int nextStepNum = 1;
                    if (existingPlanObj instanceof List<?> existingList) {
                        nextStepNum = existingList.size() + 1;
                    }
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            if (additionalSteps.size() >= 2) {
                                break;
                            }
                            Map<String, Object> step = new HashMap<>();
                            for (Map.Entry<?, ?> entry : map.entrySet()) {
                                step.put(String.valueOf(entry.getKey()), entry.getValue());
                            }
                            step.put("step", nextStepNum++);
                            step.put("status", "PENDING");
                            additionalSteps.add(step);
                        }
                    }
                }
                result.put("additional_steps", additionalSteps);
                logger.info("ReplannerNode: LLM决策REPLAN, reason={}, 新增{}个步骤", reason, additionalSteps.size());
            } else {
                logger.info("ReplannerNode: LLM决策{}, reason={}", nextAction, reason);
            }

            return result;
        } catch (Exception e) {
            logger.warn("ReplannerNode: LLM重规划失败，使用启发式规则, error={}", e.getMessage());
            if (evidence.isEmpty() || evidence.contains("失败") || evidence.length() < 50) {
                return Map.of("next_action", "REPLAN", "loop_count", loopCount + 1);
            }
            return Map.of("next_action", "CONTINUE", "loop_count", loopCount + 1);
        }
    }

    private Object first(Map<String, Object> map, String... keys) {
        String fallback = "";
        int limit = keys.length;
        if (keys.length > 0 && !map.containsKey(keys[keys.length - 1])) {
            fallback = keys[keys.length - 1];
            limit = keys.length - 1;
        }
        for (int i = 0; i < limit; i++) {
            String key = keys[i];
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return fallback;
    }
}
