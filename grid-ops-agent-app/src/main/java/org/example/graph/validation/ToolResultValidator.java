package org.example.graph.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class ToolResultValidator {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ValidationResult validate(String toolName, String result) {
        if (result == null || result.isBlank()) {
            return ValidationResult.invalid("工具返回为空", "EMPTY_TOOL_RESULT", true);
        }

        String lower = result.toLowerCase(Locale.ROOT);
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return ValidationResult.invalid("工具调用超时", "INTERFACE_TIMEOUT", true);
        }
        if (lower.contains("unauthorized") || lower.contains("forbidden")) {
            return ValidationResult.invalid("工具调用未授权", "INTERFACE_UNAUTHORIZED", false);
        }

        JsonNode json = null;
        if (looksLikeJson(result)) {
            try {
                json = objectMapper.readTree(result);
            } catch (Exception e) {
                return ValidationResult.invalid("工具返回不是合法 JSON: " + e.getMessage(), "INVALID_RESPONSE_FORMAT", true);
            }
        }

        ValidationResult semanticFailure = validateSemanticFailure(json, result);
        if (!semanticFailure.isValid()) {
            return semanticFailure;
        }

        ValidationResult shapeResult = validateShape(toolName, json, result);
        if (!shapeResult.isValid()) {
            return shapeResult;
        }
        return ValidationResult.ok();
    }

    public String evidenceType(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case "getDeviceProfile" -> "DEVICE_PROFILE";
            case "getDeviceStatus" -> "DEVICE_STATUS";
            case "getAlarmHistory" -> "ALARM_HISTORY";
            case "getDeviceLogs" -> "DEVICE_LOGS";
            case "getDefectTickets" -> "DEFECT_TICKETS";
            case "searchSafetyRules" -> "SAFETY_RULES";
            case "queryInternalDocs" -> "RAG_DOCS";
            default -> "OTHER";
        };
    }

    private ValidationResult validateShape(String toolName, JsonNode json, String raw) {
        if (json == null || toolName == null) {
            return ValidationResult.ok();
        }

        Map<String, String[]> requiredAny = Map.of(
                "getDeviceStatus", new String[]{"deviceId", "metrics", "status"},
                "getAlarmHistory", new String[]{"deviceId", "alarms", "total"},
                "getDeviceLogs", new String[]{"deviceId", "logs", "total"},
                "getDefectTickets", new String[]{"deviceId", "tickets", "total"},
                "getDeviceProfile", new String[]{"deviceId", "deviceName", "deviceType", "model", "manufacturer"},
                "searchSafetyRules", new String[]{"rules", "total", "query"},
                "queryInternalDocs", new String[]{"status", "content", "score"}
        );

        String[] fields = requiredAny.get(toolName);
        if (fields == null) {
            return ValidationResult.ok();
        }

        for (String field : fields) {
            if (json.has(field) || raw.contains("\"" + field + "\"")) {
                return ValidationResult.ok();
            }
        }
        return ValidationResult.invalid("工具结果缺少期望业务字段: " + toolName, "INVALID_RESPONSE_SCHEMA", true);
    }

    private ValidationResult validateSemanticFailure(JsonNode json, String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (json != null) {
            if (json.has("error")) {
                return ValidationResult.invalid("工具接口异常: " + json.path("error").asText(), "INTERFACE_EXCEPTION", true);
            }
            String status = json.path("status").asText("");
            String message = json.path("message").asText("");
            String combined = (status + " " + message).toLowerCase(Locale.ROOT);

            if (combined.contains("真实模式需要接入") || combined.contains("需要接入")) {
                return ValidationResult.invalid("数据源未接入: " + message, "DATA_SOURCE_NOT_CONNECTED", true);
            }
            if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status) || "failure".equalsIgnoreCase(status)) {
                return ValidationResult.invalid("工具接口异常: " + (message.isBlank() ? status : message), "INTERFACE_EXCEPTION", true);
            }
            if ("no_results".equalsIgnoreCase(status) || "未找到".equals(status)) {
                return ValidationResult.invalid("演示数据未覆盖当前查询条件", "MOCK_DATA_MISSING", true);
            }
        } else {
            if (lower.contains("真实模式需要接入") || lower.contains("需要接入")) {
                return ValidationResult.invalid("数据源未接入", "DATA_SOURCE_NOT_CONNECTED", true);
            }
            if (lower.contains("tool execution failed") || lower.contains("http 5") || lower.contains("connection refused")) {
                return ValidationResult.invalid("工具接口异常", "INTERFACE_EXCEPTION", true);
            }
        }

        return ValidationResult.ok();
    }

    private boolean looksLikeJson(String result) {
        String trimmed = result.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }
}
