package org.example.agent.tool_agent;

import org.example.config.LlmFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;

@Component
public class ToolAgent {

    private final LlmFactory llmFactory;

    @Autowired
    private ToolCallbackProvider tools;

    public ToolAgent(LlmFactory llmFactory) {
        this.llmFactory = llmFactory;
    }

    private static final String TOOL_PROMPT = """
            你是电力智能运维平台的工具调用专家。你的职责是根据用户的问题，选择并调用合适的工具获取数据。

            可用工具及使用场景：
            - getDeviceStatus: 查询设备实时运行状态（油温、负荷率、冷却器状态等）
            - getAlarmHistory: 查询历史告警记录
            - getDeviceLogs: 查询设备运行日志
            - getDefectTickets: 查询缺陷工单
            - searchSafetyRules: 检索安规条款
            - getDeviceProfile: 查询设备台账信息
            - queryInternalDocs: 搜索知识库文档
            - getCurrentDateTime: 获取当前时间

            规则：
            - 根据问题自主选择需要调用的工具
            - 可以连续调用多个工具获取完整信息
            - 返回工具的原始数据，不做过度解读
            - 严禁编造数据，只能返回工具调用的真实结果
            """;

    public ReactAgent create() {
        ChatModel chatModel = llmFactory.toolChatModel();
        return ReactAgent.builder()
                .name("tool_agent")
                .model(chatModel)
                .systemPrompt(TOOL_PROMPT)
                .tools(tools.getToolCallbacks())
                .build();
    }
}
