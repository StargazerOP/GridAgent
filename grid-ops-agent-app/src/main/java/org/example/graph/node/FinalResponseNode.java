package org.example.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
            StringBuilder sb = new StringBuilder(response);
            sb.append("\n\n--- 执行概况 ---\n");
            long completed = stepResults.stream()
                    .filter(obj -> obj instanceof Map<?, ?> map && "COMPLETED".equalsIgnoreCase(String.valueOf(map.get("status"))))
                    .count();
            long failed = stepResults.stream()
                    .filter(obj -> obj instanceof Map<?, ?> map && "FAILED".equalsIgnoreCase(String.valueOf(map.get("status"))))
                    .count();
            sb.append("本轮新增工具调用 ").append(stepResults.size())
                    .append(" 项，成功 ").append(completed)
                    .append(" 项，失败 ").append(failed)
                    .append(" 项。详细步骤可在上方执行轨迹中展开查看。\n");
            response = sb.toString();
        }

        return Map.of("final_response", response);
    }
}
