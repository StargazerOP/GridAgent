package org.example.config;

import io.modelcontextprotocol.client.McpSyncClient;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.power.PowerAnalysisOperatorTools;
import org.example.agent.tool.power.PowerAlarmHistoryTools;
import org.example.agent.tool.power.PowerDefectTicketTools;
import org.example.agent.tool.power.PowerDeviceLogsTools;
import org.example.agent.tool.power.PowerDeviceProfileTools;
import org.example.agent.tool.power.PowerDeviceStatusTools;
import org.example.agent.tool.power.PowerSafetyRulesTools;
import org.example.tool.ToolRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class ToolConfig {

    private static final Logger logger = LoggerFactory.getLogger(ToolConfig.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private PowerSafetyRulesTools powerSafetyRulesTools;

    @Autowired
    private PowerAnalysisOperatorTools powerAnalysisOperatorTools;

    @Autowired
    private PowerDeviceStatusTools powerDeviceStatusTools;

    @Autowired
    private PowerAlarmHistoryTools powerAlarmHistoryTools;

    @Autowired
    private PowerDeviceLogsTools powerDeviceLogsTools;

    @Autowired
    private PowerDefectTicketTools powerDefectTicketTools;

    @Autowired
    private PowerDeviceProfileTools powerDeviceProfileTools;

    @Autowired
    private ToolRegistryService toolRegistryService;

    @Bean
    @Primary
    public ToolCallbackProvider toolCallbackProvider(ObjectProvider<List<McpSyncClient>> mcpSyncClientsProvider) {
        ToolCallbackProvider localTools = MethodToolCallbackProvider.builder()
                .toolObjects(
                        dateTimeTools,
                        internalDocsTools,
                        powerSafetyRulesTools,
                        powerAnalysisOperatorTools,
                        powerDeviceStatusTools,
                        powerAlarmHistoryTools,
                        powerDeviceLogsTools,
                        powerDefectTicketTools,
                        powerDeviceProfileTools
                )
                .build();

        Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
        for (ToolCallback callback : localTools.getToolCallbacks()) {
            callbacks.put(callback.getToolDefinition().name(), callback);
        }
        List<McpSyncClient> mcpSyncClients;
        try {
            mcpSyncClients = mcpSyncClientsProvider.getIfAvailable(List::of);
        } catch (Exception e) {
            logger.warn("MCP tools are unavailable; continuing with local tools only. error={}", e.getMessage());
            mcpSyncClients = List.of();
        }
        if (!mcpSyncClients.isEmpty()) {
            try {
                ToolCallbackProvider mcpTools = new SyncMcpToolCallbackProvider(mcpSyncClients);
                for (ToolCallback callback : mcpTools.getToolCallbacks()) {
                    String name = callback.getToolDefinition().name();
                    if (callbacks.containsKey(name)) {
                        logger.info("Skip duplicated MCP tool '{}'; local tool has priority", name);
                        continue;
                    }
                    callbacks.put(name, callback);
                }
            } catch (Exception e) {
                logger.warn("Failed to register MCP tools; continuing with local tools only. error={}", e.getMessage());
            }
        }

        List<ToolCallback> deduplicated = new ArrayList<>(callbacks.values());
        logger.info("Registered {} unique tool callbacks", deduplicated.size());
        return () -> deduplicated.toArray(ToolCallback[]::new);
    }

    @PostConstruct
    public void registerToolsToRegistry() {
        toolRegistryService.registerTool("getCurrentDateTime", "getCurrentDateTime",
                "获取当前日期时间", null, null,
                List.of("time", "date"), "QUERY", "LOW", "SYSTEM");

        toolRegistryService.registerTool("queryInternalDocs", "queryInternalDocs",
                "查询内部知识文档", null, null,
                List.of("knowledge", "docs", "rag"), "QUERY", "LOW", "RAG");

        toolRegistryService.registerTool("getDeviceStatus", "getDeviceStatus",
                "通过 MCP Server 查询设备实时运行状态", null, null,
                List.of("device", "status", "monitoring", "mcp"), "QUERY", "LOW", "SCADA");

        toolRegistryService.registerTool("getAlarmHistory", "getAlarmHistory",
                "通过 MCP Server 查询历史告警记录", null, null,
                List.of("alarm", "history", "alert", "mcp"), "QUERY", "LOW", "SCADA");

        toolRegistryService.registerTool("getDeviceLogs", "getDeviceLogs",
                "通过 MCP Server 查询设备运行日志", null, null,
                List.of("log", "device", "analysis", "mcp"), "QUERY", "LOW", "DMS");

        toolRegistryService.registerTool("getDefectTickets", "getDefectTickets",
                "通过 MCP Server 查询缺陷工单", null, null,
                List.of("ticket", "defect", "work-order", "mcp"), "QUERY", "LOW", "PMS");

        toolRegistryService.registerTool("searchSafetyRules", "searchSafetyRules",
                "检索安规条款", null, null,
                List.of("safety", "regulation", "compliance"), "QUERY", "LOW", "REGULATION");

        toolRegistryService.registerTool("getDeviceProfile", "getDeviceProfile",
                "通过 MCP Server 查询设备台账信息", null, null,
                List.of("device", "profile", "asset", "mcp"), "QUERY", "LOW", "PMS");

        toolRegistryService.registerTool("queryKnowledgeGraph", "queryKnowledgeGraph",
                "Query migrated workflow, tool and knowledge-entity graph resources", null, null,
                List.of("knowledge-org", "graph", "workflow"), "QUERY", "LOW", "GRIDOPS");

        toolRegistryService.registerTool("analyzeTopology", "analyzeTopology",
                "Analyze topology relations from the migrated knowledge graph", null, null,
                List.of("topology", "graph", "analysis"), "QUERY", "LOW", "GRIDOPS");

        toolRegistryService.registerTool("calculatePowerFlowEstimate", "calculatePowerFlowEstimate",
                "Return a mock/estimated power-flow result marked as MOCK_ESTIMATE", null, null,
                List.of("power-flow", "mock", "estimate"), "QUERY", "MEDIUM", "GRIDOPS");

        toolRegistryService.registerTool("checkOperationRisk", "checkOperationRisk",
                "Run a mock operation-risk check from templates, graph context and engineering rules", null, null,
                List.of("risk", "operation", "mock"), "QUERY", "MEDIUM", "GRIDOPS");

        toolRegistryService.registerTool("assessTransformerOilTempRisk", "assessTransformerOilTempRisk",
                "基于演示状态量与规则阈值校核主变油温、负载率和冷却裕度风险", null, null,
                List.of("transformer", "oil-temperature", "mechanism-check", "risk"), "QUERY", "MEDIUM", "GRIDOPS");

        toolRegistryService.registerTool("generateFaultScenario", "generateFaultScenario",
                "Generate mock fault scenarios and handling focus points", null, null,
                List.of("fault", "scenario", "mock"), "QUERY", "MEDIUM", "GRIDOPS");
    }
}
