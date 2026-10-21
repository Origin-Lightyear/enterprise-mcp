package com.kevin.mcp.clarification;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 通过 MCP Elicitation 收集并校验用户对 Wren 语义歧义的确认。
 *
 * @author Kevin
 * @date 2026-09-21
 */
@Service
public class ClarificationElicitationService {

    private static final int MAX_ANSWER_LENGTH = 1000;

    /**
     * 发起一次结构化 Elicitation 并把回答绑定到服务端候选项。
     *
     * @param requestContext MCP 请求上下文
     * @param request Wren 返回的结构化澄清请求
     * @return 用户操作和经过校验的确认内容
     * @throws IllegalStateException 客户端不支持 Elicitation 或接受了无效答案
     */
    public ClarificationResolution elicit(McpSyncRequestContext requestContext, ClarificationRequest request) {
        if (requestContext == null || !requestContext.elicitEnabled()) {
            throw new IllegalStateException("Agent does not support MCP Elicitation");
        }
        StructuredElicitResult<ClarificationAnswer> result = requestContext.elicit(
                spec -> spec.message(buildMessage(request))
                        .meta("clarificationId", request.id())
                        .meta("sourceKey", request.sourceKey())
                        .meta("mdlDigest", request.mdlDigest()),
                ClarificationAnswer.class
        );
        if (result == null || result.action() == null) {
            throw new IllegalStateException("Agent returned an invalid Elicitation result");
        }
        if (!McpSchema.ElicitResult.Action.ACCEPT.equals(result.action())) {
            return new ClarificationResolution(result.action(), "", "", "");
        }
        ClarificationAnswer answer = result.structuredContent();
        if (answer == null) {
            throw new IllegalStateException("Agent accepted Elicitation without an answer");
        }
        String optionId = normalized(answer.optionId());
        String freeText = normalized(answer.answer());
        if (!optionId.isEmpty() && !freeText.isEmpty()) {
            throw new IllegalStateException("Elicitation must contain either optionId or answer, not both");
        }
        if (!optionId.isEmpty()) {
            ClarificationRequest.ClarificationOption option = request.options().stream()
                    .filter(candidate -> optionId.equals(candidate.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Elicitation selected an unknown option"));
            String confirmation = "用户已确认选择“" + option.label() + "”（语义字段：" + option.id() + "）";
            return new ClarificationResolution(result.action(), confirmation, option.id(), "");
        }
        if (!request.allowFreeText() || freeText.isEmpty() || freeText.length() > MAX_ANSWER_LENGTH) {
            throw new IllegalStateException("Elicitation free-text answer is missing or invalid");
        }
        return new ClarificationResolution(result.action(), "用户补充说明：" + freeText, "", digest(freeText));
    }

    private String buildMessage(ClarificationRequest request) {
        StringBuilder message = new StringBuilder(request.question()).append("\n请选择一个候选项：");
        for (ClarificationRequest.ClarificationOption option : request.options()) {
            message.append("\n- ").append(option.label()).append(" [").append(option.id()).append(']');
        }
        if (request.allowFreeText()) {
            message.append("\n也可以在 answer 中补充其他明确含义。");
        }
        return message.toString();
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 接收 Agent 返回的候选项或自由文本回答。
     *
     * @param optionId 所选 {@code Model.column}；使用自由文本时为空
     * @param answer 自由文本回答；选择候选项时为空
     */
    public record ClarificationAnswer(String optionId, String answer) {
    }
}

