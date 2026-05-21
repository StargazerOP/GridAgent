package org.example.graph.subgraph.knowledge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.example.config.LlmFactory;
import org.example.rag.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Map;

public class ReactQaAgentNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ReactQaAgentNode.class);
    private final LlmFactory llmFactory;
    private final ToolCallbackProvider tools;
    private final KnowledgeGraphService knowledgeGraphService;

    public ReactQaAgentNode(LlmFactory llmFactory, ToolCallbackProvider tools, KnowledgeGraphService knowledgeGraphService) {
        this.llmFactory = llmFactory;
        this.tools = tools;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String input = state.value("cleaned_input").map(Object::toString).orElse("");
        String ragContext = state.value("execution_result").map(Object::toString).orElse("");
        String toolResult = state.value("tool_result").map(Object::toString).orElse("");
        String skillContext = state.value("skill_context").map(Object::toString).orElse("");
        String memoryContext = state.value("memory_context").map(Object::toString).orElse("");
        Object entitiesObj = state.value("entities").orElse(Map.of());

        logger.info("ReactQaAgentNode: RAG+ReAct融合问答");

        String graphContext = "";
        if (knowledgeGraphService != null) {
            graphContext = knowledgeGraphService.buildGraphContext(input);
        }

        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("你是电力智能运维平台的知识问答专家。请根据提供的参考资料和工具查询结果，准确回答用户问题。\n\n");

        systemPrompt.append("重要规则：\n");
        systemPrompt.append("- 优先使用参考资料中的信息回答，如果参考资料充分则直接回答\n");
        systemPrompt.append("- 如果参考资料不足，可以使用工具查询实时数据补充\n");
        systemPrompt.append("- 如果参考资料和工具结果都无法回答，请明确说明\n");
        systemPrompt.append("- 严禁编造数据，只能引用参考资料或工具返回的真实内容\n");
        systemPrompt.append("- 涉及安全操作时必须提示遵守现场规程\n\n");

        if (!ragContext.isEmpty()) {
            systemPrompt.append("--- RAG检索结果 ---\n").append(ragContext).append("\n\n");
        }
        if (!toolResult.isEmpty()) {
            systemPrompt.append("--- 工具查询结果 ---\n").append(toolResult).append("\n\n");
        }
        if (!graphContext.isEmpty()) {
            systemPrompt.append("--- 知识图谱扩展 ---\n").append(graphContext).append("\n\n");
        }
        if (!skillContext.isEmpty()) {
            systemPrompt.append("--- 业务场景指导 ---\n").append(skillContext).append("\n\n");
        }
        if (!memoryContext.isEmpty()) {
            systemPrompt.append("--- 上下文记忆 ---\n").append(memoryContext).append("\n\n");
        }
        if (entitiesObj instanceof Map<?, ?> entities && !entities.isEmpty()) {
            systemPrompt.append("--- 已识别实体 ---\n");
            for (Map.Entry<?, ?> entry : entities.entrySet()) {
                systemPrompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            systemPrompt.append("\n");
        }

        ChatModel chatModel = llmFactory.qaChatModel();

        ReactAgent agent = ReactAgent.builder()
                .name("react_qa_agent")
                .model(chatModel)
                .systemPrompt(systemPrompt.toString())
                .tools(tools.getToolCallbacks())
                .build();

        String result = agent.call(input).getText();

        logger.info("ReactQaAgentNode: 问答完成, resultLength={}", result.length());
        return Map.of("final_response", result);
    }
}
