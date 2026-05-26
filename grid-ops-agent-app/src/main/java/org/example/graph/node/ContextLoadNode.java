package org.example.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.example.agent.skill.model.Skill;
import org.example.agent.skill.service.SkillSelector;
import org.example.graph.GraphStateKeys;
import org.example.hook.HookContext;
import org.example.hook.HookEngine;
import org.example.knowledge.KnowledgeOrganizationService;
import org.example.memory.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class ContextLoadNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ContextLoadNode.class);

    private final MemoryService memoryService;
    private final SkillSelector skillSelector;
    private final KnowledgeOrganizationService knowledgeOrganizationService;
    private final HookEngine hookEngine;

    public ContextLoadNode(MemoryService memoryService, SkillSelector skillSelector,
                           KnowledgeOrganizationService knowledgeOrganizationService,
                           HookEngine hookEngine) {
        this.memoryService = memoryService;
        this.skillSelector = skillSelector;
        this.knowledgeOrganizationService = knowledgeOrganizationService;
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
        String workflowContext = knowledgeOrganizationService.buildWorkflowContext(ragInput);

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

        return Map.of(
                "memory_context", memoryContext,
                "skill_context", skillContext,
                GraphStateKeys.WORKFLOW_CONTEXT, workflowContext
        );
    }
}
