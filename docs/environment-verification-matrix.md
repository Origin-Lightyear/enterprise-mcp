# 多客户 MCP 环境验证矩阵

本文区分本仓库可自动验证的内容和必须进入客户或生产基础设施验证的内容。编译、单元测试和本机启动都不能替代真实系统验收。

## 当前仓库可验证

| 模型要素 | 已实现行为 | 开发或运维入口 | 验证证据 |
|---|---|---|---|
| 四模块结构 | 固定为共享契约、客户 Runtime、客户主程序、云端主程序 | 根 `pom.xml` | Maven Reactor 模块列表、整仓测试 |
| 客户单 JAR | Runtime、契约、客户能力和 Manifest 打入一个 Fat JAR | `customer/app` | `mvn package`、`jar tf`、本机启动 |
| 云端单 JAR | Catalog 和规划打入一个 Fat JAR | `cloud/control-plane` | `mvn package`、`jar tf`、本机启动 |
| Schema/Manifest | 构建期扫描能力类并打包，启动时核对 Registry | `customer/app/pom.xml` 能力类清单 | 生成器测试、Fat JAR 内容检查 |
| capabilityId 执行 | 版本、依赖、foreach、预算和本地白名单校验 | `customer/runtime/execution`、`authorization` | Runtime 单元测试 |
| JDBC 多资源 | 独立资源配置、连接池、只读 SQL、行数限制 | `enterprise.resources.profiles` | H2 自动化测试、驱动装配检查 |
| HTTP 多资源 | 认证、超时、重试、限流、熔断和 Secret Provider | `enterprise.resources.profiles` | HTTP 模拟测试 |
| Runtime Catalog 注册 | 完整 Schema、租户、实例和 JAR 摘要持久化 | Runtime 注册 HTTP 入口 | MySQL 集成验证 |
| Agent 请求验签 | 每客户独立 RSA 公钥和指纹，Runtime 启动时校验错配 | 客户外置配置 | PEM、指纹错配和请求签名测试 |
| 部署脚本 | 客户 JAR 安装、升级、健康检查和失败回滚 | 根 `deploy/` | `bash -n`；完整 systemd 流程不在 Windows 本机验证 |

## 必须接入真实环境

| 项目 | 为什么本地不能代替 | 必需证据 |
|---|---|---|
| 客户 MySQL | 网络、TLS、账号权限、方言、字符集、时区和数据量由客户决定 | 实际只读查询、最小权限和慢查询记录 |
| 客户 SQL Server | 驱动存在不代表实例协议、加密、排序规则和 SQL 兼容 | 实际连接、查询结果和超时记录 |
| 客户内部 API/金蝶 | URL、证书、认证、签名和字段语义是客户特定的 | 正常、4xx、5xx、超时和重复请求证据 |
| 员工身份与权限 | 必须验证真实身份映射、禁用、到期、越权和脱敏 | 正常/越权用例与审计日志 |
| 跨系统调用链 | 模拟数据不能证明真实数据关联和部分失败语义 | MySQL -> SQL Server -> API 场景报告 |
| Control Plane 生产存储 | MySQL/Flyway 表结构和 JDBC 仓储已接入；备份、故障恢复和多实例压测仍依赖部署环境 | 多实例并发、重启恢复、备份恢复测试 |
| 生产密钥 | 密钥托管、轮换、吊销和权限依赖生产设施 | 密钥轮换、旧计划失效和审计记录 |
| 安装升级回滚 | 依赖 Linux、systemd、下载存储、健康端点和运维权限 | 新旧版本切换与失败自动回滚报告 |

## 完成口径

当前仓库能够完成的是工程结构、自动化测试、两个 Fat JAR 的构建和本机启动验证。只有上表真实环境项目全部留下证据后，才可以宣布某个具体客户达到生产交付标准。
