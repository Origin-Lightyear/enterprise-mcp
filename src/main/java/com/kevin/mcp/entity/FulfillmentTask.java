package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 门店O2O履约任务表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "门店O2O履约任务表")
public class FulfillmentTask {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 关联线上订单ID
     */
    @PrivateMcpToolParam(description = "关联线上订单ID")
    private Long onlineOrderId;

    /**
     * 履约门店ID
     */
    @PrivateMcpToolParam(description = "履约门店ID")
    private Long storeId;

    /**
     * 履约方式：PICKUP（到店自提），DELIVERY（骑手配送）
     */
    @PrivateMcpToolParam(description = "履约方式：PICKUP（到店自提），DELIVERY（骑手配送）")
    private String type;

    /**
     * 自提取货码（6位数字）
     */
    @PrivateMcpToolParam(description = "自提取货码（6位数字）")
    private String pickupCode;

    /**
     * 任务状态：0待处理，1拣货中，2打包完成待取/待发，3已提货/已送达
     */
    @PrivateMcpToolParam(description = "任务状态：0待处理，1拣货中，2打包完成待取/待发，3已提货/已送达")
    private Integer status;

    /**
     * 操作员（拣货员）ID
     */
    @PrivateMcpToolParam(description = "操作员（拣货员）ID")
    private Long operatorId;

    /**
     * 任务创建时间
     */
    @PrivateMcpToolParam(description = "任务创建时间")
    private LocalDateTime createdAt;

    /**
     * 任务更新时间
     */
    @PrivateMcpToolParam(description = "任务更新时间")
    private LocalDateTime updatedAt;
}
