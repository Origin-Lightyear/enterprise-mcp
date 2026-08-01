package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 公司信息表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "公司信息表")
public class OrgCompany {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 公司编码（全局唯一）
     */
    @PrivateMcpToolParam(description = "公司编码（全局唯一）")
    private String code;

    /**
     * 公司全称
     */
    @PrivateMcpToolParam(description = "公司全称")
    private String name;

    /**
     * 法定代表人
     */
    @PrivateMcpToolParam(description = "法定代表人")
    private String legalPerson;

    /**
     * 纳税人识别号
     */
    @PrivateMcpToolParam(description = "纳税人识别号")
    private String taxNumber;

    /**
     * 状态：1启用，0停用
     */
    @PrivateMcpToolParam(description = "状态：1启用，0停用")
    private Integer status;

    /**
     * 创建时间
     */
    @PrivateMcpToolParam(description = "创建时间")
    private LocalDateTime createdAt;
}
