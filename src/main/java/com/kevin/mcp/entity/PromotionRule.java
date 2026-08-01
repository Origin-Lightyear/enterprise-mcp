package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 促销规则主表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "促销规则主表")
public class PromotionRule {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 促销活动名称（如：国庆全场8折）
     */
    @PrivateMcpToolParam(description = "促销活动名称（如：国庆全场8折）")
    private String name;

    /**
     * 促销类型：BUY_ONE_GET_ONE买一赠一，DISCOUNT打折，FULL_REDUCTION满减，MIX_MATCH混合
     */
    @PrivateMcpToolParam(description = "促销类型：BUY_ONE_GET_ONE买一赠一，DISCOUNT打折，FULL_REDUCTION满减，MIX_MATCH混合")
    private String type;

    /**
     * 活动开始时间
     */
    @PrivateMcpToolParam(description = "活动开始时间")
    private LocalDateTime startTime;

    /**
     * 活动结束时间
     */
    @PrivateMcpToolParam(description = "活动结束时间")
    private LocalDateTime endTime;

    /**
     * 状态：1生效中，0已停用
     */
    @PrivateMcpToolParam(description = "状态：1生效中，0已停用")
    private Integer status;

    /**
     * 优先级（数字越大越优先）
     */
    @PrivateMcpToolParam(description = "优先级（数字越大越优先）")
    private Integer priority;

    /**
     * 创建时间
     */
    @PrivateMcpToolParam(description = "创建时间")
    private LocalDateTime createdAt;
}
