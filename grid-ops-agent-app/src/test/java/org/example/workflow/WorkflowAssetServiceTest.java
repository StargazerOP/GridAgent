package org.example.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.skill.service.SkillRegistry;
import org.example.knowledge.KnowledgeOrganizationService;
import org.example.service.MockDataLoader;
import org.example.service.VectorEmbeddingService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowAssetServiceTest {

    @Test
    void exposesWorkflowAndSkillAssetsFromMigratedKnowledgeOrganization() {
        WorkflowAssetService service = workflowAssetService();

        Map<String, Object> overview = service.overview();
        assertThat((Integer) overview.get("workflowTotal")).isGreaterThan(0);
        assertThat((Integer) overview.get("skillTotal")).isGreaterThan(0);

        List<WorkflowDefinition> workflows = service.listWorkflows(null, false);
        assertThat(workflows).isNotEmpty();
        assertThat(workflows.get(0).getSteps()).isNotEmpty();

        List<SkillAssetView> skills = service.listSkills(null);
        assertThat(skills).isNotEmpty();
        assertThat(skills).allSatisfy(skill -> assertThat(skill.getSkillId()).isNotBlank());
    }

    @Test
    void matchesNaturalLanguageTaskAndReturnsWorkflowSkillContext() {
        WorkflowAssetService service = workflowAssetService();

        WorkflowMatchResult match = service.match("主变TR-110KV-001油温86C超过80C阈值，请诊断");

        assertThat(match.getWorkflow()).isNotNull();
        assertThat(match.getWorkflow().getSteps()).isNotEmpty();
        assertThat(match.getWorkflow().getSteps())
                .anySatisfy(step -> {
                    assertThat(step.getSkillIds()).contains("transformer-oil-temperature-risk-check");
                    assertThat(step.getToolName()).isEqualTo("assessTransformerOilTempRisk");
                });
        assertThat(match.getRecommendedSkills()).isNotNull();
        assertThat(match.getRecommendedSkills())
                .extracting(SkillAssetView::getSkillId)
                .contains("device-status-query", "alarm-history-retrieval", "transformer-oil-temperature-risk-check");
        assertThat(match.getCandidates()).isNotEmpty();
        assertThat(match.getExplanation()).contains("Workflow");
    }

    @Test
    void createsExecutionBlueprintForWorkflow() {
        WorkflowAssetService service = workflowAssetService();
        WorkflowDefinition workflow = service.listWorkflows(null, false).get(0);

        Map<String, Object> blueprint = service.executionBlueprint(workflow.getWorkflowId());

        assertThat(blueprint.get("found")).isEqualTo(true);
        assertThat((List<?>) blueprint.get("nodes")).isNotEmpty();
        assertThat((List<?>) blueprint.get("stateFields"))
                .extracting(String::valueOf)
                .contains("workflow_context", "plan_steps");
    }

    @Test
    void savesEditableWorkflowDraft() throws Exception {
        WorkflowAssetService service = workflowAssetService();
        Path tempFile = Files.createTempFile("gridops-workflows", ".json");
        service.setWorkflowAssetsFileForTest(tempFile.toString());
        WorkflowDefinition draft = WorkflowDefinition.builder()
                .name("测试流程草案")
                .scenarioType("diagnosis")
                .triggerKeywords(List.of("测试"))
                .steps(List.of())
                .build();

        WorkflowDefinition saved = service.saveWorkflow(draft);

        assertThat(saved.getWorkflowId()).isNotBlank();
        assertThat(saved.getSource()).isEqualTo("USER_EDITABLE");
        assertThat(service.getWorkflow(saved.getWorkflowId())).isPresent();
        assertThat(Files.readString(tempFile)).contains("测试流程草案");
    }

    private WorkflowAssetService workflowAssetService() {
        SkillRegistry skillRegistry = new SkillRegistry();
        skillRegistry.init();
        return new WorkflowAssetService(knowledgeOrganizationService(), skillRegistry);
    }

    private KnowledgeOrganizationService knowledgeOrganizationService() {
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);
        when(embeddingService.generateEmbedding(anyString()))
                .thenThrow(new RuntimeException("embedding disabled in unit test"));
        MockDataLoader mockDataLoader = new MockDataLoader();
        mockDataLoader.init();
        KnowledgeOrganizationService service = new KnowledgeOrganizationService(new ObjectMapper(), embeddingService, mockDataLoader);
        service.load();
        return service;
    }
}
