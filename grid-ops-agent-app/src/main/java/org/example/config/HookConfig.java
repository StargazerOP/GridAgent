package org.example.config;

import org.example.hook.HookEngine;
import org.example.hook.impl.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class HookConfig {

    @Autowired
    private HookEngine hookEngine;

    @Autowired
    private PostDiagnosisHook postDiagnosisHook;

    @Autowired
    private AuditHook auditHook;

    @Autowired
    private HumanApprovalHook humanApprovalHook;

    @Autowired
    private SafetyCheckHook safetyCheckHook;

    @Autowired
    private PreRagHook preRagHook;

    @Autowired
    private PostRagHook postRagHook;

    @Autowired
    private PreToolUseHook preToolUseHook;

    @Autowired
    private PostToolUseHook postToolUseHook;

    @PostConstruct
    public void registerHooks() {
        // POST_DIAGNOSIS hooks (existing)
        hookEngine.registerHook("POST_DIAGNOSIS", auditHook);
        hookEngine.registerHook("POST_DIAGNOSIS", postDiagnosisHook);
        hookEngine.registerHook("POST_DIAGNOSIS", safetyCheckHook);
        hookEngine.registerHook("POST_DIAGNOSIS", humanApprovalHook);

        // RAG hooks (newly activated)
        hookEngine.registerHook("PRE_RAG", preRagHook);
        hookEngine.registerHook("POST_RAG", postRagHook);

        // Tool-use hooks (newly activated)
        hookEngine.registerHook("PRE_TOOL_USE", preToolUseHook);
        hookEngine.registerHook("POST_TOOL_USE", postToolUseHook);
    }
}
