package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购订单主表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "采购订单主表")
public class PurchaseOrder {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 采购订单号（业务唯一编码）
     */
    @PrivateMcpToolParam(description = "采购订单号（业务唯一编码）")
    private String poNo;

    /**
     * 供应商ID
     */
    @PrivateMcpToolParam(description = "供应商ID")
    private Long supplierId;

    /**
     * 目标收货总仓ID
     */
    @PrivateMcpToolParam(description = "目标收货总仓ID")
    private Long warehouseId;

    /**
     * 采购总金额
     */
    @PrivateMcpToolParam(description = "采购总金额")
    private BigDecimal totalAmount;

    /**
     * 状态：0草稿，1已审核，2部分收货，3已完成，4已取消
     */
    @PrivateMcpToolParam(description = "状态：0草稿，1已审核，2部分收货，3已完成，4已取消")
    private Integer status;

    /**
     * 制单人ID
     */
    @PrivateMcpToolParam(description = "制单人ID")
    private Long createdBy;

    /**
     * 创建时间
     */
    @PrivateMcpToolParam(description = "创建时间")
    private LocalDateTime createdAt;

    /**
     * 审核时间
     */
    @PrivateMcpToolParam(description = "审核时间")
    private LocalDateTime auditedAt;
}
