package org.example.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStepDefinition {

    private String stepId;
    private String name;
    private String purpose;
    private List<String> skillIds;
    private String skillName;
    private String toolName;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;
    private String evidenceRequirement;
    private String failurePolicy;
    private boolean humanConfirmRequired;

    @Builder.Default
    private Map<String, Object> params = new LinkedHashMap<>();
}
