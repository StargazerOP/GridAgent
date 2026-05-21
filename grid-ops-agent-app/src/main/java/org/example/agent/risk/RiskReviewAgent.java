package org.example.agent.risk;

import org.example.config.LlmFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;

@Component
public class RiskReviewAgent {

    private final LlmFactory llmFactory;

    @Autowired
    private ToolCallbackProvider tools;

    public RiskReviewAgent(LlmFactory llmFactory) {
        this.llmFactory = llmFactory;
    }

    private static final String RISK_REVIEW_PROMPT = """
            你是电力智能运维平台的风险评估与行动建议专家。你的职责是：

            1. 风险评估：基于诊断结果评估风险等级
               - CRITICAL：可能危及人身安全或造成重大设备损坏
               - HIGH：可能导致设备故障或严重影响运行
               - MEDIUM：需要关注但不紧急
               - LOW：轻微异常，持续监测即可

            2. 行动建议：根据风险等级生成具体行动建议
               - CRITICAL/HIGH：必须标注⚠️，建议人工确认，说明紧急程度
               - MEDIUM：给出处理建议和时间要求
               - LOW：给出监测建议

            3. 安全提示：列出操作过程中的安全注意事项，引用相关安规条款

            重要规则：
            - 高风险操作（停电、降负荷、紧急派单）必须标注⚠️并建议人工确认
            - 严禁编造数据，只能引用已有分析结果
            - 涉及安全操作时，必须提示遵守现场规程

            输出格式：
            ## 风险等级
            （CRITICAL/HIGH/MEDIUM/LOW）

            ## 行动建议
            （具体的处理建议和步骤）

            ## 安全提示
            （安全注意事项和安规引用）

            ## 是否建议派单
            （明确结论和紧急程度）
            """;

    public ReactAgent create() {
        ChatModel chatModel = llmFactory.riskChatModel();
        return ReactAgent.builder()
                .name("risk_review_agent")
                .model(chatModel)
                .systemPrompt(RISK_REVIEW_PROMPT)
                .tools(tools.getToolCallbacks())
                .build();
    }
}
