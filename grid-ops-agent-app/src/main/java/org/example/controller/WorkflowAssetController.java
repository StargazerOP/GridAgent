package org.example.controller;

import org.example.workflow.WorkflowAssetService;
import org.example.workflow.WorkflowDefinition;
import org.example.workflow.WorkflowMatchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/workflow-assets")
public class WorkflowAssetController {

    private final WorkflowAssetService workflowAssetService;

    public WorkflowAssetController(WorkflowAssetService workflowAssetService) {
        this.workflowAssetService = workflowAssetService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return workflowAssetService.overview();
    }

    @GetMapping("/workflows")
    public Map<String, Object> workflows(@RequestParam(value = "scenarioType", required = false) String scenarioType,
                                         @RequestParam(value = "editableOnly", defaultValue = "false") boolean editableOnly) {
        var workflows = workflowAssetService.listWorkflows(scenarioType, editableOnly);
        return Map.of("workflows", workflows, "total", workflows.size());
    }

    @GetMapping("/workflows/{workflowId}")
    public ResponseEntity<?> workflow(@PathVariable String workflowId) {
        return workflowAssetService.getWorkflow(workflowId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/workflows")
    public WorkflowDefinition createWorkflow(@RequestBody WorkflowDefinition workflow) {
        return workflowAssetService.saveWorkflow(workflow);
    }

    @PutMapping("/workflows/{workflowId}")
    public WorkflowDefinition updateWorkflow(@PathVariable String workflowId,
                                             @RequestBody WorkflowDefinition workflow) {
        workflow.setWorkflowId(workflowId);
        return workflowAssetService.saveWorkflow(workflow);
    }

    @DeleteMapping("/workflows/{workflowId}")
    public Map<String, Object> deleteWorkflow(@PathVariable String workflowId) {
        boolean deleted = workflowAssetService.deleteWorkflow(workflowId);
        return Map.of("deleted", deleted, "workflowId", workflowId);
    }

    @PostMapping("/match")
    public WorkflowMatchResult match(@RequestBody Map<String, Object> request) {
        return workflowAssetService.match(String.valueOf(request.getOrDefault("query", "")));
    }

    @GetMapping("/skills")
    public Map<String, Object> skills(@RequestParam(value = "type", required = false) String type) {
        var skills = workflowAssetService.listSkills(type);
        return Map.of("skills", skills, "total", skills.size());
    }

    @GetMapping("/workflows/{workflowId}/blueprint")
    public Map<String, Object> blueprint(@PathVariable String workflowId) {
        return workflowAssetService.executionBlueprint(workflowId);
    }
}
