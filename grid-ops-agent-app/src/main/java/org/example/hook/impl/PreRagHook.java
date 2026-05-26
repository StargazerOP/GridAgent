package org.example.hook.impl;

import org.example.hook.AgentHook;
import org.example.hook.HookContext;
import org.example.hook.HookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PreRagHook implements AgentHook {

    private static final Logger logger = LoggerFactory.getLogger(PreRagHook.class);

    @Override
    public String getName() {
        return "pre-rag-query-expansion";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public HookResult execute(HookContext context) {
        String input = context.getInput();
        if (input == null || input.isBlank()) {
            return HookResult.proceed();
        }

        // Expand query with entity aliases and synonyms
        StringBuilder expanded = new StringBuilder(input);

        // Add common power-grid domain synonyms
        if (input.contains("油温") || input.contains("oil")) {
            expanded.append(" 变压器油温 冷却系统 温度监测");
        }
        if (input.contains("局放") || input.contains("放电")) {
            expanded.append(" 局部放电 TEV 超声波 绝缘缺陷");
        }
        if (input.contains("断路器") || input.contains("开关")) {
            expanded.append(" SF6 操作机构 分合闸");
        }
        if (input.contains("线路") || input.contains("杆塔")) {
            expanded.append(" 架空线路 绝缘子 导线");
        }

        context.setParam("expanded_query", expanded.toString());
        logger.debug("PreRagHook: expanded query from '{}' to '{}'", input.substring(0, Math.min(50, input.length())),
                expanded.substring(0, Math.min(80, expanded.length())));
        return HookResult.proceed();
    }
}
