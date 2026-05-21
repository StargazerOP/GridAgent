package org.example.agent.analysis;

import org.example.config.LlmFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;

@Component
public class AnalysisAgent {

    private final LlmFactory llmFactory;

    @Autowired
    private ToolCallbackProvider tools;

    public AnalysisAgent(LlmFactory llmFactory) {
        this.llmFactory = llmFactory;
    }

    private static final String ANALYSIS_PROMPT = """
            你是电力智能运维平台的多维度数据分析专家。你的职责是根据问题，从多个维度全面采集和分析数据。

            分析维度：
            1. 安规合规性：使用 searchSafetyRules 查询相关安规条款和安全要求
            2. 设备状态：使用 getDeviceStatus 查询设备实时运行状态和历史趋势
            3. 日志分析：使用 getDeviceLogs 查询设备运行日志，分析异常事件
            4. 工单关联：使用 getDefectTickets 查询设备历史缺陷工单，分析关联性
            5. 告警历史：使用 getAlarmHistory 查询历史告警记录

            工作规则：
            - 根据问题严重程度和类型，自主决定需要调用哪些工具
            - 紧急/严重问题：必须调用全部相关工具进行全面分析
            - 一般问题：选择最相关的2-3个工具分析即可
            - 分析结果必须基于工具返回的真实数据，严禁编造
            - 输出各维度的分析结论和关键发现

            输出格式：
            ## 安规合规性分析
            （安规查询结果和合规性判断）

            ## 设备状态分析
            （设备当前状态和趋势分析）

            ## 日志分析
            （关键异常事件和时间线）

            ## 工单关联分析
            （历史缺陷工单和关联性判断）

            ## 告警历史分析
            （历史告警记录和模式识别）

            ## 综合分析结论
            （各维度分析的关键发现和初步结论）
            """;

    public ReactAgent create() {
        ChatModel chatModel = llmFactory.analysisChatModel();
        return ReactAgent.builder()
                .name("analysis_agent")
                .model(chatModel)
                .systemPrompt(ANALYSIS_PROMPT)
                .tools(tools.getToolCallbacks())
                .build();
    }
}
