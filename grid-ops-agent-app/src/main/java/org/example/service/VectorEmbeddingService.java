package org.example.service;

import org.example.config.LlmFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量嵌入服务
 * 通过 LlmFactory 统一使用配置的 Embedding 模型
 */
@Service
public class VectorEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    public VectorEmbeddingService(LlmFactory llmFactory) {
        this.embeddingModel = llmFactory.embeddingModel();
    }

    @PostConstruct
    public void init() {
        logger.info("向量嵌入服务初始化完成，模型: DeepSeek Embedding");
    }

    /**
     * 生成向量嵌入
     * @param content 文本内容
     * @return 向量嵌入（浮点数列表）
     */
    public List<Float> generateEmbedding(String content) {
        if (content == null || content.trim().isEmpty()) {
            logger.warn("内容为空，无法生成向量");
            throw new IllegalArgumentException("内容不能为空");
        }

        logger.debug("开始生成向量嵌入, 内容长度: {} 字符", content.length());

        try {
            float[] embedding = embeddingModel.embed(content);
            List<Float> result = new ArrayList<>(embedding.length);
            for (float v : embedding) {
                result.add(v);
            }

            logger.info("成功生成向量嵌入, 内容长度: {} 字符, 向量维度: {}",
                    content.length(), result.size());

            return result;
        } catch (Exception e) {
            logger.error("生成向量嵌入失败, 内容长度: {}", content != null ? content.length() : 0, e);
            throw new RuntimeException("生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量生成向量嵌入
     * @param contents 文本内容列表
     * @return 向量嵌入列表
     */
    public List<List<Float>> generateEmbeddings(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            logger.warn("内容列表为空，无法生成向量");
            return Collections.emptyList();
        }

        logger.info("开始批量生成向量嵌入, 数量: {}", contents.size());

        try {
            EmbeddingRequest request = new EmbeddingRequest(contents, null);
            EmbeddingResponse response = embeddingModel.call(request);

            if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
                throw new RuntimeException("Embedding API 返回空结果");
            }

            List<List<Float>> embeddings = new ArrayList<>();
            for (var embeddingResult : response.getResults()) {
                float[] embeddingArray = embeddingResult.getOutput();
                List<Float> embedding = new ArrayList<>(embeddingArray.length);
                for (float v : embeddingArray) {
                    embedding.add(v);
                }
                embeddings.add(embedding);
            }

            logger.info("成功批量生成向量嵌入, 数量: {}, 维度: {}",
                    embeddings.size(),
                    embeddings.isEmpty() ? 0 : embeddings.get(0).size());

            return embeddings;

        } catch (Exception e) {
            logger.error("批量生成向量嵌入失败", e);
            throw new RuntimeException("批量生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成查询向量
     */
    public List<Float> generateQueryVector(String query) {
        return generateEmbedding(query);
    }

    /**
     * 计算两个向量的余弦相似度
     */
    public float calculateCosineSimilarity(List<Float> vector1, List<Float> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("向量维度不匹配");
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += vector1.get(i) * vector1.get(i);
            norm2 += vector2.get(i) * vector2.get(i);
        }

        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
