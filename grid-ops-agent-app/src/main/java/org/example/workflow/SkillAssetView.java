package org.example.workflow;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class SkillAssetView {
    private String skillId;
    private String displayName;
    private String technicalName;
    private String type;
    private String description;
    private List<String> recommendedTools;
    private List<String> applicableScenarios;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;
    private boolean mockMode;
    private boolean enabled;
    private int priority;
    private int boundWorkflowCount;
}
