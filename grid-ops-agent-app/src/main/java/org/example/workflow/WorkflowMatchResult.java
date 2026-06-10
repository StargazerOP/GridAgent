package org.example.workflow;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class WorkflowMatchResult {
    private String query;
    private WorkflowDefinition workflow;
    private double score;
    private List<WorkflowDefinition> candidates;
    private List<SkillAssetView> recommendedSkills;
    private List<Map<String, Object>> evidenceHints;
    private String executionMode;
    private String explanation;
}
