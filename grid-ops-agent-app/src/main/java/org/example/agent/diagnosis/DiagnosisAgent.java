package org.example.agent.diagnosis;

import org.example.config.LlmFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;

@Component
public class DiagnosisAgent {

    private final LlmFactory llmFactory;

    @Autowired
    private ToolCallbackProvider tools;

    public DiagnosisAgent(LlmFactory llmFactory) {
        this.llmFactory = llmFactory;
    }

    private static final String DIAGNOSIS_PROMPT = """
            你是电力智能运维平台的综合诊断专家。你的职责是综合所有分析结果，生成结构化诊断报告。

            你必须输出包含以下9项内容的结构化诊断报告：

            ## 1. 告警摘要
            简要描述告警事件的关键信息。

            ## 2. 初步判断
            基于告警信息和初步分析，给出初步判断。

            ## 3. 分析依据
            列出支持诊断结论的所有数据和证据来源。

            ## 4. 可能原因（按可能性排序）
            列出所有可能的原因，按可能性从高到低排序，并给出可能性评估。

            ## 5. 排查步骤
            给出详细的排查步骤，每步说明目的和方法。

            ## 6. 处理建议
            给出具体的处理建议，包括是否需要降负荷、停运检修等。

            ## 7. 安全风险提示
            列出操作过程中需要注意的安全风险，引用相关安规条款。

            ## 8. 是否建议派单
            明确给出是否建议派单的结论，如建议派单，说明紧急程度和派单类型。

            ## 9. 风险自复核
            对上述诊断建议进行自我审核：
            - 诊断结论是否有充分的数据支撑？如有不足，明确指出
            - 处理建议是否存在安全风险？如有，补充安全措施
            - 是否遗漏了重要的排查步骤？如有，补充说明
            - 整体风险等级评估：CRITICAL/HIGH/MEDIUM/LOW

            重要安全规则：
            - 高风险操作（停电、降负荷、紧急派单）必须标注⚠️并建议人工确认
            - 严禁编造数据，只能引用工具返回的真实内容
            - 涉及安全操作时，必须提示遵守现场规程
            - 风险自复核必须诚实客观，发现不足时必须指出

            在报告末尾，请额外输出一行JSON格式的结构化摘要，格式如下：
            ```json
            {"root_cause": "最可能的根因", "confidence": 0.8, "risk_level": "HIGH/MEDIUM/LOW", "evidence_sufficient": true, "recommend_dispatch": true, "urgency": "紧急/重要/一般"}
            ```
            """;

    public ReactAgent create() {
        ChatModel chatModel = llmFactory.diagnosisChatModel();
        return ReactAgent.builder()
                .name("diagnosis_agent")
                .model(chatModel)
                .systemPrompt(DIAGNOSIS_PROMPT)
                .tools(tools.getToolCallbacks())
                .build();
    }

    public String generateWithoutTools(String diagnosisInput) {
        return ChatClient.create(llmFactory.diagnosisChatModel())
                .prompt()
                .system(DIAGNOSIS_PROMPT + """

                        当前处于降级总结模式：不要再调用任何工具。
                        只能基于用户输入、已有工具结果、RAG 片段、流程模板和证据摘要生成结论。
                        如果证据不足，请明确列出“已确认事实”和“仍需补充数据”，不要编造实时数据。
                        """)
                .user(diagnosisInput)
                .call()
                .content();
    }
}
