package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收银员班次表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "收银员班次表")
public class StoreShift {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 门店ID
     */
    @PrivateMcpToolParam(description = "门店ID")
    private Long storeId;

    /**
     * 收银员用户ID
     */
    @PrivateMcpToolParam(description = "收银员用户ID")
    private Long cashierId;

    /**
     * 班次编号（日期+收银员）
     */
    @PrivateMcpToolParam(description = "班次编号（日期+收银员）")
    private String shiftNo;

    /**
     * 开台时间
     */
    @PrivateMcpToolParam(description = "开台时间")
    private LocalDateTime startTime;

    /**
     * 结账下班时间
     */
    @PrivateMcpToolParam(description = "结账下班时间")
    private LocalDateTime endTime;

    /**
     * 系统理论应收总额（根据销售单汇总）
     */
    @PrivateMcpToolParam(description = "系统理论应收总额（根据销售单汇总）")
    private BigDecimal expectedAmount;

    /**
     * 实际清点现金总额
     */
    @PrivateMcpToolParam(description = "实际清点现金总额")
    private BigDecimal actualAmount;

    /**
     * 长短款差异
     */
    @PrivateMcpToolParam(description = "长短款差异")
    private BigDecimal difference;

    /**
     * 状态：1营业中，0已扎账关闭
     */
    @PrivateMcpToolParam(description = "状态：1营业中，0已扎账关闭")
    private Integer status;
}
