package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 表示 ERP 订单详情。(演示用)
 *
 * @author Kevin
 * 2026/7/21
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "ERP订单信息")
public class ErpOrder {
    @PrivateMcpToolParam(description = "订单ID")
    private String orderId;

    @PrivateMcpToolParam(description = "客户名称")
    private String customerName;

    @PrivateMcpToolParam(description = "客户地址")
    private String customerAddress;


    /**
     * 返回订单ID，便于框架序列化结果对象。
     *
     * @return 订单ID
     */
    public String getOrderId() {
        return orderId;
    }

    /**
     * 更新订单ID。
     *
     * @param orderId 订单ID
     */
    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    /**
     * 返回客户名称，便于框架序列化结果对象。
     *
     * @return 客户名称
     */
    public String getCustomerName() {
        return customerName;
    }

    /**
     * 更新客户名称。
     *
     * @param customerName 客户名称
     */
    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    /**
     * 返回客户地址，便于框架序列化结果对象。
     *
     * @return 客户地址
     */
    public String getCustomerAddress() {
        return customerAddress;
    }

    /**
     * 更新客户地址。
     *
     * @param customerAddress 客户地址
     */
    public void setCustomerAddress(String customerAddress) {
        this.customerAddress = customerAddress;
    }
}
