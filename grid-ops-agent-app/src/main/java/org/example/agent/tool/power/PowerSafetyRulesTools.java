package org.example.agent.tool.power;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.MockDataLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PowerSafetyRulesTools {

    private static final Logger logger = LoggerFactory.getLogger(PowerSafetyRulesTools.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockDataLoader mockDataLoader;

    @Value("${power.mock-enabled:true}")
    private boolean mockEnabled;

    public PowerSafetyRulesTools(MockDataLoader mockDataLoader) {
        this.mockDataLoader = mockDataLoader;
    }

    @Tool(description = "查询电力安全工作规程和作业安全要求。" +
            "适用于高压室作业安全、倒闸操作安全、设备巡检安全、带电作业安全等场景。" +
            "当用户询问安全注意事项、操作规程、安全措施等问题时使用此工具。")
    public String searchSafetyRules(
            @ToolParam(description = "查询内容，如 高压室作业安全,倒闸操作,设备巡检安全措施") String query,
            @ToolParam(description = "规程类型过滤，如 安规,运维规程,抢修手册。为空返回所有类型") String ruleType) {

        logger.info("查询安规: query={}, ruleType={}", query, ruleType);

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);

            if (mockEnabled) {
                List<Map<String, Object>> rules = mockDataLoader.searchSafetyRules(query, ruleType);

                if (rules.isEmpty()) {
                    Map<String, Object> defaultRule = new LinkedHashMap<>();
                    defaultRule.put("ruleId", "DL5009.3-2013-1.1");
                    defaultRule.put("source", "电力安全工作规程");
                    defaultRule.put("content", "电力作业必须遵守安全工作规程，作业前确认安全措施到位，作业中严格执行操作规程，作业后确认设备恢复正常状态。");
                    defaultRule.put("safetyLevel", "强制");
                    rules = List.of(defaultRule);
                }

                result.put("rules", rules);
                result.put("total", rules.size());
            } else {
                result.put("error", "真实数据源未接入");
                result.put("mode", "REAL_MODE_UNAVAILABLE");
                result.put("message", "请接入安规查询系统后使用");
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            logger.error("查询安规失败", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
