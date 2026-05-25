package org.example.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FinalResponseNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(FinalResponseNode.class);

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String response = state.value("final_response").map(Object::toString).orElse("");

        logger.info("FinalResponseNode: 格式化最终响应");

        Object stepResultsObj = state.value("step_results").orElse(null);
        List<?> stepResults = new ArrayList<>();
        if (stepResultsObj instanceof List) {
            stepResults = (List<?>) stepResultsObj;
        }

        if (!stepResults.isEmpty() && !response.contains("执行概况")) {
            List<?> effectiveResults = distinctResults(stepResults);
            StringBuilder sb = new StringBuilder(response);
            sb.append("\n\n--- 执行概况 ---\n");
            long completed = effectiveResults.stream()
                    .filter(obj -> obj instanceof Map<?, ?> map && "COMPLETED".equalsIgnoreCase(String.valueOf(map.get("status"))))
                    .count();
            long failed = effectiveResults.stream()
                    .filter(obj -> obj instanceof Map<?, ?> map && "FAILED".equalsIgnoreCase(String.valueOf(map.get("status"))))
                    .count();
            sb.append("本轮有效工具调用 ").append(effectiveResults.size())
                    .append(" 项，成功 ").append(completed)
                    .append(" 项，失败 ").append(failed)
                    .append(" 项。");
            if (failed > 0) {
                sb.append("失败主要来自：").append(failureSummary(effectiveResults)).append("。");
            }
            sb.append("详细步骤可在上方执行轨迹中展开查看。\n");
            response = sb.toString();
        }

        return Map.of("final_response", response);
    }

    private List<?> distinctResults(List<?> stepResults) {
        Map<String, Object> unique = new LinkedHashMap<>();
        for (Object item : stepResults) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String stepId = first(map, "stepId", "step_id");
            String stepNo = first(map, "stepNo", "step_no");
            String tool = first(map, "toolName", "tool_name", "tool");
            String action = first(map, "action");
            String key = !stepId.isBlank() ? stepId : stepNo + "|" + tool + "|" + action;
            unique.put(key, item);
        }
        return new ArrayList<>(unique.values());
    }

    private String failureSummary(List<?> stepResults) {
        Map<String, Long> counts = stepResults.stream()
                .filter(obj -> obj instanceof Map<?, ?> map && "FAILED".equalsIgnoreCase(String.valueOf(map.get("status"))))
                .map(obj -> readableErrorType((Map<?, ?>) obj))
                .collect(Collectors.groupingBy(type -> type, LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .limit(3)
                .map(entry -> entry.getKey() + " " + entry.getValue() + " 项")
                .collect(Collectors.joining("、"));
    }

    private String readableErrorType(Map<?, ?> step) {
        String type = first(step, "errorType", "error_type");
        return switch (type) {
            case "TOOL_NOT_FOUND" -> "工具未注册";
            case "TOOL_RESULT_SHAPE_MISMATCH" -> "返回字段不完整";
            case "TOOL_ERROR" -> "工具返回错误";
            case "TOOL_TIMEOUT" -> "工具超时";
            case "EMPTY_TOOL_RESULT" -> "工具无返回";
            default -> type.isBlank() || "null".equals(type) ? "未分类错误" : type;
        };
    }

    private String first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }
}
