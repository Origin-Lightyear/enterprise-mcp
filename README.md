# Enterprise MCP

本仓库只有四个 Maven 模块、两个可执行主程序和两个最终部署 JAR。

## 先看结论

| 部署位置 | 主模块 | 最终产物 | 作用 |
|---|---|---|---|
| 厂商云端 | `cloud/control-plane` | `enterprise-mcp-control-plane-0.0.1-SNAPSHOT.jar` | 保存规划目录，调用员工 LLM 生成并校验调用链 |
| 客户内网 | `customer/app` | `customer-enterprise-mcp-1.0.0.jar` | 暴露 MCP、校验权限并调用客户数据库或 API |

客户只部署 `customer/app` 生成的一个 Fat JAR。`shared/contract` 和 `customer/runtime` 会作为依赖打进该 JAR，不需要在客户服务器单独部署。

## 四模块结构

```text
enterprise-mcp/
├── shared/contract       # 两侧共享的稳定契约，不可执行
├── cloud/control-plane   # 云端唯一主程序
├── customer/runtime      # 客户侧公共执行内核，不可独立启动
└── customer/app          # 客户侧唯一主程序和客户定制代码
```

依赖方向固定为：

```text
customer/app -> customer/runtime -> shared/contract
cloud/control-plane ----------------> shared/contract
```

云端不依赖 `customer/runtime`，因此不会打入客户方法、数据库驱动、SQL 或内部 API Adapter。

## 平时改哪里

- 新增或修改客户对外 `@McpTool`、内部 `@PrivateMcpTool`、Mapper、SQL、内部 API Adapter：改 `customer/app`。
- 修改本地计划执行、多数据源连接、安全校验、审计：改 `customer/runtime`。
- 修改云端 LLM 规划、Catalog/Runtime 注册和计划结构校验：改 `cloud/control-plane`。
- 修改两侧共同使用的注解、计划 DTO、签名信封：改 `shared/contract`。

## 一次构建

在仓库根目录执行：

```bash
mvn clean package
```

最终运行产物：

```text
cloud/control-plane/target/enterprise-mcp-control-plane-0.0.1-SNAPSHOT.jar
customer/app/target/customer-enterprise-mcp-1.0.0.jar
```

`shared/contract/target` 和 `customer/runtime/target` 也会生成普通依赖 JAR，但它们只是构建中间产物，不是部署程序。

## 启动云端

至少配置 Tenant 服务地址与签名参数后启动。云端没有全局 LLM API Key；每次规划按 Runtime
提交的 `tenantId + employeeId` 从 Tenant 获取该员工的 `newapiUrl/apiKey`，再解析并使用该员工模型：

```bash
set ENTERPRISE_MCP_CONTROL_TENANT_SERVICE_BASE_URL=https://tenant.example.com
set ENTERPRISE_MCP_CONTROL_SIGNING_PRIVATE_KEY=<base64-pkcs8-private-key>
set ENTERPRISE_MCP_CONTROL_SIGNING_KEY_ID=key-2026-01
set ENTERPRISE_MCP_DB_URL=jdbc:mysql://mysql.example.com:3306/saas_mcp
set ENTERPRISE_MCP_DB_USERNAME=enterprise_mcp
set ENTERPRISE_MCP_DB_PASSWORD=<由 Secret 管理器注入>
```

```bash
java -jar cloud/control-plane/target/enterprise-mcp-control-plane-0.0.1-SNAPSHOT.jar
```

配置入口是 `cloud/control-plane/src/main/resources/application.yml`，生产环境通过环境变量或外置配置覆盖。
Control Plane 启动时通过 Flyway 自动升级 MySQL，不再使用本地 JSON 文件保存 Catalog 或 Runtime 注册状态。
Tenant 的员工 LLM 接口当前必须位于受控服务网络；正式生产还应增加服务间认证，不能直接暴露到公网。

## 启动客户侧

至少配置租户身份、SaaS 地址、Control Plane 规划地址和 `SAAS_AGENT_PUBLIC_KEY_PEM`
和 `SAAS_AGENT_PUBLIC_KEY_FINGERPRINT` 后启动：

```bash
java -jar customer/app/target/customer-enterprise-mcp-1.0.0.jar
```

配置入口是 `customer/app/src/main/resources/application.yml`。数据库和 API 通过 `enterprise.resources.profiles` 配置，密码和 Token 必须来自环境变量、JVM 属性或客户 Secret Provider，不能提交到仓库。

全部文档及用途见 [文档目录](docs/文档目录.md)。系统架构、流程、数据流、时序、启动、部署和升级统一见 [系统说明](docs/系统说明.md)。模块开发说明见 [模块开发与打包指南](docs/模块开发与打包指南.md)，完整阶段计划见 [多客户 MCP 与跨系统执行开发计划](docs/架构设计/多客户MCP与跨系统执行开发计划.md)。
