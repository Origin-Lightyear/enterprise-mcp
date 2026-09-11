# Platform 与 Control Plane 的 MCP 实例同步

Platform 是 MCP 实例身份和期望发布版本的事实来源。创建租户 MCP 时，Platform 自动生成不可变且全系统唯一的 UUID 型 `instanceId`，并在每次新增、改名、删除、发布后向 Control Plane 提交该租户的全量快照。Platform、Control Plane 期望实例表和 Runtime 实际实例表均建立全局唯一约束。

Control Plane 将快照写入 `mcp_platform_instance`：当前快照中的实例为 `ACTIVE`，缺失实例标记为 `DELETED`。Runtime 启动后仍向 `mcp_runtime_instance` 上报实际 JAR、Catalog 和心跳；数据库注册入口只接受已由 Platform 声明为 `ACTIVE` 的实例，因此客户端上报不再能够单独创建未知实例。

Platform 配置 `ENTERPRISE_MCP_CONTROL_PLANE_URL` 后启用云端同步；为空时保留 Platform 到 Tenant 的本地同步行为。安装包 manifest 包含 `instanceId`，bootstrap 默认读取该值并将其注入 `enterprise.runtime.instance-id`，升级器会拒绝实例号不匹配的包。

## 验证边界

- 已验证 Platform 编译、Control Plane 测试、Flyway 迁移版本不冲突和部署脚本语法。
- 仍需在联通 Platform、Control Plane 和 MySQL 的环境验证真实 HTTP 鉴权、快照重试、Runtime 首次注册及删除后的拒绝行为。
