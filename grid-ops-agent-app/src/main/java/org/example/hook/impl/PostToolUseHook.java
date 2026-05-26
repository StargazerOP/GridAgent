package org.example.hook.impl;

import org.example.hook.AgentHook;
import org.example.hook.HookContext;
import org.example.hook.HookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PostToolUseHook implements AgentHook {

    private static final Logger logger = LoggerFactory.getLogger(PostToolUseHook.class);

    @Override
    public String getName() {
        return "post-tool-use-validation";
    }

    @Override
    public int getOrder() {
        return 90;
    }

    @Override
    public HookResult execute(HookContext context) {
        String output = context.getOutput();

        // Check for empty or error tool results
        if (output == null || output.isBlank()) {
            context.setParam("tool_result_status", "EMPTY");
            context.setParam("tool_result_warning", "工具返回空结果");
            logger.warn("PostToolUseHook: empty tool result — tool={}, session={}",
                    context.getAgentName(), context.getSessionId());
            return HookResult.proceed();
        }

        // Check for error patterns in tool output
        String lower = output.toLowerCase();
        if (lower.contains("\"error\"") || lower.contains("error") || lower.contains("失败") || lower.contains("异常")) {
            context.setParam("tool_result_status", "ERROR");
            context.setParam("tool_result_warning", "工具返回包含错误信息");
            logger.warn("PostToolUseHook: tool returned error — tool={}, session={}, preview={}",
                    context.getAgentName(), context.getSessionId(),
                    output.substring(0, Math.min(100, output.length())));
        } else {
            context.setParam("tool_result_status", "OK");
        }

        return HookResult.proceed();
    }
}
