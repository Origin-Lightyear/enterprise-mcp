package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 供应商信息表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "供应商信息表")
public class Supplier {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 供应商编码
     */
    @PrivateMcpToolParam(description = "供应商编码")
    private String code;

    /**
     * 供应商名称
     */
    @PrivateMcpToolParam(description = "供应商名称")
    private String name;

    /**
     * 主要联系人
     */
    @PrivateMcpToolParam(description = "主要联系人")
    private String contact;

    /**
     * 联系电话
     */
    @PrivateMcpToolParam(description = "联系电话")
    private String phone;

    /**
     * 供应商地址
     */
    @PrivateMcpToolParam(description = "供应商地址")
    private String address;

    /**
     * 结算方式（如：月结30天）
     */
    @PrivateMcpToolParam(description = "结算方式（如：月结30天）")
    private String paymentTerms;

    /**
     * 状态：1合作中，0已终止
     */
    @PrivateMcpToolParam(description = "状态：1合作中，0已终止")
    private Integer status;
}
