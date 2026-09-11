# 客户主模块

`customer/app` 是客户侧唯一主程序，也是新增客户时主要修改的模块。最终客户只部署这里生成的一个 Spring Boot Fat JAR。

## 放什么

- `bootstrap`：客户启动类和能力装配。
- `capability`：客户的 `@PrivateMcpTool` 与 `@LocalCapability`。
- `configuration`：客户能力注册和客户专属 Spring 装配。
- `domain`：客户领域对象。
- `repository`：客户 Mapper/Repository，需要时创建。
- `integration`：客户内部 HTTP、SDK 或其他系统 Adapter，需要时创建。
- `src/main/resources/sql`：客户固定 SQL 模板，需要时创建。
- `src/main/resources/mapper`：客户 MyBatis XML，需要时创建。
- `application.yml`：无密钥默认配置；生产值由外置配置覆盖。

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

运行时 Registry 会自动扫描 Spring Bean 上的 `@PrivateMcpTool` 与 `@LocalCapability`。构建会生成并打包：

```text
```

## 启动

```bash
java -jar target/customer-enterprise-mcp-1.0.0.jar \
  --spring.config.additional-location=file:/etc/enterprise-mcp/<instance-id>/application.yml
```

生产外置配置至少要提供租户身份、SaaS 地址、Control Plane 规划地址和 Catalog 绑定信息；Runtime 注册地址由规划地址自动生成、无需单独配置。
以及客户数据源或 API 配置。密码和 Token 必须通过环境变量或 Secret Provider 注入。
