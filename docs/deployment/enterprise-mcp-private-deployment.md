# enterprise-mcp 私有部署能力设计

## 1. 背景

当前 `enterprise-mcp` 的运行方式，本质上还是一个“单机服务 + 定时轮询配置”的模型：

- 启动时同步一次租户配置。
- 之后依赖定时轮询刷新本地缓存。
- 本地仅保存短时缓存，没有安装、升级、回滚、分发的控制面。

这在私有化交付场景里不够用。现场部署时，真正缺的是一条完整链路：

1. 能安装。
2. 能升级。
3. 能回滚。
4. 能把版本和配置分发到各节点。

## 2. 目标

把 `enterprise-mcp` 从“靠轮询活着”的服务，补成可交付产品：

- 安装器负责首次落地。
- 版本包负责可重复安装和升级。
- 配置分发负责把租户配置、运行参数、密钥和功能开关送到节点。
- 回滚机制负责把故障面收回来。

## 3. 现状判断

当前代码里的相关能力主要集中在：

- `src/main/java/com/kevin/mcp/agent/connector/TenantConfigSynchronizer.java`
- `src/main/java/com/kevin/mcp/agent/connector/TenantCacheService.java`
- `src/main/java/com/kevin/mcp/configuration/TenantConfigPollingConfiguration.java`
- `src/main/resources/application.yml`

现有模型只解决了“本进程如何拿到最新租户状态”，没有解决“新版本如何进入现场”和“故障时如何回退”。

## 4. 设计原则

- 安装、升级、配置分发分开设计，不要绑死在一个轮询任务里。
- 运行时配置与安装期配置分层，别把所有东西都塞进同一份 `application.yml`。
- 版本切换要原子化，不能半升级半运行。
- 回滚必须保留上一版完整工件和配置快照。
- 轮询只做兜底，不做主通路。

## 5. 推荐架构

### 5.1 安装器

安装器的职责：

- 校验环境依赖。
- 解压或落盘服务包。
- 写入基础配置。
- 注册系统服务或启动脚本。

首期只支持 Linux，使用 `systemd` 包装脚本，并统一采用“版本目录 + 当前指针”的发布方式。Windows 安装器不在本期范围内。

目录示意：

```text
/opt/enterprise-mcp/
  releases/
    1.0.0/
    1.0.1/
  current -> releases/1.0.1/
  shared/
    application-private.yml
    logs/
    data/
```

### 5.2 版本升级

升级流程建议如下：

1. 拉取版本清单。
2. 下载目标版本包。
3. 校验签名或哈希。
4. 预检配置和环境。
5. 停止旧实例或切流。
6. 切换 `current` 指针。
7. 启动新实例。
8. 做健康检查。

失败时直接切回上一版本目录。

### 5.3 配置分发

建议把配置拆成两类：

- 安装期配置：端口、数据库、日志目录、对外地址、租户绑定。
- 运行期配置：租户状态、授权截止时间、员工配置、功能开关、限流参数。

分发机制建议支持：

- 启动拉取一次完整快照。
- 版本号驱动增量刷新。
- Webhook / 推送作为加速通道。
- 轮询作为兜底通道。

### 5.4 回滚

回滚必须覆盖两层：

- 应用版本回滚。
- 配置版本回滚。

原则是：应用和配置都要有“上一个已知可用版本”。否则只能停机，不能回退。

## 6. 对现有代码的影响

当前这条链路：

- `TenantConfigSynchronizer` 负责定时轮询。
- `TenantCacheService` 负责本地缓存。
- `TenantAvailabilityFilter` 在入口拦截不可用租户。

建议演进为：

- 保留 `TenantAvailabilityFilter` 作为请求入口保护。
- 将 `TenantConfigSynchronizer` 降级成 fallback sync。
- 引入一个版本化配置源，优先读分发下发的快照。
- 轮询只用于补偿丢包、补偿推送失败、补偿节点重启。

## 7. 推荐落地顺序

### 第一阶段

- 做安装包和启动器。
- 固化版本目录结构。
- 支持完整配置文件注入。

### 第二阶段

- 做版本清单和升级包下载。
- 加哈希校验。
- 加原子切换和回滚脚本。

### 第三阶段

- 做配置分发控制面。
- 提供版本号、快照、增量下发能力。
- 将轮询改为兜底。

### 第四阶段

- 做灰度发布、健康探测、自动回滚。
- 统一审计升级和配置变更历史。

## 8. 结论

如果 `enterprise-mcp` 要作为私有部署产品交付，现有的“定时轮询拉配置”只能算生存手段，不算产品能力。真正需要补齐的是一套版本化交付体系：安装器、升级器、配置分发器、回滚器，四者缺一不可。

## 9. 已确认的 Platform / Tenant 产品边界

### 9.1 租户 MCP 模型

- 每个租户拥有独立的一组定制 MCPServer，不使用跨租户共享 MCP 产品目录。
- Tenant 不存在默认 MCP；所有 MCP 必须由 Platform 下发，Platform 未配置时 Tenant 没有 MCP。
- Platform 维护每个租户的 MCP 明细：数量由明细行数量决定，不单独保存 MCP 数量字段。
- Platform 主键作为跨 Platform 与 Tenant 的 MCP 标识；Tenant 可以保留本地自增主键，但不能用本地主键跨服务管理。
- Platform 管理 MCP 名称、版本和安装包下载信息；Tenant 管理员只能手工填写企业内部访问 URL。
- Tenant 不允许新增、改名或删除 Platform 未下发的 MCP，也不允许手填任意 MCP 管理项。
- 一个 MCP 只有一个当前访问 URL；URL 为空表示未配置，不向 Agent 客户端返回。
- MCP 是否可用不再使用独立启用开关，避免 URL 与开关形成重复状态。

### 9.2 Platform 版本管理

- 每个租户的每个 MCPServer 独立维护版本历史。
- 版本发布采用“草稿 -> 已发布”流程，已发布版本对 Tenant 展示更新说明。
- 版本不设置最低升级版本、数据库迁移、兼容门槛或跳过中间版本规则。
- 发布记录至少保存版本号、更新说明、安装包、文件大小和 SHA-256；安装包复用 Platform 现有阿里 OSS 能力。
- Platform 通过统一下载出口提供安装包，不直接暴露 OSS 细节。
- 删除整个 MCPServer 时同步删除 Tenant 对应配置；删除单个发布版本不属于本期需求，审计数据保留。

### 9.3 Tenant 更新与回滚

- Tenant 同时展示 Platform 最新发布版本和 Enterprise-mcp 实际成功安装版本，两者不同即显示有更新。
- Tenant 管理员可以查看更新内容并一键更新，也可以选择历史版本回滚；回滚本质上是重新部署指定历史版本。
- 更新请求由 Tenant 后端发给 Enterprise-mcp，前端不得直接调用升级接口。
- 每个 MCPServer 独立进程、目录、systemd 服务、升级和回滚，互不影响。
- Enterprise-mcp 使用独立 updater；主进程非 root，updater 仅以受限权限执行版本切换和服务重启。
- Linux 首期使用 bootstrap 安装脚本、版本目录、`current` 软链接和独立 systemd 服务。
- 安装包必须携带不可变 `manifest.json`，updater 校验 MCP 主键、版本、启动信息、健康检查方式和 SHA-256。
- 升级成功必须同时满足 systemd active 和 manifest 声明的健康检查通过；失败自动回滚。

### 9.4 配置发现与同步

- Tenant 登录或打开 MCP 管理页时主动同步 Platform 清单，定时同步作为兜底。
- Platform 删除 MCP 后主动通知 Tenant；Tenant 删除对应 MCP 配置，Agent 客户端后续拉取不到该地址。
- Tenant 保存 URL 时不要求 Platform 或 Tenant 能访问企业内网地址，健康检查异步执行。
- Enterprise-mcp 后续只适配安装、更新、回滚和状态回报，不负责读取 Tenant 的 MCP URL。
