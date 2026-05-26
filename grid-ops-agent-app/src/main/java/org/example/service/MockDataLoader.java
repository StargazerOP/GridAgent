package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;

@Component
public class MockDataLoader {

    private static final Logger logger = LoggerFactory.getLogger(MockDataLoader.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Map<String, Map<String, Object>> deviceProfiles = Map.of();
    private Map<String, List<Map<String, Object>>> alarmHistory = Map.of();
    private Map<String, List<Map<String, Object>>> deviceLogs = Map.of();
    private Map<String, List<Map<String, Object>>> defectTickets = Map.of();
    private List<Map<String, Object>> safetyRules = List.of();

    @PostConstruct
    public void init() {
        try {
            deviceProfiles = loadMapOfMaps("mock-data/device_profiles.json");
            alarmHistory = loadMapOfLists("mock-data/alarm_history.json");
            deviceLogs = loadMapOfLists("mock-data/device_logs.json");
            defectTickets = loadMapOfLists("mock-data/defect_tickets.json");
            safetyRules = loadList("mock-data/safety_rules.json");
            logger.info("MockDataLoader initialized: {} device profiles, {} alarm groups, {} log groups, {} defect ticket groups, {} safety rules",
                    deviceProfiles.size(), alarmHistory.size(), deviceLogs.size(), defectTickets.size(), safetyRules.size());
        } catch (Exception e) {
            logger.error("Failed to load mock data files", e);
        }
    }

    public Optional<Map<String, Object>> getDeviceProfile(String deviceId) {
        return Optional.ofNullable(deviceProfiles.get(deviceId));
    }

    public Map<String, Object> getDeviceProfileOrDefault(String deviceId) {
        return deviceProfiles.getOrDefault(deviceId, createNotFoundProfile(deviceId));
    }

    public List<Map<String, Object>> getAlarmHistory(String deviceId) {
        return alarmHistory.getOrDefault(deviceId, List.of());
    }

    public List<Map<String, Object>> getAlarmHistoryFiltered(String deviceId, String alarmType) {
        List<Map<String, Object>> alarms = alarmHistory.getOrDefault(deviceId, List.of());
        if (alarmType == null || alarmType.isBlank()) {
            return alarms;
        }
        return alarms.stream()
                .filter(a -> {
                    Object type = a.get("alarmType");
                    return type != null && type.toString().contains(alarmType);
                })
                .toList();
    }

    public List<Map<String, Object>> getDeviceLogs(String deviceId) {
        return deviceLogs.getOrDefault(deviceId, List.of());
    }

    public List<Map<String, Object>> getDeviceLogsFiltered(String deviceId, String keywords) {
        List<Map<String, Object>> logs = deviceLogs.getOrDefault(deviceId, List.of());
        if (keywords == null || keywords.isBlank()) {
            return logs;
        }
        String[] tokens = keywords.split("[,，]");
        return logs.stream()
                .filter(log -> {
                    String msg = String.valueOf(log.getOrDefault("message", ""));
                    String src = String.valueOf(log.getOrDefault("source", ""));
                    for (String token : tokens) {
                        if (msg.contains(token.trim()) || src.contains(token.trim())) {
                            return true;
                        }
                    }
                    return false;
                })
                .toList();
    }

    public List<Map<String, Object>> getDefectTickets(String deviceId) {
        return defectTickets.getOrDefault(deviceId, List.of());
    }

    public List<Map<String, Object>> getDefectTicketsFiltered(String deviceId, String defectType) {
        List<Map<String, Object>> tickets = defectTickets.getOrDefault(deviceId, List.of());
        if (defectType == null || defectType.isBlank()) {
            return tickets;
        }
        return tickets.stream()
                .filter(t -> {
                    Object type = t.get("defectType");
                    return type != null && type.toString().contains(defectType);
                })
                .toList();
    }

    public List<Map<String, Object>> searchSafetyRules(String query, String ruleType) {
        if (safetyRules.isEmpty()) {
            return List.of();
        }
        return safetyRules.stream()
                .filter(rule -> {
                    if (query == null || query.isBlank()) {
                        return true;
                    }
                    Object keywords = rule.get("keywords");
                    if (keywords instanceof List<?> kwList) {
                        for (Object kw : kwList) {
                            if (query.contains(String.valueOf(kw))) {
                                return true;
                            }
                        }
                    }
                    String src = String.valueOf(rule.getOrDefault("source", ""));
                    String content = String.valueOf(rule.getOrDefault("content", ""));
                    return content.contains(query) || src.contains(query);
                })
                .filter(rule -> {
                    if (ruleType == null || ruleType.isBlank()) {
                        return true;
                    }
                    String src = String.valueOf(rule.getOrDefault("source", ""));
                    return src.contains(ruleType);
                })
                .toList();
    }

    public Map<String, Object> getDeviceStatusMock(String deviceId) {
        Map<String, Object> profile = deviceProfiles.get(deviceId);
        Map<String, Object> metrics = new LinkedHashMap<>();

        if (profile == null) {
            metrics.put("status", "未知设备");
            metrics.put("message", "未找到设备 " + deviceId + " 的档案信息，无法提供状态数据");
            return metrics;
        }

        String deviceType = String.valueOf(profile.getOrDefault("deviceType", ""));
        String deviceName = String.valueOf(profile.getOrDefault("deviceName", ""));

        if (deviceType.contains("变压器")) {
            if (deviceId.equals("TR-110KV-001")) {
                metrics.put("oilTemperature", "86℃");
                metrics.put("oilTemperatureThreshold", "80℃");
                metrics.put("oilTemperatureMargin", "+6℃");
                metrics.put("loadRate", "92%");
                metrics.put("loadRateThreshold", "90%");
                metrics.put("loadCurrentA", "278A");
                metrics.put("ratedCurrentA", "302A");
                metrics.put("coolerStatus", "异常");
                metrics.put("coolerFan1", "运行（转速1450rpm）");
                metrics.put("coolerFan2", "启动失败（KM2未吸合）");
                metrics.put("coolerFan3", "备用未投入");
                metrics.put("coolerControlMode", "自动");
                metrics.put("environmentTemp", "32℃");
                metrics.put("oilLevel", "62%");
                metrics.put("windingTemp", "78℃");
                metrics.put("alarmPresent", true);
                metrics.put("diagnosticFlags", List.of("OIL_TEMP_OVER_THRESHOLD", "COOLER_FAN_START_FAILED", "LOAD_RATE_HIGH"));
            } else {
                double loadRate = 60 + Math.random() * 25;
                double oilTemp = 55 + Math.random() * 20;
                metrics.put("oilTemperature", String.format("%.0f℃", oilTemp));
                metrics.put("oilTemperatureThreshold", "80℃");
                metrics.put("loadRate", String.format("%.0f%%", loadRate));
                metrics.put("coolerStatus", "正常");
                metrics.put("coolerFan1", "运行");
                metrics.put("coolerFan2", "运行");
                metrics.put("coolerFan3", "备用");
                metrics.put("coolerControlMode", "自动");
                metrics.put("environmentTemp", "28℃");
                metrics.put("oilLevel", "正常");
                metrics.put("windingTemp", String.format("%.0f℃", oilTemp - 8));
                metrics.put("alarmPresent", false);
                metrics.put("diagnosticFlags", List.of());
            }
        } else if (deviceType.contains("开关柜")) {
            if (deviceId.equals("KG-10KV-001")) {
                metrics.put("partialDischarge", "异常");
                metrics.put("pdValue", "50pC");
                metrics.put("pdThreshold", "30pC");
                metrics.put("cabinetTemp", "42℃");
                metrics.put("cabinetTempThreshold", "35℃（环境+15K）");
                metrics.put("humidity", "58%");
                metrics.put("busbarTemp", "55℃");
                metrics.put("alarmPresent", true);
                metrics.put("diagnosticFlags", List.of("PARTIAL_DISCHARGE_ABNORMAL", "CABINET_TEMP_HIGH"));
            } else {
                metrics.put("partialDischarge", "正常");
                metrics.put("pdValue", "12pC");
                metrics.put("pdThreshold", "30pC");
                metrics.put("cabinetTemp", "32℃");
                metrics.put("humidity", "52%");
                metrics.put("alarmPresent", false);
            }
        } else if (deviceType.contains("SF6") || deviceType.contains("断路器")) {
            if (deviceId.equals("DL-110KV-001")) {
                metrics.put("SF6Pressure", "0.53MPa");
                metrics.put("SF6PressureAlarm", "0.52MPa");
                metrics.put("SF6PressureLock", "0.50MPa");
                metrics.put("SF6PressureRated", "0.60MPa");
                metrics.put("operationCount", 1250);
                metrics.put("operationCountServiceLimit", 2000);
                metrics.put("status", "在运（SF6偏低需关注）");
                metrics.put("alarmPresent", true);
            } else if (deviceId.equals("DL-35KV-001")) {
                metrics.put("vacuumDegree", "1.2×10⁻²Pa");
                metrics.put("vacuumDegreeLimit", "1.33×10⁻³Pa");
                metrics.put("status", "在运（真空度下降需关注）");
                metrics.put("alarmPresent", true);
            } else {
                metrics.put("status", "正常");
                metrics.put("alarmPresent", false);
            }
        } else if (deviceType.contains("线路") || deviceType.contains("架空")) {
            metrics.put("transmissionStatus", "运行");
            metrics.put("currentLoad", deviceId.equals("XL-110KV-001") ? "68MW" : "22MW");
            metrics.put("ratedCapacity", deviceId.equals("XL-110KV-001") ? "120MW" : "40MW");
            metrics.put("lastPatrolDate", "2026-05-20");
            metrics.put("alarmPresent", false);
        } else if (deviceType.contains("母线")) {
            metrics.put("voltageAB", "10.3kV");
            metrics.put("voltageBC", "10.2kV");
            metrics.put("voltageCA", "10.3kV");
            metrics.put("frequency", "50.02Hz");
            metrics.put("status", "在运");
            metrics.put("alarmPresent", false);
        } else if (deviceType.contains("电容器")) {
            metrics.put("status", "在运");
            metrics.put("reactivePowerOutput", "2850kvar");
            metrics.put("phaseCurrentA", "165A");
            metrics.put("phaseCurrentB", "172A");
            metrics.put("phaseCurrentC", "148A");
            metrics.put("currentUnbalance", "14%");
            metrics.put("alarmPresent", true);
        } else {
            metrics.put("status", "正常");
            metrics.put("alarmPresent", false);
        }

        return metrics;
    }

    private Map<String, Map<String, Object>> loadMapOfMaps(String path) {
        try {
            Resource resource = new PathMatchingResourcePatternResolver().getResource(path);
            try (InputStream is = resource.getInputStream()) {
                return OBJECT_MAPPER.readValue(is, new TypeReference<Map<String, Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            logger.warn("Failed to load {}: {}", path, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, List<Map<String, Object>>> loadMapOfLists(String path) {
        try {
            Resource resource = new PathMatchingResourcePatternResolver().getResource(path);
            try (InputStream is = resource.getInputStream()) {
                return OBJECT_MAPPER.readValue(is, new TypeReference<Map<String, List<Map<String, Object>>>>() {});
            }
        } catch (Exception e) {
            logger.warn("Failed to load {}: {}", path, e.getMessage());
            return Map.of();
        }
    }

    private List<Map<String, Object>> loadList(String path) {
        try {
            Resource resource = new PathMatchingResourcePatternResolver().getResource(path);
            try (InputStream is = resource.getInputStream()) {
                return OBJECT_MAPPER.readValue(is, new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            logger.warn("Failed to load {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> createNotFoundProfile(String deviceId) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("deviceId", deviceId);
        fallback.put("deviceName", "未知设备");
        fallback.put("deviceType", "未知");
        fallback.put("status", "未在档案库中找到");
        return fallback;
    }
}
