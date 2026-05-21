package org.example.graph.handler;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.agent.diagnosis.DiagnosisAgent;
import org.example.graph.model.PlanStep;
import org.example.graph.model.StepResult;

public class DiagnosisStepHandler implements PlanStepHandler {

    private final DiagnosisAgent diagnosisAgent;

    public DiagnosisStepHandler(DiagnosisAgent diagnosisAgent) {
        this.diagnosisAgent = diagnosisAgent;
    }

    @Override
    public String agentType() {
        return "diagnosis";
    }

    @Override
    public StepResult execute(PlanStep step, OverAllState state) {
        try {
            String input = state.value("cleaned_input").map(Object::toString).orElse("");
            String skillContext = state.value("skill_context").map(Object::toString).orElse("");
            String diagnosisInput = input;
            if (!skillContext.isEmpty()) {
                diagnosisInput += "\n\n--- 业务场景指导 ---\n" + skillContext;
            }
            String result = diagnosisAgent.create().call(diagnosisInput).getText();
            return StepResult.builder().success(true).result(result).build();
        } catch (Exception e) {
            return StepResult.builder().success(false).error(e.getMessage()).result("诊断执行失败: " + e.getMessage()).build();
        }
    }
}
