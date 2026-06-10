package org.example.graph.subgraph.diagnosis;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.example.graph.GraphStateKeys;
import org.example.graph.model.PlanStep;
import org.example.graph.model.StepResult;
import org.example.graph.validation.ToolResultValidator;
import org.example.graph.validation.ValidationResult;
import org.example.graph.validation.PlanValidator;
import org.example.hook.HookContext;
import org.example.hook.HookEngine;
import org.example.observability.ObservabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ExecutorNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ExecutorNode.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolCallbackProvider tools;
    private final RetryRegistry retryRegistry;
    private final ToolResultValidator toolResultValidator;
    private final PlanValidator planValidator;
    private final ObservabilityService observabilityService;
    private final HookEngine hookEngine;

    public ExecutorNode(ToolCallbackProvider tools, RetryRegistry retryRegistry,
                        ToolResultValidator toolResultValidator,
                        PlanValidator planValidator,
                        ObservabilityService observabilityService,
                        HookEngine hookEngine) {
        this.tools = tools;
        this.retryRegistry = retryRegistry;
        this.toolResultValidator = toolResultValidator;
        this.planValidator = planValidator;
        this.observabilityService = observabilityService;
        this.hookEngine = hookEngine;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        List<PlanStep> planSteps = readSteps(state.value(GraphStateKeys.PLAN_STEPS).orElse(List.of()));
        Map<String, ToolCallback> callbackMap = Arrays.stream(tools.getToolCallbacks())
                .collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
        appendAdditionalSteps(planSteps, state.value(GraphStateKeys.ADDITIONAL_STEPS).orElse(List.of()), callbackMap.keySet());
        if (planSteps.isEmpty()) {
            logger.warn("ExecutorNode: no plan steps, skipping execution");
            return Map.of(GraphStateKeys.EVIDENCE, "", GraphStateKeys.EXECUTION_RESULT, "", GraphStateKeys.STEP_RESULTS, List.of());
        }

        List<StepResult> allResults = readResults(state.value(GraphStateKeys.STEP_RESULTS).orElse(List.of()));
        List<StepResult> newResults = new ArrayList<>();
        StringBuilder evidenceBuilder = new StringBuilder(state.value(GraphStateKeys.EVIDENCE).map(Object::toString).orElse(""));
        String taskId = state.value(GraphStateKeys.TASK_ID).map(Object::toString).orElse(null);
        String sessionId = state.value(GraphStateKeys.SESSION_ID).map(Object::toString).orElse(null);
        String traceId = state.value(GraphStateKeys.TRACE_ID).map(Object::toString).orElseGet(observabilityService::generateTraceId);

        for (PlanStep step : planSteps) {
            if ("COMPLETED".equals(step.effectiveStatus())
                    || "SKIPPED".equals(step.effectiveStatus())
                    || "FAILED".equals(step.effectiveStatus())) {
                continue;
            }
            if (!"TOOL_CALL".equals(step.effectiveStepType())) {
                step.setStatus("SKIPPED");
                continue;
            }

            StepResult stepResult = executeToolStep(step, callbackMap, traceId, taskId, sessionId);
            newResults.add(stepResult);
            allResults.add(stepResult);
            step.setStatus(stepResult.isSuccess() ? "COMPLETED" : "FAILED");
            step.setRetryCount(stepResult.getRetryCount());
            step.setResult(stepResult.getResult());

            evidenceBuilder.append("证据：")
                    .append(nullToBlank(step.getAction()))
                    .append("，能力 ").append(step.getBusinessSkillName() == null || step.getBusinessSkillName().isBlank()
                            ? step.effectiveToolName() : step.getBusinessSkillName())
                    .append(" 返回").append(statusText(stepResult.getStatus()))
                    .append("。").append(summarizeResult(stepResult.getResult()))
                    .append("\n");
        }

        boolean requiredFailure = newResults.stream().anyMatch(result -> !result.isSuccess()
                && planSteps.stream().filter(PlanStep::effectiveRequired)
                .anyMatch(step -> step.effectiveStepId().equals(result.getStepId())));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put(GraphStateKeys.EVIDENCE, evidenceBuilder.toString());
        output.put(GraphStateKeys.EXECUTION_RESULT, evidenceBuilder.toString());
        output.put(GraphStateKeys.STEP_RESULTS, allResults);
        output.put(GraphStateKeys.PLAN_STEPS, planSteps);
        output.put(GraphStateKeys.ADDITIONAL_STEPS, List.of());
        if (requiredFailure) {
            output.put(GraphStateKeys.NEXT_ACTION, "REPLAN");
        }
        logger.info("ExecutorNode: executed {} new steps, requiredFailure={}", newResults.size(), requiredFailure);
        return output;
    }

    private StepResult executeToolStep(PlanStep step, Map<String, ToolCallback> callbackMap,
                                       String traceId, String taskId, String sessionId) {
        String toolName = step.effectiveToolName();
        String toolInput = toJson(step.getParams() == null ? Map.of() : step.getParams());
        ToolCallback callback = callbackMap.get(toolName);
        long start = System.currentTimeMillis();

        StepResult.StepResultBuilder result = StepResult.builder()
                .stepId(step.effectiveStepId())
                .stepNo(step.effectiveStepNo())
                .action(step.getAction())
                .toolName(toolName)
                .businessSkillId(step.getBusinessSkillId())
                .businessSkillName(step.getBusinessSkillName())
                .retryCount(0)
                .evidenceType(toolResultValidator.evidenceType(toolName));

        String paramError = validateRequiredParams(toolName, step.getParams());
        if (paramError != null) {
            logTool(traceId, taskId, sessionId, toolName, toolInput, paramError, "FAILED", start);
            return result.status("FAILED")
                    .success(false)
                    .result(paramError)
                    .error(paramError)
                    .errorType("PARAMETER_MISMATCH")
                    .recoverable(true)
                    .nextSuggestion("REPLAN")
                    .matchExpected(false)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        if (callback == null) {
            String message = "Tool not found: " + toolName;
            logTool(traceId, taskId, sessionId, toolName, toolInput, message, "FAILED", start);
            return result.status("FAILED")
                    .success(false)
                    .result(message)
                    .error(message)
                    .errorType("TOOL_NOT_REGISTERED")
                    .recoverable(true)
                    .nextSuggestion("REPLAN")
                    .matchExpected(false)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        try {
            // PRE_TOOL_USE hook
            HookContext preCtx = HookContext.builder()
                    .sessionId(sessionId).taskId(taskId).agentName(toolName)
                    .params(step.getParams()).input(toolInput).build();
            hookEngine.executeHooks("PRE_TOOL_USE", preCtx);

            Retry retry = retryRegistry.retry(toolName.startsWith("get") ? "mcpTool" : "llmCall");
            Callable<String> decorated = Retry.decorateCallable(retry, () -> callback.call(toolInput));
            String response = decorated.call();

            // POST_TOOL_USE hook
            HookContext postCtx = HookContext.builder()
                    .sessionId(sessionId).taskId(taskId).agentName(toolName)
                    .output(response).params(step.getParams()).build();
            hookEngine.executeHooks("POST_TOOL_USE", postCtx);

            ValidationResult validation = toolResultValidator.validate(toolName, response);
            long duration = System.currentTimeMillis() - start;
            if (!validation.isValid()) {
                logTool(traceId, taskId, sessionId, toolName, toolInput, response, "FAILED", start);
                return result.status("FAILED")
                        .success(false)
                        .result(response)
                        .error(String.join("; ", validation.getErrors()))
                        .errorType(validation.getErrorType())
                        .recoverable(validation.isRecoverable())
                        .nextSuggestion(validation.isRecoverable() ? "REPLAN" : "STOP")
                        .matchExpected(false)
                        .durationMs(duration)
                        .build();
            }
            logTool(traceId, taskId, sessionId, toolName, toolInput, response, "SUCCESS", start);
            return result.status("COMPLETED")
                    .success(true)
                    .result(response)
                    .recoverable(false)
                    .matchExpected(true)
                    .durationMs(duration)
                    .build();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String errorType = classify(e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            logTool(traceId, taskId, sessionId, toolName, toolInput, message, "FAILED", start);
            return result.status("FAILED")
                    .success(false)
                    .result("Tool execution failed: " + message)
                    .error(message)
                    .errorType(errorType)
                    .recoverable(!"INTERFACE_UNAUTHORIZED".equals(errorType))
                    .nextSuggestion("REPLAN")
                    .matchExpected(false)
                    .durationMs(duration)
                    .build();
        }
    }

    private List<PlanStep> readSteps(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        return OBJECT_MAPPER.convertValue(list, new TypeReference<>() {});
    }

    private List<StepResult> readResults(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        return OBJECT_MAPPER.convertValue(list, new TypeReference<>() {});
    }

    private void appendAdditionalSteps(List<PlanStep> planSteps, Object additionalStepsObj, java.util.Set<String> availableTools) {
        if (!(additionalStepsObj instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<PlanStep> additionalSteps = OBJECT_MAPPER.convertValue(list, new TypeReference<>() {});
        int nextNo = planSteps.stream().mapToInt(PlanStep::effectiveStepNo).max().orElse(0) + 1;
        for (PlanStep step : additionalSteps) {
            List<String> warnings = new ArrayList<>();
            planValidator.normalizeAdditionalStep(step, nextNo, availableTools, warnings);
            if (!warnings.isEmpty()) {
                logger.info("ExecutorNode: normalized additional step {}, warnings={}", step.effectiveStepId(), warnings);
            }
            step.setStatus("PENDING");
            step.setRetryCount(0);
            planSteps.add(step);
            nextNo++;
        }
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String classify(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (message.contains("timeout") || message.contains("timed out")) {
            return "INTERFACE_TIMEOUT";
        }
        if (message.contains("connection") || message.contains("connect")) {
            return "INTERFACE_EXCEPTION";
        }
        if (message.contains("unauthorized") || message.contains("forbidden")) {
            return "INTERFACE_UNAUTHORIZED";
        }
        return "INTERFACE_EXCEPTION";
    }

    private String validateRequiredParams(String toolName, Map<String, Object> params) {
        if (toolName == null || params == null) {
            return null;
        }
        if (toolName.startsWith("getDevice") || "getAlarmHistory".equals(toolName) || "getDefectTickets".equals(toolName)) {
            String deviceId = stringParam(params, "deviceId");
            if (deviceId.isBlank() || "UNKNOWN".equalsIgnoreCase(deviceId) || "未知".equals(deviceId)) {
                return "参数不匹配: " + toolName + " 需要有效 deviceId";
            }
        }
        return null;
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            value = params.get(key.toLowerCase());
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String summarizeResult(String result) {
        if (result == null || result.isBlank()) {
            return "无返回";
        }
        String structured = summarizeJsonResult(result);
        if (structured != null) {
            return structured;
        }
        String compact = result.replaceAll("\\s+", " ").trim();
        if (compact.length() > 260) {
            compact = compact.substring(0, 260) + "...";
        }
        return compact;
    }

    private String summarizeJsonResult(String result) {
        String trimmed = result.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(trimmed);
            if (root.isArray()) {
                return summarizeSearchResults(root);
            }
            String mode = text(root, "mode");
            if ("RESOURCE_GRAPH_QUERY".equals(mode)) {
                int nodes = root.path("nodes").isArray() ? root.path("nodes").size() : 0;
                int edges = root.path("edges").isArray() ? root.path("edges").size() : 0;
                return "知识图谱查询完成，命中节点 " + nodes + " 个、关系 " + edges + " 条；查询主题：" + textOr(root, "query", "未提供");
            }
            if ("MOCK_SCENARIO_GENERATION".equals(mode)) {
                int scenarios = root.path("scenarios").isArray() ? root.path("scenarios").size() : 0;
                return "生成 " + scenarios + " 个模拟故障场景；对象：" + textOr(root, "fault_entity", "未指定")
                        + "；类型：" + textOr(root, "fault_type", "未指定") + "。结果为 MOCK_SCENARIO_GENERATION，仅作预案推演参考";
            }
            if ("MOCK_RISK_CHECK".equals(mode)) {
                int findings = root.path("findings").isArray() ? root.path("findings").size() : 0;
                return "完成模拟风险校核，风险等级：" + textOr(root, "risk_level", "UNKNOWN")
                        + "；发现项 " + findings + " 条；目标：" + textOr(root, "target", "未指定")
                        + "。结果为 MOCK_RISK_CHECK，不代表真实在线安全校核";
            }
            if ("SEMI_REAL_RULE_RISK_CHECK".equals(mode)) {
                int findings = root.path("findings").isArray() ? root.path("findings").size() : 0;
                return "完成规则风险校核，风险等级：" + textOr(root, "risk_level", "UNKNOWN")
                        + "；规则命中 " + findings + " 项；对象：" + textOr(root, "device_id", textOr(root, "target", "未指定"))
                        + "。结果基于演示数据和工程阈值，需人工复核";
            }
            if ("SEMI_REAL_RULE_MECHANISM_CHECK".equals(mode)) {
                JsonNode calculation = root.path("calculation");
                int findings = root.path("findings").isArray() ? root.path("findings").size() : 0;
                return "完成主变油温规则校核，风险等级：" + textOr(root, "risk_level", "UNKNOWN")
                        + "；油温裕度 " + textOr(calculation, "temperature_margin_celsius", "未知") + "℃"
                        + "；负荷率 " + textOr(calculation, "load_rate_percent", "未知") + "%"
                        + "；规则命中 " + findings + " 项。结果为演示状态量与规则阈值推演";
            }
            if ("MOCK_ESTIMATE".equals(mode)) {
                return "完成模拟潮流估算；区域：" + textOr(root, "area", "未指定")
                        + "；场景：" + textOr(root, "scenario", "未指定")
                        + "。结果为 MOCK_ESTIMATE，不代表真实 EMS/DTS 计算";
            }
            if ("SEMI_REAL_RULE_ESTIMATE".equals(mode)) {
                JsonNode metrics = root.path("metrics");
                return "完成规则化潮流/负载估算；区域：" + textOr(root, "area", "未指定")
                        + "；最大负载率 " + textOr(metrics, "max_line_loading_percent", "未知") + "%"
                        + "；电压越限 " + textOr(metrics, "voltage_violation_count", "0") + " 项"
                        + "。结果基于演示数据和规则估算，需 EMS/DTS 复核";
            }
            if (root.has("rules")) {
                int total = root.path("rules").isArray() ? root.path("rules").size() : root.path("total").asInt(0);
                String first = root.path("rules").isArray() && !root.path("rules").isEmpty()
                        ? textOr(root.path("rules").get(0), "content", "")
                        : "";
                return "检索到安全规程 " + total + " 条" + (first.isBlank() ? "" : "；首条要点：" + truncate(first, 90));
            }
            if ("error".equals(text(root, "status"))) {
                return "工具返回错误：" + textOr(root, "message", "未提供错误信息");
            }
            if ("no_results".equals(text(root, "status"))) {
                return "未检索到匹配文档：" + textOr(root, "message", "");
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String summarizeSearchResults(JsonNode array) {
        if (array.isEmpty()) {
            return "知识库未返回匹配文档";
        }
        JsonNode first = array.get(0);
        String content = textOr(first, "content", "");
        String docName = metadataValue(first.path("metadata").asText(""), "documentName");
        if (docName.isBlank()) {
            docName = metadataValue(first.path("metadata").asText(""), "_file_name");
        }
        return "知识库检索到 " + array.size() + " 条候选资料"
                + (docName.isBlank() ? "" : "；首条来源：" + docName)
                + (content.isBlank() ? "" : "；要点：" + truncate(content, 110));
    }

    private String metadataValue(String metadata, String key) {
        if (metadata == null || metadata.isBlank()) {
            return "";
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(metadata);
            return textOr(node, key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private String text(JsonNode node, String key) {
        return node.has(key) && !node.path(key).isNull() ? node.path(key).asText("") : "";
    }

    private String textOr(JsonNode node, String key, String fallback) {
        String value = text(node, key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String statusText(String status) {
        if ("COMPLETED".equals(status)) {
            return "成功";
        }
        if ("FAILED".equals(status)) {
            return "失败";
        }
        return status == null ? "未知" : status;
    }

    private void logTool(String traceId, String taskId, String sessionId, String toolName,
                         String request, String response, String status, long start) {
        observabilityService.logToolCall(traceId, taskId, sessionId, toolName, request, response, status,
                System.currentTimeMillis() - start);
    }
}
