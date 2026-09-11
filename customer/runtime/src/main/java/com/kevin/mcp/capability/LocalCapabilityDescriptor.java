package com.kevin.mcp.capability;

import com.kevin.mcp.annotation.OperationMode;

/**
 * 保存本地能力与反射方法之间的绑定信息。
 *
 * @author Kevin
 * @date 2026-09-09
 *
 * @param capabilityId 稳定能力编号
 * @param version 能力契约版本
 * @param methodKey 本地反射方法键
 * @param mode 能力读写模式
 * @param dataDomain 数据域
 */
public record LocalCapabilityDescriptor(
        String capabilityId,
        int version,
        String methodKey,
        OperationMode mode,
        String dataDomain
) {
}
