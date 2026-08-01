package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/**
 * 会员等级配置表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "会员等级配置表")
public class MemberLevel {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 等级名称（如：青铜/白银/黄金/钻石）
     */
    @PrivateMcpToolParam(description = "等级名称（如：青铜/白银/黄金/钻石）")
    private String name;

    /**
     * 折扣率（100表示无折扣，95表示9.5折）
     */
    @PrivateMcpToolParam(description = "折扣率（100表示无折扣，95表示9.5折）")
    private BigDecimal discountRate;

    /**
     * 升级所需最低累计积分
     */
    @PrivateMcpToolParam(description = "升级所需最低累计积分")
    private Integer minPoints;

    /**
     * 权益描述（如：生日双倍积分）
     */
    @PrivateMcpToolParam(description = "权益描述（如：生日双倍积分）")
    private String benefits;
}
