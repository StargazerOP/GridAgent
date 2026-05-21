package org.example.service;

import org.example.config.LlmFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG (Retrieval-Augmented Generation) 服务
 * 结合向量检索和大语言模型生成答案
 */
@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private org.example.rag.HybridSearchService hybridSearchService;

    @Autowired
    private org.example.rag.RerankService rerankService;

    @Autowired
    private org.example.rag.KnowledgeGraphService knowledgeGraphService;

    @Autowired
    private LlmFactory llmFactory;

    @Value("${rag.top-k:3}")
    private int topK;

    private ChatClient chatClient;

    @jakarta.annotation.PostConstruct
    public void init() {
        ChatModel chatModel = llmFactory.chatModel();
        this.chatClient = ChatClient.builder(chatModel).build();
        logger.info("RAG 服务初始化完成，topK: {}", topK);
    }

    /**
     * 流式处理用户问题（不带历史消息）
     */
    public void queryStream(String question, StreamCallback callback) {
        queryStream(question, new ArrayList<>(), callback);
    }

    /**
     * 流式处理用户问题（带历史消息）
     */
    public void queryStream(String question, List<Map<String, String>> history, StreamCallback callback) {
        try {
            logger.info("收到 RAG 流式查询: {}", question);

            // 1. 混合检索（向量 + 关键词）
            List<org.example.rag.HybridSearchService.HybridSearchResult> hybridResults =
                    hybridSearchService.hybridSearch(question, topK * 2);

            // 2. 重排序
            List<org.example.rag.RerankService.RerankResult> rerankedResults =
                    rerankService.rerank(question, hybridResults, topK);

            // 3. 知识图谱扩展
            String graphContext = knowledgeGraphService.buildGraphContext(question);

            // 转换为 SearchResult 格式
            List<VectorSearchService.SearchResult> searchResults = rerankedResults.stream()
                    .map(r -> {
                        VectorSearchService.SearchResult sr = new VectorSearchService.SearchResult();
                        sr.setId(r.getId());
                        sr.setContent(r.getContent());
                        sr.setScore((float) r.getRelevanceScore());
                        sr.setMetadata(r.getSource());
                        return sr;
                    })
                    .toList();

            callback.onSearchResults(searchResults);

            if (searchResults.isEmpty()) {
                logger.warn("未找到相关文档");
                callback.onComplete("抱歉，我在知识库中没有找到相关信息来回答您的问题。", "");
                return;
            }

            // 2. 构建上下文和提示词
            String context = buildContext(searchResults);
            if (graphContext != null && !graphContext.isEmpty()) {
                context = graphContext + "\n" + context;
            }
            String prompt = buildPrompt(question, context);

            // 3. 流式调用大语言模型（传入历史消息）
            generateAnswerStream(prompt, history, callback);

        } catch (Exception e) {
            logger.error("RAG 流式查询失败", e);
            callback.onError(e);
        }
    }

    /**
     * 构建上下文
     */
    private String buildContext(List<VectorSearchService.SearchResult> searchResults) {
        StringBuilder context = new StringBuilder();

        for (int i = 0; i < searchResults.size(); i++) {
            VectorSearchService.SearchResult result = searchResults.get(i);
            context.append("【参考资料 ").append(i + 1).append("】\n");
            context.append(result.getContent()).append("\n\n");
        }

        return context.toString();
    }

    /**
     * 构建提示词
     */
    private String buildPrompt(String question, String context) {
        return String.format(
            "你是一个专业的AI助手。请根据以下参考资料回答用户的问题。\n\n" +
            "参考资料：\n%s\n" +
            "用户问题：%s\n\n" +
            "请基于上述参考资料给出准确、详细的回答。如果参考资料中没有相关信息，请明确说明。",
            context, question
        );
    }

    /**
     * 生成答案（流式）
     */
    private void generateAnswerStream(String prompt, List<Map<String, String>> history, StreamCallback callback) {
        // 构建消息列表：历史消息 + 当前问题
        List<Message> messages = new ArrayList<>();

        // 添加历史消息
        for (Map<String, String> historyMsg : history) {
            String role = historyMsg.get("role");
            String content = historyMsg.get("content");

            if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            }
        }

        // 添加当前用户问题
        messages.add(new UserMessage(prompt));

        logger.debug("发送给AI模型的消息数量: {}", messages.size());

        logger.info("开始调用AI模型流式接口...");

        StringBuilder finalContent = new StringBuilder();

        // 使用 ChatClient 流式调用
        Flux<String> contentFlux = chatClient.prompt()
                .messages(messages)
                .stream()
                .content();

        contentFlux.doOnNext(chunk -> {
                    if (chunk != null && !chunk.isEmpty()) {
                        finalContent.append(chunk);
                        callback.onContentChunk(chunk);
                    }
                })
                .doOnComplete(() -> {
                    logger.info("AI模型流式响应完成，总内容长度: {}", finalContent.length());
                    callback.onComplete(finalContent.toString(), "");
                })
                .doOnError(error -> {
                    logger.error("AI模型流式调用失败", error);
                    callback.onError(new Exception("AI模型流式调用失败: " + error.getMessage(), error));
                })
                .blockLast(); // 阻塞等待流完成
    }

    /**
     * 流式回调接口
     */
    public interface StreamCallback {
        void onSearchResults(List<VectorSearchService.SearchResult> results);
        void onReasoningChunk(String chunk);
        void onContentChunk(String chunk);
        void onComplete(String fullContent, String fullReasoning);
        void onError(Exception e);
    }
}
