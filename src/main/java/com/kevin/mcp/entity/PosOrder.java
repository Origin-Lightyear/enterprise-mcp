package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 门店POS销售订单主表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "门店POS销售订单主表")
public class PosOrder {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 销售小票单号（门店唯一）
     */
    @PrivateMcpToolParam(description = "销售小票单号（门店唯一）")
    private String orderNo;

    /**
     * 销售门店ID
     */
    @PrivateMcpToolParam(description = "销售门店ID")
    private Long storeId;

    /**
     * 所属收银班次ID
     */
    @PrivateMcpToolParam(description = "所属收银班次ID")
    private Long shiftId;

    /**
     * 收银员ID
     */
    @PrivateMcpToolParam(description = "收银员ID")
    private Long cashierId;

    /**
     * 会员ID（0表示非会员散客）
     */
    @PrivateMcpToolParam(description = "会员ID（0表示非会员散客）")
    private Long memberId;

    /**
     * 原始总金额（折前）
     */
    @PrivateMcpToolParam(description = "原始总金额（折前）")
    private BigDecimal totalAmount;

    /**
     * 整单优惠金额（含促销/优惠券）
     */
    @PrivateMcpToolParam(description = "整单优惠金额（含促销/优惠券）")
    private BigDecimal discountAmount;

    /**
     * 最终实付金额
     */
    @PrivateMcpToolParam(description = "最终实付金额")
    private BigDecimal payAmount;

    /**
     * 交易时间
     */
    @PrivateMcpToolParam(description = "交易时间")
    private LocalDateTime orderTime;

    /**
     * 状态：1正常，2全额退货，3部分退货
     */
    @PrivateMcpToolParam(description = "状态：1正常，2全额退货，3部分退货")
    private Integer status;

    /**
     * 若为退货单，关联原单号
     */
    @PrivateMcpToolParam(description = "若为退货单，关联原单号")
    private String returnParentNo;
}
