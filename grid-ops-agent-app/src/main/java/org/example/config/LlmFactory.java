package org.example.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LLM 统一工厂 —— 所有 Agent / Graph Node / Service 的唯一入口。
 * 切换模型厂商只需改配置，不碰业务代码。
 */
@Component
public class LlmFactory {

    private final OpenAiApi openAiApi;
    private final OpenAiApi embeddingOpenAiApi;
    private final String chatModelName;
    private final String embeddingModelName;

    public LlmFactory(@Value("${spring.ai.openai.api-key}") String apiKey,
                      @Value("${spring.ai.openai.base-url}") String baseUrl,
                      @Value("${spring.ai.openai.embedding.api-key:${spring.ai.openai.api-key}}") String embeddingApiKey,
                      @Value("${spring.ai.openai.embedding.base-url:${spring.ai.openai.base-url}}") String embeddingBaseUrl,
                      @Value("${spring.ai.openai.chat.options.model}") String chatModelName,
                      @Value("${spring.ai.openai.embedding.options.model}") String embeddingModelName) {
        this.openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        this.embeddingOpenAiApi = OpenAiApi.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(embeddingApiKey)
                .build();
        this.chatModelName = chatModelName;
        this.embeddingModelName = embeddingModelName;
    }

    // ───────────────────── 预配置 ChatModel ─────────────────────

    /** 意图路由 — 极低温度确保分类确定性 */
    public ChatModel routerChatModel() {
        return buildChatModel(0.05, 500, 0.5);
    }

    /** 工具调用 Agent — 低温度 */
    public ChatModel toolChatModel() {
        return buildChatModel(0.1, 2000, 0.8);
    }

    /** 多维度分析 Agent — 中等温度 */
    public ChatModel analysisChatModel() {
        return buildChatModel(0.3, 3000, 0.8);
    }

    /** 综合诊断 Agent — 中温，需要一定创造性诊断 */
    public ChatModel diagnosisChatModel() {
        return buildChatModel(0.4, 4000, 0.8);
    }

    /** 风险评估 Agent — 低温，安全评估不能出错 */
    public ChatModel riskChatModel() {
        return buildChatModel(0.2, 3000, 0.8);
    }

    /** 通用对话 — 较高温度 */
    public ChatModel chatModel() {
        return buildChatModel(0.7, 2000, 0.9);
    }

    /** 知识问答（RAG 融合） — 中等温度 */
    public ChatModel qaChatModel() {
        return buildChatModel(0.5, 3000, 0.9);
    }

    /** 自定义参数的 ChatModel */
    public ChatModel customChatModel(Double temperature, Integer maxTokens, Double topP) {
        return buildChatModel(temperature, maxTokens, topP);
    }

    // ───────────────────── Embedding ─────────────────────

    /** 文本向量化模型 */
    public EmbeddingModel embeddingModel() {
        return new OpenAiEmbeddingModel(embeddingOpenAiApi,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(embeddingModelName)
                        .build(),
                RetryUtils.DEFAULT_RETRY_TEMPLATE);
    }

    // ───────────────────── private ─────────────────────

    private ChatModel buildChatModel(double temperature, int maxTokens, double topP) {
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(chatModelName)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .topP(topP)
                        .build())
                .build();
    }
}
