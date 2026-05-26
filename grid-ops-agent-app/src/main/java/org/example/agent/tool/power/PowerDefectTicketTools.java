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
public class PowerDefectTicketTools {

    private static final Logger logger = LoggerFactory.getLogger(PowerDefectTicketTools.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockDataLoader mockDataLoader;

    @Value("${power.mock-enabled:true}")
    private boolean mockEnabled;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    public PowerDefectTicketTools(MockDataLoader mockDataLoader) {
        this.mockDataLoader = mockDataLoader;
    }

    @Tool(description = "查询电力设备历史缺陷工单，包括缺陷描述、处理状态、处理结果等。" +
            "适用于了解设备历史缺陷情况、判断当前告警是否与历史缺陷相关、辅助故障诊断。")
    public String getDefectTickets(
            @ToolParam(description = "设备编号，如 TR-110KV-001") String deviceId,
            @ToolParam(description = "缺陷类型过滤，如 冷却器,温控器,油温。为空返回所有类型") String defectType,
            @ToolParam(description = "查询时间范围，如 30d, 90d, 180d。默认180d") String timeRange) {

        logger.info("查询缺陷工单: deviceId={}, defectType={}, timeRange={}", deviceId, defectType, timeRange);

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceId", deviceId);
            result.put("queryTime", FORMATTER.format(Instant.now()));

            if (mockEnabled) {
                List<Map<String, Object>> tickets = mockDataLoader.getDefectTicketsFiltered(deviceId, defectType);
                result.put("tickets", tickets);
                result.put("total", tickets.size());

                if (tickets.isEmpty()) {
                    result.put("analysis", "该设备近半年无" + (defectType != null ? defectType + "相关" : "") + "缺陷工单记录。");
                } else {
                    long pending = tickets.stream().filter(t -> "待处理".equals(t.get("status")) || "待复查".equals(t.get("status"))).count();
                    result.put("analysis", "共查到" + tickets.size() + "条缺陷工单，其中" + pending + "条待处理/待复查。");
                }
            } else {
                result.put("error", "真实数据源未接入");
                result.put("mode", "REAL_MODE_UNAVAILABLE");
                result.put("message", "请接入缺陷工单系统后使用");
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            logger.error("查询缺陷工单失败", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
