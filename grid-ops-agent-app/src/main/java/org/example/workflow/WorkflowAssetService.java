package org.example.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.example.agent.skill.model.Skill;
import org.example.agent.skill.service.SkillRegistry;
import org.example.knowledge.KnowledgeOrgMatch;
import org.example.knowledge.KnowledgeOrganizationService;
import org.example.knowledge.WorkflowStep;
import org.example.knowledge.WorkflowTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class WorkflowAssetService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowAssetService.class);

    private final KnowledgeOrganizationService knowledgeOrganizationService;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WorkflowDefinition> editableWorkflows = new ConcurrentHashMap<>();

    @Value("${gridops.workflow-assets.file:data/workflows/user-workflows.json}")
    private String workflowAssetsFile = "data/workflows/user-workflows.json";

    public WorkflowAssetService(KnowledgeOrganizationService knowledgeOrganizationService,
                                SkillRegistry skillRegistry) {
        this.knowledgeOrganizationService = knowledgeOrganizationService;
        this.skillRegistry = skillRegistry;
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void loadEditableWorkflows() {
        if (workflowAssetsFile == null || workflowAssetsFile.isBlank()) {
            return;
        }
        Path path = Path.of(workflowAssetsFile);
        if (!Files.exists(path)) {
            return;
        }
        try {
            List<WorkflowDefinition> workflows = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            for (WorkflowDefinition workflow : workflows) {
                if (workflow.getWorkflowId() != null && !workflow.getWorkflowId().isBlank()) {
                    editableWorkflows.put(workflow.getWorkflowId(), workflow);
                }
            }
            logger.info("Loaded {} editable workflow assets from {}", editableWorkflows.size(), path.toAbsolutePath());
        } catch (Exception e) {
            logger.warn("Failed to load editable workflow assets from {}: {}", path.toAbsolutePath(), e.getMessage());
        }
    }

    public Map<String, Object> overview() {
        List<WorkflowDefinition> workflows = listWorkflows(null, false);
        List<SkillAssetView> skills = listSkills(null);
        long enabledWorkflows = workflows.stream().filter(WorkflowDefinition::isEnabled).count();
        long mockSkills = skills.stream().filter(SkillAssetView::isMockMode).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowTotal", workflows.size());
        result.put("workflowEnabled", enabledWorkflows);
        result.put("skillTotal", skills.size());
        result.put("mockSkillTotal", mockSkills);
        result.put("diagnosisCases", List.of(
                "主变油温异常诊断",
                "500kV 母线检修方式 N-1 风险校核",
                "负荷转供操作风险校核"
        ));
        result.put("platformPositioning", "Workflow-Skill-Agent 诊断流程平台");
        result.put("governance", Map.of(
                "workflowVersioning", true,
                "skillReuse", true,
                "traceableExecution", true,
                "humanConfirmation", true
        ));
        return result;
    }

    public List<WorkflowDefinition> listWorkflows(String scenarioType, boolean editableOnly) {
        List<WorkflowDefinition> result = new ArrayList<>();
        if (!editableOnly) {
            for (WorkflowTemplate template : knowledgeOrganizationService.templates()) {
                result.add(fromTemplate(template));
            }
        }
        result.addAll(editableWorkflows.values());
        return result.stream()
                .filter(workflow -> scenarioType == null || scenarioType.isBlank()
                        || scenarioType.equalsIgnoreCase(workflow.getScenarioType()))
                .sorted(Comparator.comparing(WorkflowDefinition::getScenarioType,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(WorkflowDefinition::getName,
                                Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public Optional<WorkflowDefinition> getWorkflow(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Optional.empty();
        }
        if (editableWorkflows.containsKey(workflowId)) {
            return Optional.of(editableWorkflows.get(workflowId));
        }
        return knowledgeOrganizationService.templates().stream()
                .filter(template -> workflowId.equals(template.getTemplateId()))
                .findFirst()
                .map(this::fromTemplate);
    }

    public WorkflowDefinition saveWorkflow(WorkflowDefinition workflow) {
        String id = workflow.getWorkflowId();
        if (id == null || id.isBlank()) {
            id = "workflow_" + System.currentTimeMillis();
            workflow.setWorkflowId(id);
        }
        workflow.setSource("USER_EDITABLE");
        workflow.setUpdatedAt(LocalDateTime.now());
        if (workflow.getCreatedAt() == null) {
            workflow.setCreatedAt(LocalDateTime.now());
        }
        if (workflow.getVersion() == null || workflow.getVersion().isBlank()) {
            workflow.setVersion("1.0");
        }
        editableWorkflows.put(id, workflow);
        persistEditableWorkflows();
        return workflow;
    }

    public boolean deleteWorkflow(String workflowId) {
        boolean deleted = editableWorkflows.remove(workflowId) != null;
        if (deleted) {
            persistEditableWorkflows();
        }
        return deleted;
    }

    public WorkflowMatchResult match(String query) {
        KnowledgeOrgMatch orgMatch = knowledgeOrganizationService.match(query);
        WorkflowDefinition selected = orgMatch.getTemplate() == null
                ? listWorkflows(null, false).stream().findFirst().orElse(null)
                : fromTemplate(orgMatch.getTemplate());

        List<WorkflowDefinition> candidates = listWorkflows(null, false).stream()
                .map(workflow -> Map.entry(workflow, workflowScore(query, workflow)))
                .sorted(Map.Entry.<WorkflowDefinition, Double>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

        if (selected == null && !candidates.isEmpty()) {
            selected = candidates.get(0);
        }

        List<SkillAssetView> skills = selected == null
                ? List.of()
                : safeList(selected.getSteps()).stream()
                .flatMap(step -> safeList(step.getSkillIds()).stream())
                .distinct()
                .map(this::skillViewByToolOrId)
                .flatMap(Optional::stream)
                .toList();

        return WorkflowMatchResult.builder()
                .query(query)
                .workflow(selected)
                .score(orgMatch.getScore())
                .candidates(candidates)
                .recommendedSkills(skills)
                .evidenceHints(List.of(
                        Map.of("type", "workflow", "label", "命中可配置处置流程"),
                        Map.of("type", "skill", "label", "流程步骤已绑定可复用 Skill/工具能力"),
                        Map.of("type", "trace", "label", "执行后将沉淀 Agent Graph 溯源记录")
                ))
                .executionMode("PREVIEW_OR_GRAPH_EXECUTION")
                .explanation("Workflow 负责处置顺序，Skill 负责专业能力，Agent 在执行时根据状态调用、补证、复核和生成报告。")
                .build();
    }

    public List<SkillAssetView> listSkills(String type) {
        List<WorkflowDefinition> workflows = listWorkflows(null, false);
        Map<String, Long> boundCounts = workflows.stream()
                .flatMap(workflow -> safeList(workflow.getSteps()).stream())
                .flatMap(step -> safeList(step.getSkillIds()).stream())
                .collect(Collectors.groupingBy(String::valueOf, Collectors.counting()));
        return skillRegistry.getAllSkills().stream()
                .map(skill -> toSkillAssetView(skill, boundCounts))
                .filter(skill -> type == null || type.isBlank() || type.equalsIgnoreCase(skill.getType()))
                .sorted(Comparator.comparing(SkillAssetView::getType,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(SkillAssetView::getDisplayName,
                                Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public Map<String, Object> executionBlueprint(String workflowId) {
        WorkflowDefinition workflow = getWorkflow(workflowId).orElse(null);
        if (workflow == null) {
            return Map.of("found", false, "workflowId", workflowId);
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(Map.of("id", "router", "label", "任务识别", "agent", "RouterAgent"));
        nodes.add(Map.of("id", "workflow", "label", "流程装载", "agent", "WorkflowContextAgent"));
        nodes.add(Map.of("id", "planner", "label", "计划生成", "agent", "PlannerAgent"));
        for (WorkflowStepDefinition step : safeList(workflow.getSteps())) {
            nodes.add(Map.of(
                    "id", valueOr(step.getStepId(), "step_" + nodes.size()),
                    "label", valueOr(step.getName(), "流程步骤"),
                    "agent", "Tool/Knowledge Agent",
                    "skills", safeList(step.getSkillIds()),
                    "skillName", valueOr(step.getSkillName(), ""),
                    "toolName", valueOr(step.getToolName(), "")
            ));
        }
        nodes.add(Map.of("id", "evidence", "label", "证据复核", "agent", "EvidenceAgent"));
        nodes.add(Map.of("id", "safety", "label", "安全复核", "agent", "SafetyAgent"));
        nodes.add(Map.of("id", "report", "label", "报告生成", "agent", "ReportAgent"));
        return Map.of(
                "found", true,
                "workflow", workflow,
                "nodes", nodes,
                "stateFields", List.of("entities", "workflow_context", "plan_steps", "step_results", "evidence_score", "final_response"),
                "auditFields", List.of("operator", "startedAt", "skillName", "status", "duration", "evidenceRefs", "humanConfirmation")
        );
    }

    private WorkflowDefinition fromTemplate(WorkflowTemplate template) {
        List<WorkflowStepDefinition> steps = new ArrayList<>();
        int index = 1;
        List<WorkflowStep> rawSteps = safeList(template.getWorkflow());
        for (WorkflowStep step : rawSteps) {
            String mappedTool = KnowledgeOrganizationService.mapToolName(step.getTool());
            String skillId = skillIdForTool(mappedTool == null ? step.getTool() : mappedTool, template);
            List<String> skillIds = skillId == null ? List.of() : List.of(skillId);
            Map<String, Object> params = step.getParams() == null ? Map.of() : step.getParams();
            steps.add(WorkflowStepDefinition.builder()
                    .stepId(template.getTemplateId() + "_step_" + index)
                    .name(valueOr(step.getStep(), "步骤 " + index))
                    .purpose(valueOr(step.getStep(), "执行流程步骤"))
                    .skillIds(skillIds)
                    .skillName(skillId == null ? null : skillName(skillId))
                    .toolName(mappedTool)
                    .inputSchema(Map.of("params", params))
                    .outputSchema(Map.of("status", "COMPLETED|FAILED", "summary", "结构化步骤摘要"))
                    .evidenceRequirement(evidenceRequirement(mappedTool))
                    .failurePolicy("RECORD_AND_CONTINUE_WITH_DATA_GAP")
                    .humanConfirmRequired(index == rawSteps.size())
                    .params(params)
                    .build());
            index++;
        }
        if ("diagnosis_transformer_oil_temp_high".equals(template.getTemplateId())) {
            steps.add(Math.min(4, steps.size()), WorkflowStepDefinition.builder()
                    .stepId(template.getTemplateId() + "_step_risk_check")
                    .name("计算主变负载率、油温越限和冷却裕度风险")
                    .purpose("基于油温阈值、负荷率、冷却器状态和历史缺陷形成规则校核结果")
                    .skillIds(List.of("transformer-oil-temperature-risk-check"))
                    .skillName(skillName("transformer-oil-temperature-risk-check"))
                    .toolName("assessTransformerOilTempRisk")
                    .inputSchema(Map.of("params", Map.of("deviceId", "{device_id}", "oilTemperature", "{oil_temperature}", "threshold", "{threshold}")))
                    .outputSchema(Map.of("riskLevel", "LOW|MEDIUM|HIGH|CRITICAL", "findings", "规则命中项", "humanConfirmation", "人工确认项"))
                    .evidenceRequirement("返回油温裕度、负载率、冷却状态和人工确认项，明确数据来自演示状态与规则阈值")
                    .failurePolicy("RECORD_AND_CONTINUE_WITH_DATA_GAP")
                    .humanConfirmRequired(false)
                    .params(Map.of("deviceId", "{device_id}", "oilTemperature", "{oil_temperature}", "threshold", "{threshold}"))
                    .build());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("slots", template.getSlots() == null ? Map.of() : template.getSlots());
        metadata.put("rawExtra", template.getExtra() == null ? Map.of() : template.getExtra());
        return WorkflowDefinition.builder()
                .workflowId(template.getTemplateId())
                .name(template.getName())
                .version("1.0")
                .scenarioType(template.getScene())
                .description(template.getDescription())
                .triggerKeywords(template.getKeywords())
                .applicableDeviceTypes(inferDeviceTypes(template))
                .steps(steps)
                .enabled(true)
                .riskLevel(inferRisk(template))
                .source("RESOURCE_SEED")
                .metadata(metadata)
                .build();
    }

    private SkillAssetView toSkillAssetView(Skill skill, Map<String, Long> boundCounts) {
        List<String> recommendedTools = skill.getRecommendedTools() == null ? List.of() : skill.getRecommendedTools();
        List<String> applicableScenarios = skill.getApplicableScenarios() == null ? List.of() : skill.getApplicableScenarios();
        return SkillAssetView.builder()
                .skillId(skill.getSkillId())
                .displayName(skill.getName())
                .technicalName(skill.getSkillId())
                .type(skill.getCategory())
                .description(skill.getDescription())
                .recommendedTools(recommendedTools)
                .applicableScenarios(applicableScenarios)
                .inputSchema(Map.of("input", "由 Workflow Step 或 Graph State 提供"))
                .outputSchema(skill.getOutputSchema() == null ? Map.of("summary", "结构化能力输出") : skill.getOutputSchema())
                .mockMode(isMockSkill(skill))
                .enabled(skill.isEnabled())
                .priority(skill.getPriority())
                .boundWorkflowCount(boundCounts.getOrDefault(skill.getSkillId(), 0L).intValue()
                        + recommendedTools.stream().mapToInt(tool -> boundCounts.getOrDefault(tool, 0L).intValue()).sum())
                .build();
    }

    private Optional<SkillAssetView> skillViewByToolOrId(String skillOrTool) {
        Optional<Skill> byId = skillRegistry.getSkill(skillOrTool);
        if (byId.isPresent()) {
            return Optional.of(toSkillAssetView(byId.get(), Map.of()));
        }
        return skillRegistry.getAllSkills().stream()
                .filter(skill -> skill.getRecommendedTools() != null && skill.getRecommendedTools().contains(skillOrTool))
                .findFirst()
                .map(skill -> toSkillAssetView(skill, Map.of()));
    }

    private String skillIdForTool(String toolName, WorkflowTemplate template) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String text = normalize((template == null ? "" : template.getName() + " " + template.getDescription()) + " " + toolName);
        if ("getDeviceProfile".equals(toolName)) {
            return "device-profile-query";
        }
        if ("getDeviceStatus".equals(toolName)) {
            return "device-status-query";
        }
        if ("getAlarmHistory".equals(toolName)) {
            return "alarm-history-retrieval";
        }
        if ("getDeviceLogs".equals(toolName)) {
            return "operation-log-retrieval";
        }
        if ("getDefectTickets".equals(toolName)) {
            return "defect-ticket-check";
        }
        if ("queryInternalDocs".equals(toolName)) {
            return "rag-evidence-retrieval";
        }
        if ("searchSafetyRules".equals(toolName)) {
            return "safety-regulation-qa";
        }
        if ("queryKnowledgeGraph".equals(toolName) || "analyzeTopology".equals(toolName)) {
            return "knowledge-graph-topology-analysis";
        }
        if ("calculatePowerFlowEstimate".equals(toolName)
                || "checkOperationRisk".equals(toolName)
                || "generateFaultScenario".equals(toolName)) {
            return text.contains("油温") ? "transformer-oil-temperature-risk-check" : "operation-risk-simulation";
        }
        if ("llm".equals(toolName)) {
            return "diagnosis-report-generation";
        }
        return null;
    }

    private String skillName(String skillId) {
        return skillRegistry.getSkill(skillId).map(Skill::getName).orElse(skillId);
    }

    private boolean isMockSkill(Skill skill) {
        Set<String> tools = new LinkedHashSet<>(skill.getRecommendedTools() == null ? List.of() : skill.getRecommendedTools());
        return tools.stream().anyMatch(tool -> tool.toLowerCase(Locale.ROOT).contains("mock")
                || tool.equals("checkOperationRisk")
                || tool.equals("assessTransformerOilTempRisk")
                || tool.equals("calculatePowerFlowEstimate")
                || tool.equals("generateFaultScenario"));
    }

    private double workflowScore(String query, WorkflowDefinition workflow) {
        String q = normalize(query);
        if (q.isBlank()) {
            return 0;
        }
        double score = 0;
        String text = normalize(workflow.getName() + " " + workflow.getDescription() + " " + String.join(" ", safeList(workflow.getTriggerKeywords())));
        for (String token : q.split("\\s+")) {
            if (!token.isBlank() && text.contains(token)) {
                score += 1.0;
            }
        }
        for (String keyword : safeList(workflow.getTriggerKeywords())) {
            if (!keyword.isBlank() && q.contains(normalize(keyword))) {
                score += 2.5;
            }
        }
        return score;
    }

    private List<String> inferDeviceTypes(WorkflowTemplate template) {
        String text = normalize(template.getName() + " " + template.getDescription() + " " + String.join(" ", template.getKeywords()));
        List<String> types = new ArrayList<>();
        if (text.contains("主变") || text.contains("变压器")) {
            types.add("主变压器");
        }
        if (text.contains("母线")) {
            types.add("母线");
        }
        if (text.contains("线路")) {
            types.add("线路");
        }
        if (text.contains("负荷") || text.contains("转供")) {
            types.add("负荷/馈线");
        }
        return types;
    }

    private String inferRisk(WorkflowTemplate template) {
        String text = normalize(template.getName() + " " + template.getDescription() + " " + String.join(" ", template.getKeywords()));
        if (text.contains("n-1") || text.contains("全停") || text.contains("风险")) {
            return "HIGH";
        }
        if (text.contains("校核") || text.contains("操作")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String evidenceRequirement(String tool) {
        if (tool == null || tool.isBlank()) {
            return "由实体抽取、人工确认或报告节点内部完成";
        }
        return switch (tool) {
            case "queryInternalDocs", "searchSafetyRules" -> "至少返回一条规程、案例或文档片段作为文本依据";
            case "queryKnowledgeGraph", "analyzeTopology" -> "返回相关节点、关系或空结果说明";
            case "checkOperationRisk", "calculatePowerFlowEstimate", "generateFaultScenario" -> "明确标注模拟/估算模式和人工复核要求";
            default -> "返回结构化状态、摘要、数据来源和失败原因";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[，。；：、,.;:()（）\\[\\]{}]", " ").trim();
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    void setWorkflowAssetsFileForTest(String workflowAssetsFile) {
        this.workflowAssetsFile = workflowAssetsFile;
    }

    private void persistEditableWorkflows() {
        if (workflowAssetsFile == null || workflowAssetsFile.isBlank()) {
            return;
        }
        Path path = Path.of(workflowAssetsFile);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<WorkflowDefinition> workflows = editableWorkflows.values().stream()
                    .sorted(Comparator.comparing(WorkflowDefinition::getUpdatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), workflows);
        } catch (IOException e) {
            logger.warn("Failed to persist editable workflow assets to {}: {}", path.toAbsolutePath(), e.getMessage());
        }
    }
}
