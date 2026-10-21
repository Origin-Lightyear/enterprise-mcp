# 客户主模块

`customer/app` 是客户侧唯一主程序，也是新增客户时主要修改的模块。最终客户只部署这里生成的一个 Spring Boot Fat JAR。

## 包结构

| 包 | 职责 |
|---|---|
| `com.customer.mcp` | 客户启动类 `CustomerMcpApplication`。 |
| `com.customer.mcp.controller` | 客户侧 HTTP Controller，如运行健康检查。 |
| `com.customer.mcp.domain` | 客户领域对象。`ErpOrder` 是新增领域实体的示例代码，可按同样风格扩展。 |
| `com.customer.mcp.tool.publicmcp` | 公开 MCP 工具，标注 `@PublicMcpTool`，已验证员工可通过 Agent 直接调用。 |
| `com.customer.mcp.tool.internalmcp` | 内部 MCP 工具，标注 `@PrivateMcpTool` 或经内部门面转发，仅在 Runtime/Tenant 链路内使用。 |
| `com.customer.mcp.k3` | 金蝶 K3 领域层：`catalog`（现场目录）、`client`（SDK 网关）、`errors`（错误模式）、`knowledge`（本地确认知识）。 |
| `com.customer.mcp.wren` | Wren 本机语义查询领域层：Sidecar 客户端、进程管理、管理端 Controller/Session/Filter。 |

## 新增 MCP 工具

- 公开工具放 `tool/publicmcp`，方法标 `@McpTool` + `@PublicMcpTool`。
- 内部工具放 `tool/internalmcp`，方法标 `@McpTool` 或 `@PrivateMcpTool`。
- 领域逻辑放 `k3`/`wren` 或新领域包，工具类保持薄封装。
- 领域实体放 `domain`，参考 `ErpOrder` 的注解与 Javadoc 风格。

客户专属代码不能放到 `customer/runtime`，云端代码不能放到本模块。

## 构建

从仓库根目录执行：

```bash
mvn -pl customer/app -am clean package
```

最终产物：

```text
customer/app/target/customer-enterprise-mcp-1.0.0.jar
```

运行时 Registry 会自动扫描 Spring Bean 上的 `@McpTool`、`@PublicMcpTool`、`@PrivateMcpTool` 与 `@LocalCapability`。

## 启动

```bash
java -jar target/customer-enterprise-mcp-1.0.0.jar \
  --spring.config.additional-location=file:/etc/enterprise-mcp/<instance-id>/application.yml
```

生产外置配置至少要提供租户身份、SaaS 地址、Control Plane 规划地址和 Catalog 绑定信息；Runtime 注册地址由规划地址自动生成、无需单独配置。
以及客户数据源或 API 配置。密码和 Token 必须通过环境变量或 Secret Provider 注入。
