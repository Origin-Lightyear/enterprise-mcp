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
 * 门店每日销售对账汇总表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "门店每日销售对账汇总表")
public class FinanceSettlement {
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
     * 对账日期（格式：YYYY-MM-DD）
     */
    @PrivateMcpToolParam(description = "对账日期（格式：YYYY-MM-DD）")
    private LocalDate settleDate;

    /**
     * 当日总销售额（折后实收）
     */
    @PrivateMcpToolParam(description = "当日总销售额（折后实收）")
    private BigDecimal totalSales;

    /**
     * 现金收款合计
     */
    @PrivateMcpToolParam(description = "现金收款合计")
    private BigDecimal cashAmount;

    /**
     * 微信支付合计
     */
    @PrivateMcpToolParam(description = "微信支付合计")
    private BigDecimal weixinAmount;

    /**
     * 支付宝支付合计
     */
    @PrivateMcpToolParam(description = "支付宝支付合计")
    private BigDecimal alipayAmount;

    /**
     * 银行卡刷卡合计
     */
    @PrivateMcpToolParam(description = "银行卡刷卡合计")
    private BigDecimal cardAmount;

    /**
     * 优惠券抵扣金额合计
     */
    @PrivateMcpToolParam(description = "优惠券抵扣金额合计")
    private BigDecimal couponAmount;

    /**
     * 当日退货金额
     */
    @PrivateMcpToolParam(description = "当日退货金额")
    private BigDecimal refundAmount;

    /**
     * 净销售额（虚拟列）
     */
    @PrivateMcpToolParam(description = "净销售额（虚拟列）")
    private BigDecimal netSales;

    /**
     * 对账状态：0未对账，1已平账，2有差异
     */
    @PrivateMcpToolParam(description = "对账状态：0未对账，1已平账，2有差异")
    private Integer status;

    /**
     * 平账/对账完成时间
     */
    @PrivateMcpToolParam(description = "平账/对账完成时间")
    private LocalDateTime settledAt;
}
