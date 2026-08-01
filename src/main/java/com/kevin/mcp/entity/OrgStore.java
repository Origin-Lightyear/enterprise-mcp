package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 门店主数据表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "门店主数据表")
public class OrgStore {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 所属公司ID
     */
    @PrivateMcpToolParam(description = "所属公司ID")
    private Long companyId;

    /**
     * 所属区域ID
     */
    @PrivateMcpToolParam(description = "所属区域ID")
    private Long regionId;

    /**
     * 门店编码（POS系统唯一标识）
     */
    @PrivateMcpToolParam(description = "门店编码（POS系统唯一标识）")
    private String code;

    /**
     * 门店名称
     */
    @PrivateMcpToolParam(description = "门店名称")
    private String name;

    /**
     * 门店等级：A旗舰店，B标准店，C社区店
     */
    @PrivateMcpToolParam(description = "门店等级：A旗舰店，B标准店，C社区店")
    private String level;

    /**
     * 详细地址
     */
    @PrivateMcpToolParam(description = "详细地址")
    private String address;

    /**
     * 门店联系电话
     */
    @PrivateMcpToolParam(description = "门店联系电话")
    private String contactPhone;

    /**
     * 营业时间（如：09:00-22:00）
     */
    @PrivateMcpToolParam(description = "营业时间（如：09:00-22:00）")
    private String businessHours;

    /**
     * 状态：1营业中，0已闭店
     */
    @PrivateMcpToolParam(description = "状态：1营业中，0已闭店")
    private Integer status;

    /**
     * 创建时间
     */
    @PrivateMcpToolParam(description = "创建时间")
    private LocalDateTime createdAt;
}
