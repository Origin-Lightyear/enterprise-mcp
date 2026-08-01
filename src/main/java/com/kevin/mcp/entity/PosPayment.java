package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/**
 * POS支付记录表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "POS支付记录表")
public class PosPayment {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 关联POS订单主表ID
     */
    @PrivateMcpToolParam(description = "关联POS订单主表ID")
    private Long orderId;

    /**
     * 支付方式：CASH现金，WEIXIN微信，ALIPAY支付宝，CARD银行卡，COUPON优惠券
     */
    @PrivateMcpToolParam(description = "支付方式：CASH现金，WEIXIN微信，ALIPAY支付宝，CARD银行卡，COUPON优惠券")
    private String payType;

    /**
     * 该方式支付金额
     */
    @PrivateMcpToolParam(description = "该方式支付金额")
    private BigDecimal payAmount;

    /**
     * 第三方支付流水号（微信/支付宝）
     */
    @PrivateMcpToolParam(description = "第三方支付流水号（微信/支付宝）")
    private String transactionId;

    /**
     * 银行卡后四位（脱敏）
     */
    @PrivateMcpToolParam(description = "银行卡后四位（脱敏）")
    private String cardLastFour;
}
