package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 系统用户表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "系统用户表")
public class SysUser {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 所属门店ID（0表示总部/后台管理员）
     */
    @PrivateMcpToolParam(description = "所属门店ID（0表示总部/后台管理员）")
    private Long storeId;

    /**
     * 登录账号
     */
    @PrivateMcpToolParam(description = "登录账号")
    private String username;

    /**
     * 真实姓名
     */
    @PrivateMcpToolParam(description = "真实姓名")
    private String realName;

    /**
     * 登录密码（默认123456的32位MD5）
     */
    @PrivateMcpToolParam(description = "登录密码（默认123456的32位MD5）")
    private String password;

    /**
     * 角色：ADMIN管理员，MANAGER店长，CASHIER收银员
     */
    @PrivateMcpToolParam(description = "角色：ADMIN管理员，MANAGER店长，CASHIER收银员")
    private String role;

    /**
     * 手机号
     */
    @PrivateMcpToolParam(description = "手机号")
    private String mobile;

    /**
     * 状态：1在职，0离职/停用
     */
    @PrivateMcpToolParam(description = "状态：1在职，0离职/停用")
    private Integer status;

    /**
     * 创建时间
     */
    @PrivateMcpToolParam(description = "创建时间")
    private LocalDateTime createdAt;
}
