package org.example.hook.impl;

import org.example.hook.AgentHook;
import org.example.hook.HookContext;
import org.example.hook.HookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class PreToolUseHook implements AgentHook {

    private static final Logger logger = LoggerFactory.getLogger(PreToolUseHook.class);

    @Override
    public String getName() {
        return "pre-tool-use-audit";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public HookResult execute(HookContext context) {
        String toolName = context.getAgentName();
        Map<String, Object> params = context.getParams();

        // Record tool call intent for audit trail
        context.setParam("tool_audit_timestamp", Instant.now().toString());
        context.setParam("tool_audit_agent", toolName);

        if (params != null && !params.isEmpty()) {
            logger.debug("PreToolUseHook: audit log — tool={}, session={}, task={}, params={}",
                    toolName, context.getSessionId(), context.getTaskId(),
                    params.keySet());
        }

        return HookResult.proceed();
    }
}
