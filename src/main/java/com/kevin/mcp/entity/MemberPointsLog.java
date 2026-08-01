package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 会员积分流水日志
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "会员积分流水日志")
public class MemberPointsLog {
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
     * 来源单号（POS单号/线上单号）
     */
    @PrivateMcpToolParam(description = "来源单号（POS单号/线上单号）")
    private String orderNo;

    /**
     * 变动积分（正数增加，负数消耗）
     */
    @PrivateMcpToolParam(description = "变动积分（正数增加，负数消耗）")
    private Integer points;

    /**
     * 变动前积分
     */
    @PrivateMcpToolParam(description = "变动前积分")
    private Integer beforePoints;

    /**
     * 变动后积分
     */
    @PrivateMcpToolParam(description = "变动后积分")
    private Integer afterPoints;

    /**
     * 变动原因（如：消费满50送10积分）
     */
    @PrivateMcpToolParam(description = "变动原因（如：消费满50送10积分）")
    private String reason;

    /**
     * 变动时间
     */
    @PrivateMcpToolParam(description = "变动时间")
    private LocalDateTime createdAt;
}
