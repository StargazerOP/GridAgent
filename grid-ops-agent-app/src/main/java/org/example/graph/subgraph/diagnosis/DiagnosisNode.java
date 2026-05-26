package org.example.graph.subgraph.diagnosis;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.example.agent.diagnosis.DiagnosisAgent;
import org.example.graph.GraphStateKeys;
import org.example.graph.validation.DiagnosisValidator;
import org.example.graph.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class DiagnosisNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosisNode.class);

    private final DiagnosisAgent diagnosisAgent;
    private final DiagnosisValidator diagnosisValidator;

    public DiagnosisNode(DiagnosisAgent diagnosisAgent, DiagnosisValidator diagnosisValidator) {
        this.diagnosisAgent = diagnosisAgent;
        this.diagnosisValidator = diagnosisValidator;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String input = state.value(GraphStateKeys.CLEANED_INPUT).map(Object::toString).orElse("");
        String evidence = state.value(GraphStateKeys.EVIDENCE).map(Object::toString)
                .orElse(state.value(GraphStateKeys.EXECUTION_RESULT).map(Object::toString).orElse(""));
        String skillContext = state.value("skill_context").map(Object::toString).orElse("");
        String ragContext = state.value("rag_results").map(Object::toString).orElse("");
        String workflowContext = state.value(GraphStateKeys.WORKFLOW_CONTEXT).map(Object::toString).orElse("");
        Object evidenceScore = state.value(GraphStateKeys.EVIDENCE_SCORE).orElse(null);
        Object evidenceCoverage = state.value(GraphStateKeys.EVIDENCE_COVERAGE).orElse(null);

        logger.info("DiagnosisNode: generating diagnosis, evidenceScore={}", evidenceScore);

        StringBuilder diagnosisInput = new StringBuilder();
        diagnosisInput.append(input).append("\n\n--- Evidence ---\n").append(evidence);
        if (evidenceScore != null) {
            diagnosisInput.append("\n\nEvidence score: ").append(evidenceScore);
        }
        if (evidenceCoverage != null) {
            diagnosisInput.append("\nEvidence coverage: ").append(evidenceCoverage);
        }
        if (!skillContext.isBlank()) {
            diagnosisInput.append("\n\n--- Business Context ---\n").append(skillContext);
        }
        if (!ragContext.isBlank()) {
            diagnosisInput.append("\n\n--- Retrieved Knowledge (RAG) ---\n").append(truncate(ragContext, 2400));
        }
        if (!workflowContext.isBlank()) {
            diagnosisInput.append("\n\n--- Workflow Template Guidance ---\n").append(truncate(workflowContext, 2000));
        }
        diagnosisInput.append("""

                Please produce a structured diagnosis report that includes:
                - Alarm summary
                - Key evidence
                - Possible causes
                - Risk level
                - Handling suggestions
                - Safety notes
                - Uncertainty statement

                Do not reproduce raw tool logs, JSON payloads, Markdown step blocks, or stack traces.
                Summarize tool evidence in concise operational language.
                """);

        String result = diagnosisAgent.generateWithoutTools(diagnosisInput.toString());
        ValidationResult validation = diagnosisValidator.validate(result);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put(GraphStateKeys.DIAGNOSIS_RESULT, result);
        output.put(GraphStateKeys.EXECUTION_RESULT, result);
        if (!validation.isValid()) {
            output.put(GraphStateKeys.VALIDATION_WARNINGS, validation.getWarnings());
        }
        return output;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
