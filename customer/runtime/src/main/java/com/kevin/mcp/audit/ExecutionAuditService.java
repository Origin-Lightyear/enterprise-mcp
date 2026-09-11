package com.kevin.mcp.audit;

import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 记录结构化执行计划摘要，避免在客户 Runtime 维护独立审计文件。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Service
public class ExecutionAuditService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionAuditService.class);
    /**
     * 记录计划执行结果摘要。
     *
     * @param plan 执行计划
     * @param result 执行结果
     */
    public void record(JsonExecutionPlan plan, JsonExecutionResult result) {
        if (plan == null || result == null) {
            return;
        }
        log.info("Local plan audit: planId={}, steps={}, success={}",
                plan.planId(), result.executedSteps().size(), result.success());
    }
}
