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
public class PowerDeviceLogsTools {

    private static final Logger logger = LoggerFactory.getLogger(PowerDeviceLogsTools.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockDataLoader mockDataLoader;

    @Value("${power.mock-enabled:true}")
    private boolean mockEnabled;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    public PowerDeviceLogsTools(MockDataLoader mockDataLoader) {
        this.mockDataLoader = mockDataLoader;
    }

    @Tool(description = "查询电力设备运行日志，包括冷却器启停记录、温控器动作记录、保护动作记录等。" +
            "适用于分析设备故障前后的运行状态变化和异常事件。")
    public String getDeviceLogs(
            @ToolParam(description = "设备编号，如 TR-110KV-001") String deviceId,
            @ToolParam(description = "查询时间范围，如 1h, 24h。默认1h") String timeRange,
            @ToolParam(description = "关键词过滤，如 冷却器,启动失败,温控器。多个关键词用逗号分隔") String keywords) {

        logger.info("查询设备日志: deviceId={}, timeRange={}, keywords={}", deviceId, timeRange, keywords);

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceId", deviceId);
            result.put("queryTime", FORMATTER.format(Instant.now()));

            if (mockEnabled) {
                List<Map<String, Object>> logs = mockDataLoader.getDeviceLogsFiltered(deviceId, keywords);
                result.put("logs", logs);
                result.put("total", logs.size());

                if (logs.isEmpty()) {
                    result.put("analysis", "未找到与设备 " + deviceId + " 匹配的运行日志。");
                } else {
                    long errorCount = logs.stream()
                            .filter(l -> "ERROR".equals(l.get("level")) || "ALARM".equals(l.get("level")))
                            .count();
                    result.put("analysis", "共查到" + logs.size() + "条日志，其中" + errorCount + "条异常/告警级别日志。");
                }
            } else {
                result.put("error", "真实数据源未接入");
                result.put("mode", "REAL_MODE_UNAVAILABLE");
                result.put("message", "请接入设备日志系统后使用");
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            logger.error("查询设备日志失败", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
