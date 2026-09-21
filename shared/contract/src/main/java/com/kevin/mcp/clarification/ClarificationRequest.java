package com.kevin.mcp.clarification;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 承载一次绑定 Current MDL 的用户澄清请求。
 *
 * @param id 澄清请求稳定标识
 * @param question 向用户展示的问题
 * @param reason 触发澄清的稳定原因码
 * @param options 可选择的真实语义字段
 * @param allowFreeText 是否允许用户自由补充
 * @param sourceKey 数据源稳定标识
 * @param mdlDigest 识别歧义时使用的 Current MDL 摘要
 * @author Kevin
 * @date 2026-09-21
 */
public record ClarificationRequest(
        String id,
        String question,
        String reason,
        List<ClarificationOption> options,
        boolean allowFreeText,
        String sourceKey,
        String mdlDigest
) {

    /**
     * 复制候选列表，防止调用方在发起 Elicitation 后修改选项含义。
     */
    public ClarificationRequest {
        options = options == null ? List.of() : List.copyOf(options);
        if (isBlank(id) || isBlank(question) || isBlank(reason) || isBlank(sourceKey) || isBlank(mdlDigest)) {
            throw new IllegalArgumentException("Clarification identity, question, reason and MDL binding are required");
        }
        if (options.size() < 2 || options.size() > 5) {
            throw new IllegalArgumentException("Clarification must contain 2 to 5 options");
        }
        Set<String> optionIds = new HashSet<>();
        for (ClarificationOption option : options) {
            if (option == null || !optionIds.add(option.id())) {
                throw new IllegalArgumentException("Clarification option IDs must be unique");
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 承载一个可由用户确认的 MDL 语义字段。
     *
     * @param id MDL 中稳定的 {@code Model.column} 标识
     * @param label 用户可读标签
     */
    public record ClarificationOption(String id, String label) {

        /** 校验候选项必须同时具备稳定 ID 和用户可读标签。 */
        public ClarificationOption {
            if (isBlank(id) || isBlank(label)) {
                throw new IllegalArgumentException("Clarification option ID and label are required");
            }
        }
    }
}
