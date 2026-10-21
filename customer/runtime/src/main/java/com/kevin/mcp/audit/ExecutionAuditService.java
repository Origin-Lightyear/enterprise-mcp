package com.kevin.mcp.audit;

import com.kevin.mcp.clarification.ClarificationRequest;
import com.kevin.mcp.clarification.ClarificationResolution;
import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionResult;
import com.kevin.mcp.util.GsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在客户服务器持久化不含原始数据的权限与执行审计摘要。
 *
 * <p>审计写入是执行前置条件；本地文件无法持久化时必须失败关闭。每条记录单行写入并强制刷盘，
 * 使进程异常退出后仍可追溯最后一次权限决策。</p>
 *
 * @author Kevin
 * @date 2026-09-17
 */
@Service
public class ExecutionAuditService {

    private final Path auditPath;
    private final Object writeLock = new Object();

    /**
     * 创建本地审计服务。
     *
     * @param auditPath 审计 JSONL 文件路径
     */
    public ExecutionAuditService(
            @Value("${enterprise.audit.path:./data/execution-audit.jsonl}") String auditPath
    ) {
        this.auditPath = Path.of(auditPath).toAbsolutePath().normalize();
    }

    /**
     * 在业务执行前持久化权限决策摘要。
     *
     * @param audit 安全审计字段，不得包含密钥、原始规则、SQL 或业务结果
     */
    public void recordDecision(Map<String, Object> audit) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", "PERMISSION_DECISION");
        record.put("recordedAt", Instant.now().toString());
        if (audit != null) {
            record.putAll(audit);
        }
        append(record);
    }

    /**
     * 在计划结束后持久化不含结果正文的执行摘要。
     *
     * @param plan 执行计划
     * @param result 执行结果
     */
    public void record(JsonExecutionPlan plan, JsonExecutionResult result) {
        if (plan == null || result == null) {
            throw new IllegalArgumentException("Execution audit plan and result are required");
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", "PLAN_COMPLETED");
        record.put("recordedAt", Instant.now().toString());
        record.put("planId", plan.planId());
        record.put("success", result.success());
        record.put("executedStepCount", result.executedSteps().size());
        append(record);
    }

    /**
     * 持久化不含自由文本原文的用户澄清审计。
     *
     * @param tenantId 当前租户标识
     * @param employeeId 当前员工标识
     * @param request 澄清请求及其 MDL 绑定
     * @param resolution 用户操作结果
     */
    public void recordClarification(String tenantId, Long employeeId, ClarificationRequest request,
                                    ClarificationResolution resolution) {
        if (request == null || resolution == null) {
            throw new IllegalArgumentException("Clarification audit request and resolution are required");
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", "USER_CLARIFICATION");
        record.put("recordedAt", Instant.now().toString());
        record.put("tenantId", tenantId);
        record.put("employeeId", employeeId);
        record.put("clarificationId", request.id());
        record.put("action", resolution.action().name());
        record.put("reason", request.reason());
        record.put("sourceKey", request.sourceKey());
        record.put("mdlDigest", request.mdlDigest());
        record.put("selectedOptionId", resolution.selectedOptionId());
        record.put("freeTextDigest", resolution.freeTextDigest());
        append(record);
    }

    /**
     * 持久化公开探索工具的调用摘要，不记录原始搜索词或业务数据。
     *
     * @param tenantId 租户编号
     * @param employeeId 员工编号
     * @param capabilityId 公开能力编号
     * @param decision 权限决策
     * @param paramsDigest 参数摘要
     * @param outcome 结果状态
     */
    public void recordPublicToolCall(String tenantId, Long employeeId, String capabilityId,
                                     String decision, String paramsDigest, String outcome) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", "PUBLIC_TOOL_CALL");
        record.put("recordedAt", Instant.now().toString());
        record.put("tenantId", tenantId);
        record.put("employeeId", employeeId);
        record.put("capabilityId", capabilityId);
        record.put("decision", decision);
        record.put("paramsDigest", paramsDigest);
        record.put("outcome", outcome);
        append(record);
    }

    private void append(Map<String, Object> record) {
        byte[] bytes = (GsonUtil.toJson(record) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                Path parent = auditPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (FileChannel channel = FileChannel.open(auditPath, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Local execution audit persistence failed", exception);
            }
        }
    }
}



