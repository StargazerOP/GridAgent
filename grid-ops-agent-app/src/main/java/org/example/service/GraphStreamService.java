package org.example.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 封装 CompiledGraph.stream() 为 SSE 友好的回调接口。
 * CompiledGraph 本身就支持 Flux<NodeOutput> 流式输出，
 * 每个 NodeOutput 携带节点名、Agent 名、token 用量和当时的 OverAllState。
 */
@Service
public class GraphStreamService {

    private static final Logger logger = LoggerFactory.getLogger(GraphStreamService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    @Qualifier("compiledPowerOpsGraph")
    private CompiledGraph compiledGraph;

    @Autowired
    @Qualifier("compiledAlarmDiagnosisGraph")
    private CompiledGraph alarmDiagnosisGraph;

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
        streamGraph(question, sessionId, userId, intent, callback, Map.of());
    }

    public void streamGraph(String question,
                            String sessionId,
                            String userId,
                            String intent,
                            StreamCallback callback,
                            Map<String, Object> extraState) {
        streamWithGraph(compiledGraph, "power_ops_workflow", question, sessionId, userId, intent, callback, extraState);
    }

    public void streamAlarmDiagnosis(String question,
                                     String sessionId,
                                     String userId,
                                     StreamCallback callback,
                                     Map<String, Object> extraState) {
        streamWithGraph(alarmDiagnosisGraph, "alarm_diagnosis_graph", question, sessionId, userId,
                "DIAGNOSIS", callback, extraState);
    }

    private void streamWithGraph(CompiledGraph graph,
                                 String graphName,
                                 String question,
                                 String sessionId,
                                 String userId,
                                 String intent,
                                 StreamCallback callback,
                                 Map<String, Object> extraState) {
        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put("input", question);
        initialState.put("session_id", sessionId != null ? sessionId : UUID.randomUUID().toString());
        initialState.put("user_id", userId != null ? userId : "default");
        initialState.put("task_id", "TASK-" + UUID.randomUUID().toString().substring(0, 8));
        initialState.put("graph_name", graphName);
        if (intent != null) {
            initialState.put("intent", intent);
        }
        initialState.putAll(extraState);

        logger.info("启动 Graph 流式执行: graph={}, question={}, intent={}", graphName, question, intent);

        Flux<NodeOutput> nodeFlux = graph.stream(initialState);

        NodeOutput[] lastOutput = {null};
        long startedAt = System.currentTimeMillis();
        AtomicLong lastNodeAt = new AtomicLong(startedAt);
        AtomicInteger sequence = new AtomicInteger(0);
        AtomicInteger emittedStepResults = new AtomicInteger(0);
        AtomicBoolean emittedPlanSteps = new AtomicBoolean(false);
        AtomicBoolean emittedWorkflowContext = new AtomicBoolean(false);
        AtomicBoolean emittedRagResults = new AtomicBoolean(false);

        nodeFlux.doOnNext(nodeOutput -> {
            lastOutput[0] = nodeOutput;
            String nodeName = nodeOutput.node();
            String agentName = nodeOutput.agent();

            if (nodeOutput.isSTART()) {
                long now = System.currentTimeMillis();
                lastNodeAt.set(now);
                callback.onStart();
                return;
            }

            if (nodeOutput.isEND()) {
                return;
            }

            OverAllState state = nodeOutput.state();
            long now = System.currentTimeMillis();
            long fallbackNodeDuration = Math.max(1L, now - lastNodeAt.getAndSet(now));

            // 提取节点输出的关键状态信息
            Map<String, Object> stepInfo = new LinkedHashMap<>();
            stepInfo.put("node", nodeName != null ? nodeName : "unknown");
            stepInfo.put("trace_id", initialState.get("task_id"));
            stepInfo.put("graph_name", graphName);
            stepInfo.put("sequence", sequence.incrementAndGet());
            stepInfo.put("status", "COMPLETED");
            stepInfo.put("timestamp", now);
            stepInfo.put("elapsed_ms", now - startedAt);
            stepInfo.put("cumulative_elapsed_ms", now - startedAt);
            stepInfo.put("node_category", classifyNode(nodeName));
            stepInfo.put("agent_role", agentRole(nodeName));
            stepInfo.put("agent_contribution", agentContribution(nodeName, state));
            stepInfo.put("agent_decision", agentDecision(nodeName, state));
            if (agentName != null && !agentName.isEmpty()) {
                stepInfo.put("agent", agentName);
            }
            Object measuredNode = state.value("_last_node_name").orElse(null);
            Object observedDuration = state.value("_last_node_duration_ms").orElse(null);
            long nodeDuration = reliableDuration(nodeName, measuredNode, observedDuration, fallbackNodeDuration);
            stepInfo.put("node_duration_ms", nodeDuration);
            stepInfo.put("measured_node", measuredNode != null ? measuredNode : nodeName);
            addStepResultDelta(state, stepInfo, emittedStepResults);

            // 提取常用的状态键用于前端展示
            extractIfPresent(state, stepInfo, "intent");
            extractIfPresent(state, stepInfo, "cleaned_input");
            extractIfPresent(state, stepInfo, "entities");
            extractIfPresent(state, stepInfo, "evidence_score");
            extractIfPresent(state, stepInfo, "evidence_coverage");
            extractIfPresent(state, stepInfo, "evidence_warnings");
            extractIfPresent(state, stepInfo, "diagnosis_result");
            extractIfPresent(state, stepInfo, "risk_level");
            extractIfPresent(state, stepInfo, "next_action");
            extractIfPresent(state, stepInfo, "review_decision");
            extractIfPresent(state, stepInfo, "loop_count");
            extractIfPresent(state, stepInfo, "action_recommend");
            extractOnceIfPresent(state, stepInfo, "plan_steps", emittedPlanSteps);
            extractOnceIfPresent(state, stepInfo, "workflow_context", emittedWorkflowContext);
            extractOnceIfPresent(state, stepInfo, "rag_results", emittedRagResults);

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

    private void extractOnceIfPresent(OverAllState state, Map<String, Object> target, String key, AtomicBoolean emitted) {
        if (emitted.get()) {
            return;
        }
        state.value(key).ifPresent(val -> {
            target.put(key, val);
            emitted.set(true);
        });
    }

    private void addStepResultDelta(OverAllState state, Map<String, Object> target, AtomicInteger emittedStepResults) {
        Object raw = state.value("step_results").orElse(null);
        List<?> results = asList(raw);
        int total = results.size();
        if (total == 0) {
            return;
        }

        int fromIndex = Math.min(emittedStepResults.get(), total);
        List<?> delta = new ArrayList<>(results.subList(fromIndex, total));
        emittedStepResults.set(total);

        target.put("step_results_total", total);
        if (!delta.isEmpty()) {
            target.put("step_results", delta);
            target.put("step_results_delta_count", delta.size());
            Map<String, Object> summary = summarizeToolResults(delta);
            target.put("tool_result_summary", summary);
            if (((Number) summary.getOrDefault("failed", 0)).intValue() > 0) {
                target.put("tool_result_status", "PARTIAL_FAILED");
            } else {
                target.put("tool_result_status", "ALL_COMPLETED");
            }
        }
    }

    private List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value == null) {
            return List.of();
        }
        return List.of(value);
    }

    private Map<String, Object> summarizeToolResults(List<?> results) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Integer> failuresByType = new LinkedHashMap<>();
        int success = 0;
        int failed = 0;
        for (Object result : results) {
            Map<String, Object> map = asMap(result);
            if (map.isEmpty()) {
                continue;
            }
            String status = readString(map, "status");
            String successText = readString(map, "success");
            boolean explicitlyFailed = "FAILED".equalsIgnoreCase(status)
                    || "ERROR".equalsIgnoreCase(status)
                    || "false".equalsIgnoreCase(successText);
            if (explicitlyFailed) {
                failed++;
                String type = readString(map, "errorType");
                if (type.isBlank()) {
                    type = readString(map, "error_type");
                }
                if (type.isBlank()) {
                    type = "UNCLASSIFIED";
                }
                failuresByType.merge(type, 1, Integer::sum);
            } else {
                success++;
            }
        }
        summary.put("total", results.size());
        summary.put("success", success);
        summary.put("failed", failed);
        if (!failuresByType.isEmpty()) {
            summary.put("failuresByType", failuresByType);
        }
        return summary;
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        if (value == null) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.convertValue(value, new TypeReference<>() {});
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private String readString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
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

    private String agentRole(String nodeName) {
        if (nodeName == null) {
            return "Orchestrator";
        }
        String lower = nodeName.toLowerCase();
        if (lower.contains("pre_check")) {
            return "InputGuardAgent";
        }
        if (lower.contains("context")) {
            return "WorkflowContextAgent";
        }
        if (lower.contains("route")) {
            return "RouterAgent";
        }
        if (lower.contains("entity")) {
            return "EntityAgent";
        }
        if (lower.contains("rag")) {
            return "KnowledgeAgent";
        }
        if (lower.contains("planner") || lower.contains("plan")) {
            return "PlannerAgent";
        }
        if (lower.contains("executor") || lower.contains("tool")) {
            return "ToolAgent";
        }
        if (lower.contains("evidence")) {
            return "EvidenceAgent";
        }
        if (lower.contains("replanner")) {
            return "ReplannerAgent";
        }
        if (lower.contains("diagnosis")) {
            return "DiagnosisAgent";
        }
        if (lower.contains("risk") || lower.contains("safety")) {
            return "SafetyReviewAgent";
        }
        if (lower.contains("recommend") || lower.contains("final")) {
            return "ReportAgent";
        }
        if (lower.contains("memory")) {
            return "MemoryAgent";
        }
        return "Orchestrator";
    }

    private String agentContribution(String nodeName, OverAllState state) {
        String lower = nodeName == null ? "" : nodeName.toLowerCase();
        if (lower.contains("context")) {
            return state.value("workflow_context").isPresent()
                    ? "匹配流程模板、候选知识节点和推荐能力"
                    : "装载会话、记忆与业务上下文";
        }
        if (lower.contains("route")) {
            return "判定任务意图并选择执行子图";
        }
        if (lower.contains("entity")) {
            return "抽取设备、告警、阈值和运行场景实体";
        }
        if (lower.contains("rag")) {
            int count = asList(state.value("rag_results").orElse(null)).size();
            return count > 0 ? "召回 " + count + " 条知识证据" : "检索相关规程和案例";
        }
        if (lower.contains("planner") || lower.equals("planner")) {
            int count = asList(state.value("plan_steps").orElse(null)).size();
            return count > 0 ? "生成 " + count + " 步可执行诊断计划" : "生成结构化诊断计划";
        }
        if (lower.contains("executor") || lower.contains("tool")) {
            int count = asList(state.value("step_results").orElse(null)).size();
            return count > 0 ? "累计形成 " + count + " 条工具证据" : "执行工具并记录证据";
        }
        if (lower.contains("evidence")) {
            Object score = state.value("evidence_score").orElse(null);
            return score != null ? "评估证据覆盖度，得分 " + score : "评估证据覆盖度和缺口";
        }
        if (lower.contains("replanner")) {
            Object next = state.value("next_action").orElse(null);
            return next != null ? "根据证据质量决定 " + next : "判断是否需要补查或降级输出";
        }
        if (lower.contains("diagnosis")) {
            return "融合证据形成诊断判断";
        }
        if (lower.contains("risk") || lower.contains("safety")) {
            return "识别风险等级、操作约束和人工复核要求";
        }
        if (lower.contains("recommend") || lower.contains("final")) {
            return "组织用户可读结论和处置建议";
        }
        if (lower.contains("memory")) {
            return "保存会话与执行摘要";
        }
        return "完成当前编排节点";
    }

    private String agentDecision(String nodeName, OverAllState state) {
        String lower = nodeName == null ? "" : nodeName.toLowerCase();
        if (lower.contains("route")) {
            return state.value("intent").map(Object::toString).orElse("");
        }
        if (lower.contains("evidence") || lower.contains("replanner")) {
            return state.value("next_action").map(Object::toString).orElse("");
        }
        if (lower.contains("risk")) {
            return state.value("risk_level").map(Object::toString).orElse("");
        }
        if (lower.contains("planner")) {
            return "PLAN_READY";
        }
        if (lower.contains("executor")) {
            return "EVIDENCE_COLLECTED";
        }
        return "";
    }

    private long reliableDuration(String nodeName, Object measuredNode, Object observedDuration, long fallbackNodeDuration) {
        Long observed = toLong(observedDuration);
        boolean matchesNode = measuredNode == null
                || nodeName == null
                || nodeName.equals(String.valueOf(measuredNode));
        if (matchesNode && observed != null && observed > 0) {
            return observed;
        }
        return Math.max(1L, fallbackNodeDuration);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
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
