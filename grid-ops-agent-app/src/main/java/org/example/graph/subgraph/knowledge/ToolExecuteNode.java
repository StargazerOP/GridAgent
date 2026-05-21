package org.example.graph.subgraph.knowledge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.example.agent.tool_agent.ToolAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class ToolExecuteNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ToolExecuteNode.class);
    private final ToolAgent toolAgent;

    public ToolExecuteNode(ToolAgent toolAgent) {
        this.toolAgent = toolAgent;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String input = state.value("cleaned_input").map(Object::toString).orElse("");
        Object entitiesObj = state.value("entities").orElse(Map.of());
        logger.info("ToolExecuteNode: ToolAgent工具调用");

        String toolInput = input;
        if (entitiesObj instanceof Map<?, ?> entities && !entities.isEmpty()) {
            StringBuilder enrichedInput = new StringBuilder(input);
            enrichedInput.append("\n\n已识别的关键实体：");
            for (Map.Entry<?, ?> entry : entities.entrySet()) {
                enrichedInput.append("\n- ").append(entry.getKey()).append(": ").append(entry.getValue());
            }
            toolInput = enrichedInput.toString();
            logger.info("ToolExecuteNode: 注入实体信息到工具调用, entities={}", entities);
        }

        try {
            String result = toolAgent.create().call(toolInput).getText();
            return Map.of("tool_result", result);
        } catch (Exception e) {
            logger.error("ToolExecuteNode: 工具调用失败", e);
            return Map.of("tool_result", "工具调用失败: " + e.getMessage());
        }
    }
}
