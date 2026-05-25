package org.example.graph.subgraph.diagnosis;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ActionRecommendNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ActionRecommendNode.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final ChatClient chatClient;

    public ActionRecommendNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String executionResult = state.value("execution_result").map(Object::toString).orElse("");
        String riskLevel = state.value("risk_level").map(Object::toString).orElse("MEDIUM");
        String evidence = state.value("evidence").map(Object::toString).orElse("");
        String diagnosisResult = state.value("diagnosis_result").map(Object::toString).orElse("");
        String input = state.value("cleaned_input").map(Object::toString)
                .orElse(state.value("input").map(Object::toString).orElse(""));
        logger.info("ActionRecommendNode: 行动建议+结果汇总, riskLevel={}", riskLevel);

        StringBuilder result = new StringBuilder();

        Object stepResultsObj = state.value("step_results").orElse(null);
        List<Map<String, Object>> stepResults = readList(stepResultsObj);
        List<Map<String, Object>> planSteps = readList(state.value("plan_steps").orElse(List.of()));

        if (!diagnosisResult.isEmpty()) {
            result.append(diagnosisResult);
        } else {
            result.append(generateFallbackResponse(input, planSteps, stepResults, evidence, riskLevel));
        }

        if (!stepResults.isEmpty() && !result.toString().contains("执行概况")) {
            result.append("\n\n--- 执行概况 ---\n")
                    .append(buildExecutionSummary(stepResults, evidence));
        }

        if ("CRITICAL".equals(riskLevel) || "HIGH".equals(riskLevel)) {
            result.append("\n\n高风险操作提醒：本诊断涉及运行方式调整或风险校核，需由专业人员复核后再执行。");
            result.append("\n建议启动人工审批流程，确认后方可执行相关操作。");
        }

        return Map.of("final_response", result.toString());
    }

    private String generateFallbackResponse(String input, List<Map<String, Object>> planSteps, List<Map<String, Object>> stepResults,
                                            String evidence, String riskLevel) {
        try {
            String response = chatClient.prompt()
                    .system(fallbackPrompt())
                    .user(fallbackUserMessage(input, planSteps, stepResults, evidence, riskLevel))
                    .call()
                    .content();
            if (response != null && !response.isBlank()) {
                return response.trim();
            }
        } catch (Exception e) {
            logger.warn("ActionRecommendNode: LLM fallback generation failed, using deterministic summary. error={}", e.getMessage());
        }
        return buildDeterministicFallback(input, planSteps, stepResults, evidence, riskLevel);
    }

    private String fallbackPrompt() {
        return """
                你是电网运行诊断系统的最终报告生成器。
                你的任务是基于用户原始问题、已生成计划、工具执行结果和证据摘要，生成面向调度/运维人员的中文回答。

                要求：
                1. 必须紧扣用户原始问题，不要套用不相关场景。
                2. 不要复述英文工具名、JSON、trace、step_id 或调试日志。
                3. 如果证据不足，要明确说明缺口，并给出下一步核查项。
                4. 如果结果来自 mock/estimate，要标注为推演线索，不能当作真实 EMS/DTS/SCADA 结论。
                5. 输出结构固定为：
                   - 诊断目标
                   - 已完成核查
                   - 初步判断
                   - 建议处置
                   - 仍需补充的数据
                6. 每节 2-4 条，语言简洁、专业、可展示。
                """;
    }

    private String fallbackUserMessage(String input, List<Map<String, Object>> planSteps, List<Map<String, Object>> stepResults,
                                       String evidence, String riskLevel) {
        return "用户问题：\n" + truncate(input, 500)
                + "\n\n风险等级：\n" + riskLevel
                + "\n\n计划步骤摘要：\n" + truncate(toJson(planSteps), 1600)
                + "\n\n工具执行摘要：\n" + truncate(toJson(stepResults), 2200)
                + "\n\n证据摘要：\n" + truncate(evidence, 1800);
    }

    private String buildDeterministicFallback(String input, List<Map<String, Object>> planSteps, List<Map<String, Object>> stepResults, String evidence, String riskLevel) {
        StringBuilder text = new StringBuilder();
        text.append("运行诊断建议\n\n");
        text.append("诊断目标：围绕“").append(truncate(input, 80)).append("”完成状态核查、证据补充、风险判断和处置建议生成。\n\n");
        text.append("已完成核查：\n");
        List<Map<String, Object>> source = planSteps.isEmpty() ? stepResults : planSteps;
        int index = 1;
        for (Map<String, Object> step : source) {
            String action = firstText(step, "action", "purpose", "expected");
            if (action.isBlank() || action.contains("查询当前日期时间")) {
                continue;
            }
            text.append(index++).append(". ").append(cleanAction(action)).append("\n");
            if (index > 7) {
                break;
            }
        }
        if (index == 1) {
            text.append("1. 已尝试装载上下文、匹配流程模板并执行可用工具。\n");
            text.append("2. 已整理可用证据和工具返回结果。\n");
        }
        text.append("\n初步判断：风险等级暂定为 ").append(riskLevel).append("。");
        if (evidence.contains("MOCK_") || evidence.contains("模拟")) {
            text.append("其中部分结果来自模拟/估算算子，只能作为推演线索。");
        }
        text.append("\n\n建议处置：优先核对实时测点、设备状态、历史告警、运行日志和现场反馈；涉及操作或方式调整时需人工复核。\n\n");
        text.append("仍需补充的数据：真实业务系统数据、现场巡视结论、保护/限额/工单等外部依据。");
        return text.toString();
    }

    private String buildExecutionSummary(List<Map<String, Object>> stepResults, String evidence) {
        List<Map<String, Object>> effectiveResults = distinctResults(stepResults);
        long completed = effectiveResults.stream().filter(step -> "COMPLETED".equalsIgnoreCase(String.valueOf(step.get("status")))).count();
        long failed = effectiveResults.stream().filter(step -> "FAILED".equalsIgnoreCase(String.valueOf(step.get("status")))).count();
        StringBuilder summary = new StringBuilder();
        summary.append("本轮有效工具调用 ").append(effectiveResults.size())
                .append(" 项，成功 ").append(completed)
                .append(" 项，失败 ").append(failed).append(" 项。");
        if (failed > 0) {
            summary.append("失败主要来自：").append(failureSummary(effectiveResults)).append("。");
        }
        if (evidence.contains("MOCK_RISK_CHECK") || evidence.contains("MOCK_SCENARIO_GENERATION") || evidence.contains("MOCK_ESTIMATE")) {
            summary.append("检测到模拟算子结果，页面溯源中可查看具体调用过程。");
        }
        if (evidence.contains("命中节点 0 个") || evidence.contains("关系 0 条")) {
            summary.append("部分图谱查询未命中实体，建议补充设备台账或图谱节点。");
        }
        return summary.toString();
    }

    private List<Map<String, Object>> distinctResults(List<Map<String, Object>> stepResults) {
        Map<String, Map<String, Object>> unique = new java.util.LinkedHashMap<>();
        for (Map<String, Object> result : stepResults) {
            String stepId = String.valueOf(result.getOrDefault("stepId", result.getOrDefault("step_id", "")));
            String stepNo = String.valueOf(result.getOrDefault("stepNo", result.getOrDefault("step_no", "")));
            String tool = String.valueOf(result.getOrDefault("toolName", result.getOrDefault("tool_name", result.getOrDefault("tool", ""))));
            String action = String.valueOf(result.getOrDefault("action", ""));
            String key = !stepId.isBlank() ? stepId : stepNo + "|" + tool + "|" + action;
            unique.put(key, result);
        }
        return new ArrayList<>(unique.values());
    }

    private String failureSummary(List<Map<String, Object>> stepResults) {
        Map<String, Long> counts = stepResults.stream()
                .filter(step -> "FAILED".equalsIgnoreCase(String.valueOf(step.get("status"))))
                .collect(java.util.stream.Collectors.groupingBy(this::readableErrorType, java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
        if (counts.isEmpty()) {
            return "无";
        }
        return counts.entrySet().stream()
                .limit(3)
                .map(entry -> entry.getKey() + " " + entry.getValue() + " 项")
                .collect(java.util.stream.Collectors.joining("、"));
    }

    private String readableErrorType(Map<String, Object> step) {
        String type = String.valueOf(step.getOrDefault("errorType", step.getOrDefault("error_type", "")));
        return switch (type) {
            case "TOOL_NOT_FOUND" -> "工具未注册";
            case "TOOL_RESULT_SHAPE_MISMATCH" -> "返回字段不完整";
            case "TOOL_ERROR" -> "工具返回错误";
            case "TOOL_TIMEOUT" -> "工具超时";
            case "EMPTY_TOOL_RESULT" -> "工具无返回";
            default -> type.isBlank() || "null".equals(type) ? "未分类错误" : type;
        };
    }

    private List<Map<String, Object>> readList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return new ArrayList<>();
        }
        return OBJECT_MAPPER.convertValue(list, new TypeReference<>() {});
    }

    private String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private String cleanAction(String action) {
        String cleaned = action.replaceAll("^完成步骤\\d+[:：]?", "").trim();
        cleaned = cleaned.replace("重新查询", "补充查询");
        return cleaned;
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
