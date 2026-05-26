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
public class PowerDeviceProfileTools {

    private static final Logger logger = LoggerFactory.getLogger(PowerDeviceProfileTools.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockDataLoader mockDataLoader;

    @Value("${power.mock-enabled:true}")
    private boolean mockEnabled;

    public PowerDeviceProfileTools(MockDataLoader mockDataLoader) {
        this.mockDataLoader = mockDataLoader;
    }

    @Tool(description = "查询电力设备台账信息，包括设备型号、厂家、投运时间、技术参数等。" +
            "适用于了解设备基本信息、判断设备是否在保修期、查找设备说明书等场景。")
    public String getDeviceProfile(
            @ToolParam(description = "设备编号，如 TR-110KV-001") String deviceId) {

        logger.info("查询设备台账: deviceId={}", deviceId);

        try {
            if (mockEnabled) {
                Map<String, Object> profile = mockDataLoader.getDeviceProfileOrDefault(deviceId);
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(profile);
            } else {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "error", "真实数据源未接入",
                        "mode", "REAL_MODE_UNAVAILABLE",
                        "message", "请接入设备台账系统（PMS或EAM）后使用"
                ));
            }
        } catch (Exception e) {
            logger.error("查询设备台账失败", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
