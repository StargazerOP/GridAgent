package org.example.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 封装 CompiledGraph.stream() 为 SSE 友好的回调接口。
 * CompiledGraph 本身就支持 Flux<NodeOutput> 流式输出，
 * 每个 NodeOutput 携带节点名、Agent 名、token 用量和当时的 OverAllState。
 */
@Service
public class GraphStreamService {

    private static final Logger logger = LoggerFactory.getLogger(GraphStreamService.class);

    @Autowired
    private CompiledGraph compiledGraph;

    /**
     * 以流式方式执行 Graph，每完成一个节点就回调一次。
     *
     * @param question 用户输入
     * @param sessionId 会话 ID
     * @param userId 用户 ID
     * @param intent  意图（null = 由 Router 自动判定）
     * @param callback 流式回调
     */
    public void streamGraph(String question,
                            String sessionId,
                            String userId,
                            String intent,
                            StreamCallback callback) {
        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put("input", question);
        initialState.put("session_id", sessionId != null ? sessionId : UUID.randomUUID().toString());
        initialState.put("user_id", userId != null ? userId : "default");
        initialState.put("task_id", "TASK-" + UUID.randomUUID().toString().substring(0, 8));
        if (intent != null) {
            initialState.put("intent", intent);
        }

        logger.info("启动 Graph 流式执行: question={}, intent={}", question, intent);

        Flux<NodeOutput> nodeFlux = compiledGraph.stream(initialState);

        NodeOutput[] lastOutput = {null};
        long startedAt = System.currentTimeMillis();
        AtomicInteger sequence = new AtomicInteger(0);

        nodeFlux.doOnNext(nodeOutput -> {
            lastOutput[0] = nodeOutput;
            String nodeName = nodeOutput.node();
            String agentName = nodeOutput.agent();

            if (nodeOutput.isSTART()) {
                callback.onStart();
                return;
            }

            if (nodeOutput.isEND()) {
                return;
            }

            OverAllState state = nodeOutput.state();

            // 提取节点输出的关键状态信息
            Map<String, Object> stepInfo = new LinkedHashMap<>();
            stepInfo.put("node", nodeName != null ? nodeName : "unknown");
            stepInfo.put("trace_id", initialState.get("task_id"));
            stepInfo.put("sequence", sequence.incrementAndGet());
            stepInfo.put("status", "COMPLETED");
            stepInfo.put("timestamp", System.currentTimeMillis());
            stepInfo.put("elapsed_ms", System.currentTimeMillis() - startedAt);
            stepInfo.put("node_category", classifyNode(nodeName));
            if (agentName != null && !agentName.isEmpty()) {
                stepInfo.put("agent", agentName);
            }

            // 提取常用的状态键用于前端展示
            extractIfPresent(state, stepInfo, "intent");
            extractIfPresent(state, stepInfo, "cleaned_input");
            extractIfPresent(state, stepInfo, "entities");
            extractIfPresent(state, stepInfo, "plan_steps");
            extractIfPresent(state, stepInfo, "step_results");
            extractIfPresent(state, stepInfo, "evidence_score");
            extractIfPresent(state, stepInfo, "evidence_coverage");
            extractIfPresent(state, stepInfo, "evidence_warnings");
            extractIfPresent(state, stepInfo, "diagnosis_result");
            extractIfPresent(state, stepInfo, "risk_level");
            extractIfPresent(state, stepInfo, "next_action");
            extractIfPresent(state, stepInfo, "review_decision");
            extractIfPresent(state, stepInfo, "rag_results");
            extractIfPresent(state, stepInfo, "workflow_context");
            extractIfPresent(state, stepInfo, "loop_count");
            extractIfPresent(state, stepInfo, "action_recommend");

            logger.info("Graph 节点完成: node={}, agent={}", nodeName, agentName);
            callback.onNodeOutput(stepInfo);
        })
        .doOnComplete(() -> {
            logger.info("Graph 流式执行完成");
            if (lastOutput[0] != null) {
                OverAllState finalState = lastOutput[0].state();
                String finalResponse = finalState.value("final_response")
                        .map(Object::toString)
                        .orElse("处理完成");
                callback.onComplete(finalResponse);
            } else {
                callback.onComplete("处理完成");
            }
        })
        .doOnError(error -> {
            logger.error("Graph 流式执行失败", error);
            callback.onError(new Exception("Graph 执行失败: " + error.getMessage(), error));
        })
        .blockLast(); // 阻塞等待流完成 (SSE 在独立线程中运行)
    }

    private void extractIfPresent(OverAllState state, Map<String, Object> target, String key) {
        state.value(key).ifPresent(val -> target.put(key, val));
    }

    private String classifyNode(String nodeName) {
        if (nodeName == null) {
            return "agent";
        }
        String lower = nodeName.toLowerCase();
        if (lower.contains("route")) {
            return "router";
        }
        if (lower.contains("context")) {
            return "context";
        }
        if (lower.contains("planner") || lower.contains("plan")) {
            return "planner";
        }
        if (lower.contains("executor") || lower.contains("tool")) {
            return "tool";
        }
        if (lower.contains("review") || lower.contains("final")) {
            return "review";
        }
        return "agent";
    }

    /**
     * 流式回调接口
     */
    public interface StreamCallback {
        /** Graph 开始执行 */
        void onStart();
        /** 每完成一个节点触发，stepInfo 包含节点名和该节点产出的关键状态 */
        void onNodeOutput(Map<String, Object> stepInfo);
        /** 全部节点执行完毕，finalResponse 为最终响应文本 */
        void onComplete(String finalResponse);
        /** 执行出错 */
        void onError(Exception e);
    }
}
