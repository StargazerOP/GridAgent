package org.example.agent.tool.power;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.MockDataLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class PowerAlarmHistoryTools {

    private static final Logger logger = LoggerFactory.getLogger(PowerAlarmHistoryTools.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockDataLoader mockDataLoader;

    @Value("${power.mock-enabled:true}")
    private boolean mockEnabled;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    public PowerAlarmHistoryTools(MockDataLoader mockDataLoader) {
        this.mockDataLoader = mockDataLoader;
    }

    @Tool(description = "查询电力设备历史告警记录。可以按设备、告警类型、时间范围等条件查询。" +
            "适用于了解设备过去的告警情况、判断告警是否重复出现等场景。")
    public String getAlarmHistory(
            @ToolParam(description = "设备编号，如 TR-110KV-001") String deviceId,
            @ToolParam(description = "告警类型过滤，如 油温异常,局放异常。为空返回所有类型") String alarmType,
            @ToolParam(description = "查询时间范围，如 24h, 7d, 30d。默认7d") String timeRange,
            @ToolParam(description = "返回记录数量，默认10") Integer limit) {

        logger.info("查询历史告警: deviceId={}, alarmType={}, timeRange={}", deviceId, alarmType, timeRange);
        int actualLimit = (limit == null || limit <= 0) ? 10 : Math.min(limit, 50);

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceId", deviceId);
            result.put("queryTime", FORMATTER.format(Instant.now()));

            if (mockEnabled) {
                boolean alarmMissingScenario = containsAny(alarmType, "告警缺失", "无告警", "未告警", "缺失");
                if (alarmMissingScenario) {
                    result.put("status", "no_alarm_records");
                    result.put("alarms", List.of());
                    result.put("total", 0);
                    result.put("analysis", "演示场景：设备状态存在异常，但历史告警未返回记录，需核查告警配置、遥信/遥测上送链路和告警抑制规则。");
                    result.put("mockScenario", "ALARM_MISSING_CHECK");
                    return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
                }

                List<Map<String, Object>> alarms = mockDataLoader.getAlarmHistoryFiltered(deviceId, alarmType);
                if (alarms.size() > actualLimit) {
                    alarms = alarms.subList(0, actualLimit);
                }

                result.put("alarms", alarms);
                result.put("total", alarms.size());

                if (alarms.isEmpty()) {
                    result.put("analysis", "该设备近期无" + (alarmType != null ? alarmType + "相关" : "") + "告警记录。");
                } else {
                    long unprocessed = alarms.stream().filter(a -> "未处理".equals(a.get("status"))).count();
                    result.put("analysis", "共查到" + alarms.size() + "条告警记录，其中" + unprocessed + "条未处理。");
                }

                if (!alarms.isEmpty()) {
                    result.put("mockScenario", "PARAMETERIZED_FROM_DATA");
                }
            } else {
                result.put("error", "真实数据源未接入");
                result.put("mode", "REAL_MODE_UNAVAILABLE");
                result.put("message", "请接入监控告警系统后使用");
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            logger.error("查询历史告警失败", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private boolean containsAny(String value, String... tokens) {
        if (value == null) {
            return false;
        }
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
