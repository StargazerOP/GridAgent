package org.example.service;

import org.example.config.LlmFactory;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class VectorEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    @Value("${rag.embedding.local-fallback.enabled:true}")
    private boolean localFallbackEnabled;

    @Value("${spring.ai.openai.embedding.base-url:${spring.ai.openai.base-url}}")
    private String embeddingBaseUrl;

    @Value("${spring.ai.openai.embedding.options.model}")
    private String embeddingModelName;

    public VectorEmbeddingService(LlmFactory llmFactory) {
        this.embeddingModel = llmFactory.embeddingModel();
    }

    @PostConstruct
    public void init() {
        logger.info("Vector embedding service initialized. localFallbackEnabled={}", localFallbackEnabled);
    }

    public List<Float> generateEmbedding(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }

        try {
            float[] embedding = embeddingModel.embed(content);
            List<Float> result = new ArrayList<>(embedding.length);
            for (float value : embedding) {
                result.add(value);
            }
            logger.info("Generated remote embedding. contentLength={}, dimension={}", content.length(), result.size());
            return result;
        } catch (Exception e) {
            if (localFallbackEnabled) {
                logger.warn("Remote embedding failed, using LOCAL_HASH_FALLBACK vector. contentLength={}, error={}",
                        content.length(), e.getMessage());
                return generateLocalHashEmbedding(content);
            }
            throw new RuntimeException("Failed to generate embedding: " + e.getMessage(), e);
        }
    }

    public List<List<Float>> generateEmbeddings(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            EmbeddingRequest request = new EmbeddingRequest(contents, null);
            EmbeddingResponse response = embeddingModel.call(request);

            if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
                throw new RuntimeException("Embedding API returned empty results");
            }

            List<List<Float>> embeddings = new ArrayList<>();
            for (var embeddingResult : response.getResults()) {
                float[] embeddingArray = embeddingResult.getOutput();
                List<Float> embedding = new ArrayList<>(embeddingArray.length);
                for (float value : embeddingArray) {
                    embedding.add(value);
                }
                embeddings.add(embedding);
            }

            logger.info("Generated remote embeddings. count={}, dimension={}",
                    embeddings.size(),
                    embeddings.isEmpty() ? 0 : embeddings.get(0).size());

            return embeddings;
        } catch (Exception e) {
            if (localFallbackEnabled) {
                logger.warn("Remote batch embedding failed, using LOCAL_HASH_FALLBACK vectors. count={}, error={}",
                        contents.size(), e.getMessage());
                List<List<Float>> fallback = new ArrayList<>(contents.size());
                for (String content : contents) {
                    fallback.add(generateLocalHashEmbedding(content));
                }
                return fallback;
            }
            throw new RuntimeException("Failed to generate batch embeddings: " + e.getMessage(), e);
        }
    }

    public List<Float> generateQueryVector(String query) {
        return generateEmbedding(query);
    }

    public Map<String, Object> checkRemoteEmbeddingApi() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("provider", providerName());
        status.put("model", embeddingModelName);
        status.put("baseUrl", maskBaseUrl(embeddingBaseUrl));
        status.put("fallbackEnabled", localFallbackEnabled);
        try {
            float[] embedding = embeddingModel.embed("rag health check");
            status.put("status", "UP");
            status.put("dimension", embedding.length);
            status.put("message", "Embedding API is reachable");
        } catch (Exception e) {
            status.put("status", "DOWN");
            status.put("message", e.getMessage());
        }
        return status;
    }

    public float calculateCosineSimilarity(List<Float> vector1, List<Float> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("vector dimensions do not match");
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += vector1.get(i) * vector1.get(i);
            norm2 += vector2.get(i) * vector2.get(i);
        }

        if (norm1 == 0.0f || norm2 == 0.0f) {
            return 0.0f;
        }
        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private List<Float> generateLocalHashEmbedding(String content) {
        float[] vector = new float[MilvusConstants.VECTOR_DIM];
        String normalized = content == null ? "" : content.toLowerCase().replaceAll("\\s+", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }

        for (int i = 0; i < normalized.length(); i++) {
            addFeature(vector, normalized.substring(i, i + 1), 1.0f);
            if (i + 2 <= normalized.length()) {
                addFeature(vector, normalized.substring(i, i + 2), 1.6f);
            }
        }

        float norm = 0.0f;
        for (float value : vector) {
            norm += value * value;
        }
        norm = (float) Math.sqrt(norm);

        List<Float> result = new ArrayList<>(vector.length);
        for (float value : vector) {
            result.add(norm == 0.0f ? 0.0f : value / norm);
        }
        return result;
    }

    private void addFeature(float[] vector, String feature, float weight) {
        int index = Math.floorMod(feature.hashCode(), vector.length);
        vector[index] += weight;
    }

    private String providerName() {
        if (embeddingBaseUrl != null && embeddingBaseUrl.contains("127.0.0.1:9910")) {
            return "Local BGE-M3";
        }
        if (embeddingBaseUrl != null && embeddingBaseUrl.contains("localhost:9910")) {
            return "Local BGE-M3";
        }
        return "OpenAI-compatible";
    }

    private String maskBaseUrl(String baseUrl) {
        return baseUrl == null || baseUrl.isBlank() ? "-" : baseUrl;
    }
}
