package org.example.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.skill.model.Skill;
import org.example.agent.skill.service.SkillSelector;
import org.example.graph.GraphStateKeys;
import org.example.hook.HookContext;
import org.example.hook.HookEngine;
import org.example.knowledge.KnowledgeOrganizationService;
import org.example.memory.MemoryService;
import org.example.workflow.SkillAssetView;
import org.example.workflow.WorkflowAssetService;
import org.example.workflow.WorkflowDefinition;
import org.example.workflow.WorkflowMatchResult;
import org.example.workflow.WorkflowStepDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ContextLoadNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ContextLoadNode.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MemoryService memoryService;
    private final SkillSelector skillSelector;
    private final KnowledgeOrganizationService knowledgeOrganizationService;
    private final WorkflowAssetService workflowAssetService;
    private final HookEngine hookEngine;

    public ContextLoadNode(MemoryService memoryService, SkillSelector skillSelector,
                           KnowledgeOrganizationService knowledgeOrganizationService,
                           WorkflowAssetService workflowAssetService,
                           HookEngine hookEngine) {
        this.memoryService = memoryService;
        this.skillSelector = skillSelector;
        this.knowledgeOrganizationService = knowledgeOrganizationService;
        this.workflowAssetService = workflowAssetService;
        this.hookEngine = hookEngine;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("session_id").map(Object::toString).orElse("default");
        String taskId = state.value("task_id").map(Object::toString).orElse("TASK-" + System.currentTimeMillis());
        String userId = state.value("user_id").map(Object::toString).orElse("default");
        String intent = state.value("intent").map(Object::toString).orElse("");

        logger.info("ContextLoadNode: 加载上下文, sessionId={}, intent={}", sessionId, intent);

        String memoryContext = memoryService.buildContextForAgent(sessionId, taskId, userId);
        String input = state.value(GraphStateKeys.CLEANED_INPUT)
                .or(() -> state.value(GraphStateKeys.INPUT))
                .map(Object::toString)
                .orElse("");

        // PRE_RAG hook: query expansion
        HookContext preRagCtx = HookContext.builder()
                .sessionId(sessionId).taskId(taskId).agentName("context_load")
                .input(input).params(Map.of("intent", intent)).build();
        hookEngine.executeHooks("PRE_RAG", preRagCtx);

        Object expandedQuery = preRagCtx.getParam("expanded_query");
        String ragInput = expandedQuery != null ? expandedQuery.toString() : input;
        String legacyWorkflowContext = knowledgeOrganizationService.buildWorkflowContext(ragInput);
        String workflowAssetContext = buildWorkflowAssetContext(ragInput, legacyWorkflowContext);
        String workflowContext = workflowAssetContext.isBlank() ? legacyWorkflowContext : workflowAssetContext;

        // POST_RAG hook: quality filter
        HookContext postRagCtx = HookContext.builder()
                .sessionId(sessionId).taskId(taskId).agentName("context_load")
                .output(workflowContext).params(Map.of("intent", intent)).build();
        hookEngine.executeHooks("POST_RAG", postRagCtx);

        String skillContext = "";
        if (!intent.isEmpty()) {
            Skill skill = skillSelector.selectByIntent(intent).orElse(null);
            if (skill != null && skill.getPromptTemplate() != null) {
                skillContext = skill.getPromptTemplate();
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memory_context", memoryContext == null ? "" : memoryContext);
        result.put("skill_context", skillContext == null ? "" : skillContext);
        result.put(GraphStateKeys.WORKFLOW_CONTEXT, workflowContext == null ? "" : workflowContext);
        result.put(GraphStateKeys.WORKFLOW_ASSET_CONTEXT, workflowAssetContext == null ? "" : workflowAssetContext);
        return result;
    }

    private String buildWorkflowAssetContext(String query, String legacyWorkflowContext) {
        try {
            WorkflowMatchResult match = workflowAssetService.match(query);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("asset_model", "WORKFLOW_SKILL_AGENT");
            context.put("role_definition", Map.of(
                    "workflow", "Defines governed diagnosis or operation procedure order.",
                    "skill", "Defines reusable domain capability that can call tools, RAG, graph or operators.",
                    "agent", "Executes the workflow dynamically, fills missing evidence, validates risk and generates report."
            ));
            context.put("query", query);
            context.put("match_score", match.getScore());
            context.put("execution_mode", match.getExecutionMode());
            context.put("matched_workflow", workflowSummary(match.getWorkflow()));
            context.put("workflow_steps", workflowSteps(match.getWorkflow()));
            context.put("recommended_skills", skillSummaries(match.getRecommendedSkills()));
            List<WorkflowDefinition> candidates = match.getCandidates() == null ? List.of() : match.getCandidates();
            context.put("candidate_workflows", candidates.stream()
                    .limit(5)
                    .map(this::workflowSummary)
                    .toList());
            context.put("evidence_hints", match.getEvidenceHints());
            context.put("planner_rules", List.of(
                    "Prefer matched_workflow.workflow_steps when generating executable PlanStep.",
                    "Use only registered GridOps tool names or internal ANALYSIS steps.",
                    "If a Skill is marked mockMode, keep output as estimate evidence and require human review.",
                    "Do not add unregistered external systems as required tool calls."
            ));
            parseLegacyContext(legacyWorkflowContext).forEach((key, value) -> context.put("legacy_" + key, value));
            return OBJECT_MAPPER.writeValueAsString(context);
        } catch (Exception e) {
            logger.warn("ContextLoadNode: failed to build workflow asset context: {}", e.getMessage());
            return legacyWorkflowContext == null ? "" : legacyWorkflowContext;
        }
    }

    private Map<String, Object> workflowSummary(WorkflowDefinition workflow) {
        if (workflow == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("workflow_id", workflow.getWorkflowId());
        summary.put("name", workflow.getName());
        summary.put("version", workflow.getVersion());
        summary.put("scenario_type", workflow.getScenarioType());
        summary.put("risk_level", workflow.getRiskLevel());
        summary.put("source", workflow.getSource());
        summary.put("enabled", workflow.isEnabled());
        summary.put("trigger_keywords", workflow.getTriggerKeywords());
        summary.put("applicable_device_types", workflow.getApplicableDeviceTypes());
        return summary;
    }

    private List<Map<String, Object>> workflowSteps(WorkflowDefinition workflow) {
        if (workflow == null || workflow.getSteps() == null) {
            return List.of();
        }
        int[] index = {1};
        return workflow.getSteps().stream().map(step -> stepSummary(step, index[0]++)).toList();
    }

    private Map<String, Object> stepSummary(WorkflowStepDefinition step, int index) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("step_no", index);
        summary.put("step_id", step.getStepId());
        summary.put("name", step.getName());
        summary.put("purpose", step.getPurpose());
        summary.put("skill_ids", step.getSkillIds() == null ? List.of() : step.getSkillIds());
        summary.put("skill_name", step.getSkillName());
        summary.put("tool_name", step.getToolName());
        summary.put("params", step.getParams() == null ? Map.of() : step.getParams());
        summary.put("evidence_requirement", step.getEvidenceRequirement());
        summary.put("failure_policy", step.getFailurePolicy());
        summary.put("human_confirm_required", step.isHumanConfirmRequired());
        return summary;
    }

    private List<Map<String, Object>> skillSummaries(List<SkillAssetView> skills) {
        if (skills == null) {
            return List.of();
        }
        return skills.stream().map(skill -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("skill_id", skill.getSkillId());
            summary.put("display_name", skill.getDisplayName());
            summary.put("type", skill.getType());
            summary.put("recommended_tools", skill.getRecommendedTools());
            summary.put("mock_mode", skill.isMockMode());
            summary.put("bound_workflow_count", skill.getBoundWorkflowCount());
            return summary;
        }).toList();
    }

    private Map<String, Object> parseLegacyContext(String legacyWorkflowContext) {
        if (legacyWorkflowContext == null || legacyWorkflowContext.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(legacyWorkflowContext, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("raw_workflow_context", legacyWorkflowContext);
        }
    }
}
