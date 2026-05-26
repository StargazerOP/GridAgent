package org.example.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.power.PowerAnalysisOperatorTools;
import org.example.service.MockDataLoader;
import org.example.service.VectorEmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeOrganizationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsMigratedResourcesAndValidatesEdges() {
        KnowledgeOrganizationService service = loadedService();

        Map<String, Object> overview = service.overview();
        assertThat((Integer) overview.get("templates")).isGreaterThan(0);
        assertThat((Integer) overview.get("nodes")).isGreaterThan(0);
        assertThat((Integer) overview.get("edges")).isGreaterThan(0);

        Set<String> ids = service.graph(null, null).get("nodes") instanceof java.util.List<?> nodes
                ? nodes.stream().map(node -> ((KnowledgeGraphNode) node).getId()).collect(Collectors.toSet())
                : Set.of();
        assertThat(ids).isNotEmpty();
        service.relatedEdges(ids).forEach(edge -> {
            assertThat(ids).contains(edge.getSource());
            assertThat(ids).contains(edge.getTarget());
        });
    }

    @Test
    void matchesTemplateAndCreatesExecutablePlan() {
        KnowledgeOrganizationService service = loadedService();

        KnowledgeOrgMatch match = service.match("负荷转供操作校核，分析潮流越限和安规约束");
        Map<String, Object> instantPlan = service.instantPlan("主变TR-110KV-001油温86C超过80C阈值，请诊断");

        assertThat(match.getTemplate()).isNotNull();
        assertThat(match.getCandidateNodes()).isNotEmpty();
        assertThat(match.getPlanSteps()).isNotEmpty();
        assertThat(match.getPlanSteps())
                .allSatisfy(step -> assertThat(step.effectiveToolName()).isNotBlank());
        assertThat(instantPlan).containsKeys("execution_graph", "execution_precheck");
        assertThat((String) instantPlan.get("execution_graph")).isEqualTo("alarm_diagnosis_graph");
    }

    @Test
    void mapsNariToolNamesToGridOpsTools() {
        assertThat(KnowledgeOrganizationService.mapToolName("regulation_retriever")).isEqualTo("searchSafetyRules");
        assertThat(KnowledgeOrganizationService.mapToolName("source_scanner")).isEqualTo("queryInternalDocs");
        assertThat(KnowledgeOrganizationService.mapToolName("graph_query")).isEqualTo("queryKnowledgeGraph");
        assertThat(KnowledgeOrganizationService.mapToolName("operator_power_flow_calculation")).isEqualTo("calculatePowerFlowEstimate");
        assertThat(KnowledgeOrganizationService.mapToolName("slot_filler")).isNull();
    }

    @Test
    void operatorToolsReturnStructuredMockJson() throws Exception {
        KnowledgeOrganizationService service = loadedService();
        MockDataLoader mockDataLoader = loadedMockData();
        PowerAnalysisOperatorTools tools = new PowerAnalysisOperatorTools(service, objectMapper, mockDataLoader);

        JsonNode powerFlow = objectMapper.readTree(tools.calculatePowerFlowEstimate("TR-110KV-001", "负荷转供", ""));
        JsonNode risk = objectMapper.readTree(tools.checkOperationRisk("N-1", "TR-110KV-001", "检修方式"));
        JsonNode graph = objectMapper.readTree(tools.queryKnowledgeGraph("负荷转供", "", 5));

        assertThat(powerFlow.get("mode").asText()).isEqualTo("SEMI_REAL_RULE_ESTIMATE");
        assertThat(risk.get("mode").asText()).isEqualTo("SEMI_REAL_RULE_RISK_CHECK");
        assertThat(graph.get("nodes").isArray()).isTrue();
    }

    private KnowledgeOrganizationService loadedService() {
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);
        when(embeddingService.generateEmbedding(anyString()))
                .thenThrow(new RuntimeException("embedding disabled in unit test"));
        KnowledgeOrganizationService service = new KnowledgeOrganizationService(objectMapper, embeddingService, loadedMockData());
        service.load();
        return service;
    }

    private MockDataLoader loadedMockData() {
        MockDataLoader loader = new MockDataLoader();
        loader.init();
        return loader;
    }
}
