package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 会员优惠券持有表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "会员优惠券持有表")
public class MemberCoupon {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 会员ID
     */
    @PrivateMcpToolParam(description = "会员ID")
    private Long memberId;

    /**
     * 优惠券名称（如：满100减20）
     */
    @PrivateMcpToolParam(description = "优惠券名称（如：满100减20）")
    private String couponName;

    /**
     * 券类型：1满减券，2折扣券，3代金券
     */
    @PrivateMcpToolParam(description = "券类型：1满减券，2折扣券，3代金券")
    private Integer type;

    /**
     * 使用门槛（满X元可用）
     */
    @PrivateMcpToolParam(description = "使用门槛（满X元可用）")
    private BigDecimal thresholdAmount;

    /**
     * 减免金额（或折扣数，如8表示8折）
     */
    @PrivateMcpToolParam(description = "减免金额（或折扣数，如8表示8折）")
    private BigDecimal discountAmount;

    /**
     * 状态：1未使用，2已使用，3已过期，4已作废
     */
    @PrivateMcpToolParam(description = "状态：1未使用，2已使用，3已过期，4已作废")
    private Integer status;

    /**
     * 来源：SYSTEM自动发，ACTIVITY活动领
     */
    @PrivateMcpToolParam(description = "来源：SYSTEM自动发，ACTIVITY活动领")
    private String source;

    /**
     * 过期日期
     */
    @PrivateMcpToolParam(description = "过期日期")
    private LocalDate expireDate;

    /**
     * 使用的订单号
     */
    @PrivateMcpToolParam(description = "使用的订单号")
    private String useOrderNo;

    /**
     * 使用时间
     */
    @PrivateMcpToolParam(description = "使用时间")
    private LocalDateTime usedAt;
}
