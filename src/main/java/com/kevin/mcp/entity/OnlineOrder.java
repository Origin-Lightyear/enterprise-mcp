package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 线上第三方订单主表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "线上第三方订单主表")
public class OnlineOrder {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 外部平台订单号（美团/饿了么）
     */
    @PrivateMcpToolParam(description = "外部平台订单号（美团/饿了么）")
    private String platformOrderNo;

    /**
     * 平台来源：MEITUAN，ELEME，WECHAT（小程序）
     */
    @PrivateMcpToolParam(description = "平台来源：MEITUAN，ELEME，WECHAT（小程序）")
    private String platform;

    /**
     * 接单/履约门店ID
     */
    @PrivateMcpToolParam(description = "接单/履约门店ID")
    private Long storeId;

    /**
     * 系统内会员ID（0表示非会员）
     */
    @PrivateMcpToolParam(description = "系统内会员ID（0表示非会员）")
    private Long memberId;

    /**
     * 订单总金额（折前）
     */
    @PrivateMcpToolParam(description = "订单总金额（折前）")
    private BigDecimal totalAmount;

    /**
     * 平台/店铺优惠
     */
    @PrivateMcpToolParam(description = "平台/店铺优惠")
    private BigDecimal discountAmount;

    /**
     * 用户实付金额
     */
    @PrivateMcpToolParam(description = "用户实付金额")
    private BigDecimal payAmount;

    /**
     * 状态：0待接单，1待拣货，2待配送，3已完成，4用户取消，5平台取消
     */
    @PrivateMcpToolParam(description = "状态：0待接单，1待拣货，2待配送，3已完成，4用户取消，5平台取消")
    private Integer status;

    /**
     * 收货人姓名
     */
    @PrivateMcpToolParam(description = "收货人姓名")
    private String receiverName;

    /**
     * 收货人电话
     */
    @PrivateMcpToolParam(description = "收货人电话")
    private String receiverPhone;

    /**
     * 收货地址（配送上门）
     */
    @PrivateMcpToolParam(description = "收货地址（配送上门）")
    private String receiverAddress;

    /**
     * 配送费
     */
    @PrivateMcpToolParam(description = "配送费")
    private BigDecimal deliveryFee;

    /**
     * 下单时间
     */
    @PrivateMcpToolParam(description = "下单时间")
    private LocalDateTime orderTime;
}
