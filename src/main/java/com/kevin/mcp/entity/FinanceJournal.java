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
 * 财务交易流水明细表（数据湖）
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "财务交易流水明细表（数据湖）")
public class FinanceJournal {
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
     * 来源单号（POS单号或线上单号）
     */
    @PrivateMcpToolParam(description = "来源单号（POS单号或线上单号）")
    private String sourceNo;

    /**
     * 来源类型：POS_SALE，ONLINE_SALE，REFUND，TRANSFER
     */
    @PrivateMcpToolParam(description = "来源类型：POS_SALE，ONLINE_SALE，REFUND，TRANSFER")
    private String sourceType;

    /**
     * 交易金额（正数为收入，负数为支出）
     */
    @PrivateMcpToolParam(description = "交易金额（正数为收入，负数为支出）")
    private BigDecimal amount;

    /**
     * 支付方式（同POS支付类型）
     */
    @PrivateMcpToolParam(description = "支付方式（同POS支付类型）")
    private String payMethod;

    /**
     * 映射的会计科目（如：主营业务收入）
     */
    @PrivateMcpToolParam(description = "映射的会计科目（如：主营业务收入）")
    private String accountSubject;

    /**
     * 交易日期（用于按月汇总）
     */
    @PrivateMcpToolParam(description = "交易日期（用于按月汇总）")
    private LocalDate transDate;

    /**
     * 流水生成时间
     */
    @PrivateMcpToolParam(description = "流水生成时间")
    private LocalDateTime createdAt;
}
