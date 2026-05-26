package org.example.hook.impl;

import org.example.hook.AgentHook;
import org.example.hook.HookContext;
import org.example.hook.HookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PostRagHook implements AgentHook {

    private static final Logger logger = LoggerFactory.getLogger(PostRagHook.class);
    private static final double MIN_RELEVANCE_SCORE = 0.35;

    @Override
    public String getName() {
        return "post-rag-quality-filter";
    }

    @Override
    public int getOrder() {
        return 90;
    }

    @Override
    public HookResult execute(HookContext context) {
        String output = context.getOutput();
        if (output == null || output.isBlank()) {
            logger.warn("PostRagHook: RAG returned empty results");
            context.setParam("rag_filtered", true);
            context.setParam("rag_warning", "RAG检索无结果");
            return HookResult.proceed();
        }

        // Mark quality: if output is very short or generic, flag it
        if (output.length() < 100) {
            context.setParam("rag_warning", "RAG结果较短，可能覆盖不足");
        }

        // Check for low-quality indicators
        String lower = output.toLowerCase();
        if (lower.contains("no results") || lower.contains("not found") || lower.contains("未找到")) {
            context.setParam("rag_warning", "RAG未检索到匹配文档");
        } else {
            context.setParam("rag_quality", "acceptable");
        }

        logger.debug("PostRagHook: RAG output length={}, quality check done", output.length());
        return HookResult.proceed();
    }
}
