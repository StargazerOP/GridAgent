package org.example.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.power.PowerAnalysisOperatorTools;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(match.getTemplate()).isNotNull();
        assertThat(match.getCandidateNodes()).isNotEmpty();
        assertThat(match.getPlanSteps()).isNotEmpty();
        assertThat(match.getPlanSteps())
                .allSatisfy(step -> assertThat(step.effectiveToolName()).isNotBlank());
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
        PowerAnalysisOperatorTools tools = new PowerAnalysisOperatorTools(service, objectMapper);

        JsonNode powerFlow = objectMapper.readTree(tools.calculatePowerFlowEstimate("A站", "负荷转供", ""));
        JsonNode risk = objectMapper.readTree(tools.checkOperationRisk("N-1", "A站", "检修方式"));
        JsonNode graph = objectMapper.readTree(tools.queryKnowledgeGraph("负荷转供", "", 5));

        assertThat(powerFlow.get("mode").asText()).isEqualTo("MOCK_ESTIMATE");
        assertThat(risk.get("mode").asText()).isEqualTo("MOCK_RISK_CHECK");
        assertThat(graph.get("nodes").isArray()).isTrue();
    }

    private KnowledgeOrganizationService loadedService() {
        KnowledgeOrganizationService service = new KnowledgeOrganizationService(objectMapper);
        service.load();
        return service;
    }
}
