package org.example.knowledge;

import org.example.graph.model.PlanStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KnowledgeOrgMatch {

    private String query;
    private WorkflowTemplate template;
    private double score;
    private List<KnowledgeGraphNode> candidateNodes = new ArrayList<>();
    private List<String> recommendedTools = new ArrayList<>();
    private List<Map<String, Object>> executableSteps = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<PlanStep> planSteps = new ArrayList<>();

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public WorkflowTemplate getTemplate() {
        return template;
    }

    public void setTemplate(WorkflowTemplate template) {
        this.template = template;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public List<KnowledgeGraphNode> getCandidateNodes() {
        return candidateNodes;
    }

    public void setCandidateNodes(List<KnowledgeGraphNode> candidateNodes) {
        this.candidateNodes = candidateNodes == null ? new ArrayList<>() : candidateNodes;
    }

    public List<String> getRecommendedTools() {
        return recommendedTools;
    }

    public void setRecommendedTools(List<String> recommendedTools) {
        this.recommendedTools = recommendedTools == null ? new ArrayList<>() : recommendedTools;
    }

    public List<Map<String, Object>> getExecutableSteps() {
        return executableSteps;
    }

    public void setExecutableSteps(List<Map<String, Object>> executableSteps) {
        this.executableSteps = executableSteps == null ? new ArrayList<>() : executableSteps;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
    }

    public List<PlanStep> getPlanSteps() {
        return planSteps;
    }

    public void setPlanSteps(List<PlanStep> planSteps) {
        this.planSteps = planSteps == null ? new ArrayList<>() : planSteps;
    }
}
