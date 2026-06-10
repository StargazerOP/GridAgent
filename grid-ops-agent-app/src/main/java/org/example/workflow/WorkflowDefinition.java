package org.example.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDefinition {

    private String workflowId;
    private String name;
    private String version;
    private String scenarioType;
    private String description;

    @Builder.Default
    private List<String> triggerKeywords = new ArrayList<>();

    @Builder.Default
    private List<String> applicableDeviceTypes = new ArrayList<>();

    @Builder.Default
    private List<WorkflowStepDefinition> steps = new ArrayList<>();

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private String riskLevel = "MEDIUM";

    @Builder.Default
    private String source = "RESOURCE_SEED";

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
