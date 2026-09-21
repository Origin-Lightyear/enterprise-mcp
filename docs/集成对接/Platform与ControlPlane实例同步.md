# Platform 与 Control Plane 的 MCP 实例同步

Platform 是 MCP 实例身份和期望发布版本的事实来源。创建租户 MCP 时，Platform 生成租户内稳定且唯一的 `instanceId`，并在每次新增、改名、删除、发布后向 Control Plane 提交该租户的全量快照。Control Plane 的期望实例表和 Runtime 实际实例表均以 `(tenant_id, instance_id)` 作为业务唯一键；不同租户可以使用相同的实例编号。

Control Plane 将快照写入 `mcp_platform_instance`：当前快照中的实例为 `ACTIVE`，缺失实例标记为 `DELETED`。Runtime 启动后仍向 `mcp_runtime_instance` 上报实际 JAR、Catalog 和心跳；数据库注册入口只接受已由 Platform 声明为 `ACTIVE` 的实例，因此客户端上报不再能够单独创建未知实例。

Platform 配置 `ENTERPRISE_MCP_CONTROL_PLANE_URL` 后启用云端同步；为空时保留 Platform 到 Tenant 的本地同步行为。安装包 manifest 包含 `instanceId`，bootstrap 默认读取该值并将其注入 `enterprise.runtime.instance-id`，升级器会拒绝实例号不匹配的包。

Control Plane 数据库采用单一 `V1__init_control_plane.sql` 初始化最终表结构，适用于清空数据库后的重新部署，不包含旧版本数据库的升级或修复逻辑。

## 验证边界

- 已验证 Control Plane 完整构建，并通过 MySQL 8.4 空库启动执行唯一 V1 初始化脚本。
- 已验证不同租户可以复用相同 `instance_id`，同一租户内的重复 `instance_id` 会被数据库唯一约束拒绝。
- 仍需在联通 Platform、Control Plane 和 MySQL 的环境验证真实 HTTP 鉴权、快照重试、Runtime 首次注册及删除后的拒绝行为。
