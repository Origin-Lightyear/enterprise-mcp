package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 会员个人信息表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "会员个人信息表")
public class MemberInfo {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 会员卡号（全局唯一）
     */
    @PrivateMcpToolParam(description = "会员卡号（全局唯一）")
    private String code;

    /**
     * 会员姓名
     */
    @PrivateMcpToolParam(description = "会员姓名")
    private String name;

    /**
     * 手机号（登录/找回凭证）
     */
    @PrivateMcpToolParam(description = "手机号（登录/找回凭证）")
    private String phone;

    /**
     * 当前会员等级ID
     */
    @PrivateMcpToolParam(description = "当前会员等级ID")
    private Long levelId;

    /**
     * 历史累计总积分（永不清零）
     */
    @PrivateMcpToolParam(description = "历史累计总积分（永不清零）")
    private Integer totalPoints;

    /**
     * 当前可用积分（可兑换）
     */
    @PrivateMcpToolParam(description = "当前可用积分（可兑换）")
    private Integer availablePoints;

    /**
     * 注册门店ID
     */
    @PrivateMcpToolParam(description = "注册门店ID")
    private Long registerStoreId;

    /**
     * 生日（用于营销）
     */
    @PrivateMcpToolParam(description = "生日（用于营销）")
    private LocalDate birthday;

    /**
     * 性别：0未知，1男，2女
     */
    @PrivateMcpToolParam(description = "性别：0未知，1男，2女")
    private Integer gender;

    /**
     * 注册时间
     */
    @PrivateMcpToolParam(description = "注册时间")
    private LocalDateTime createdAt;
}
