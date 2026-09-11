# 多客户内部 MCP 与跨系统执行架构开发计划

本文是多客户、多数据库和多内部 API 场景的总体设计与实施基线。目标是在保护云端规划能力的同时，让客户内部方法与数据始终留在客户环境，并保持每个客户只部署一个可执行 JAR。

## 1. 最终目标

一次用户意图可以形成跨 MySQL、SQL Server 和内部 API 的调用链：

```text
用户意图
  -> 客户 Runtime 携带部署身份、租户/员工身份和 Catalog 版本请求云端
  -> 厂商云端从 Tenant 获取该员工 LLM 配置
  -> 厂商云端组合 Schema + Prompt + 用户意图并使用员工配置调用 LLM
  -> 云端校验 capabilityId 执行计划并签名
  -> 客户 Runtime 本地鉴权和校验预算
  -> 客户 @PrivateMcpTool 按计划访问多个数据库或 API
  -> 客户 Runtime 汇总、脱敏、审计并返回
```

必须同时满足：

1. 客户现场只部署一个客户专属 Fat JAR。
2. 厂商云端只部署一个 Control Plane JAR。
3. 客户内部方法、SQL、Mapper、API Adapter 和凭据不进入云端。
4. 云端掌握语义目录、LLM 规划、目录生命周期和计划签名。
5. 客户 Runtime 不执行云端任意代码、任意 SQL 或任意 Java 类名。
6. 新客户主要修改 `customer/app`，公共 Runtime 尽量复用。
7. 对外 `@McpTool` 由客户 app 定义，公共 Runtime 只提供不带 MCP 注解的安全执行门面。

## 2. 最终工程结构

```text
enterprise-mcp/
├── pom.xml
├── README.md
├── shared/
│   └── contract/             # 两侧共享契约
├── cloud/
│   └── control-plane/        # 云端唯一主程序
├── customer/
│   ├── runtime/              # 客户侧公共 Runtime
│   └── app/                  # 客户侧唯一主程序和客户定制代码
├── deploy/                   # 客户单 JAR 安装、升级和回滚脚本
└── docs/                     # 架构、部署、集成和历史文档
```

四个 Maven 模块已经替代原先按 kernel、connector、schema-generator、starter 等技术点拆出的浅模块。连接器和生成器仍然存在，但作为 `customer/runtime` 内部 package，不再单独增加开发和发布成本。

## 3. 部署边界

### 3.1 云端

主模块：`cloud/control-plane`

最终产物：

```text
enterprise-mcp-control-plane-0.0.1-SNAPSHOT.jar
```

云端保存：

- 客户能力的语义目录和技术约束；
- Catalog 的租户、实例、JAR 摘要和完整 Schema；
- Runtime 注册的租户、实例和实际 JAR 摘要；
- Tenant 员工 LLM 配置接口地址、缓存和 LLM 网络超时；
- 计划签名私钥和密钥编号。

云端不保存：

- 客户 Java 方法体；
- 客户 SQL、Mapper 和数据库驱动；
- 客户内网 URL 和业务凭据；
- 员工 LLM API Key 的持久化副本；
- 客户查询得到的完整业务数据。

### 3.2 客户侧

主模块：`customer/app`

最终产物：

```text
customer-enterprise-mcp-1.0.0.jar
```

客户 JAR 保存：

- 客户对外 `@McpTool` 的名称、描述、参数和返回契约；
- 客户 `@PrivateMcpTool` 和 `@LocalCapability`；
- 客户领域模型、Mapper、SQL 和 API Adapter；
- 公共 Runtime 的 MCP、执行、安全、审计和连接器代码；
- 公共契约；
- 构建生成的技术 Schema 和执行 Manifest；
- 该客户实际需要的数据库驱动和 SDK。

## 4. Schema 与云端同步方案

云端生成计划必须知道能力 Schema。Runtime 启动时直接上报扫描得到的完整 Schema，云端持久化后供规划使用：

```text
客户能力源码
  -> Runtime 启动扫描 @LocalCapability + @PrivateMcpTool
  -> 完整规划 Schema
```

- Runtime 启动时直接将完整规划 Schema 上报云端，云端保存 Catalog 快照并供规划使用。
- 每个技术能力包含 `@PrivateMcpTool.description`、输入/输出 Schema、数据域和版本，让云端 LLM 能判断能力用途与参数约束。
- Runtime 启动后上报租户、实例、版本、JAR 摘要和完整规划 Schema，不上传 Java 实现。
- 云端保存 Schema Catalog 后即可规划，JAR 摘要用于绑定实例与 Catalog。
- 每份计划绑定租户、实例、JAR 版本、artifactDigest、员工、过期时间和唯一 `jti`。

云端内部固定流程：

```text
PlanningCatalog.catalogJson
  + PlanningPrompt.OUTPUT_SCHEMA
  + 用户原始意图
  + EmployeeLlmConfigService(tenantId, employeeId)
  -> CloudLlmClient
  -> ExecutionPlanGenerator 严格提取 JSON
  -> ExecutionPlanValidator 校验 call/foreach/dependsOn/fromStep/fromItem
  -> ControlPlanePlanningService 绑定 Runtime 身份并返回计划
```

客户 JAR 中只保留 `JsonExecutionPlan` DTO、`JsonPlanParser` 和 `JsonPlanExecutor`，用于理解并执行已签名协议；
不得包含 `PromptContext`、规划 LLM Client 或任何本地计划生成 fallback。

这避免人工维护两份 Schema，也避免客户生产 Runtime 成为云端目录的任意覆盖来源。

## 5. 能力模型

每个可规划能力至少包含：

- 稳定 `capabilityId`；
- 能力版本；
- `READ_ONLY` 或明确的写入模式；
- 数据域；
- 输入和输出 Schema；
- 本地 Java `methodKey`，仅存在客户 Manifest 和 Registry 中；
- 所需资源逻辑编号；
- 超时、行数和结果大小限制。

云端计划只能引用 `capabilityId`，客户 Registry 再将它映射到本地 `methodKey`。删除或升级能力时必须同步提升版本并重新发布 Catalog。

## 6. 多数据源方案

所有客户资源由 `enterprise.resources.profiles` 按逻辑编号配置：

```yaml
enterprise:
  resources:
    profiles:
      order-mysql:
        type: JDBC
        vendor: MYSQL
        url: ${ORDER_MYSQL_URL}
        username: ${ORDER_MYSQL_USERNAME}
        secret-ref: ORDER_MYSQL_PASSWORD
      finance-sqlserver:
        type: JDBC
        vendor: SQLSERVER
        url: ${FINANCE_SQLSERVER_URL}
        username: ${FINANCE_SQLSERVER_USERNAME}
        secret-ref: FINANCE_SQLSERVER_PASSWORD
      member-api:
        type: HTTP
        url: ${MEMBER_API_URL}
        auth-type: BEARER
        secret-ref: MEMBER_API_TOKEN
```

Runtime 为每个 JDBC 资源维护独立连接池，不使用全局 `ThreadLocal` 动态切库。客户 Capability 通过显式资源编号调用连接器，代码阅读时可以直接判断访问了哪个系统。

SQL 必须来自客户代码中的固定模板或 Mapper，参数使用绑定变量。LLM 不生成 SQL。HTTP Adapter 必须配置超时、认证、重试边界、限流和熔断；非幂等请求不得自动重试。

## 7. 计划执行与安全

客户 Runtime 按以下顺序处理云端计划：

1. 校验租户和员工身份。
2. 校验计划签名、`keyId`、过期时间和客户/实例绑定。
3. 使用 `jti` 防重放。
4. 校验 Runtime 版本与 artifactDigest。
5. 校验能力版本、本地白名单和员工权限。
6. 校验最大步骤数、循环次数、执行时间和结果大小。
7. 解析依赖并按顺序执行本地 Capability。
8. 对最终结果执行脱敏。
9. 记录不含原始客户数据的审计摘要。

权限执行必须是确定性的：`allowCapabilities` 在本地逐能力校验。旧自然语言 `denyRules` 无法在移除客户侧 LLM 后可靠解释，
因此迁移完成前采用失败关闭；未提供精确字段路径的 `sensitiveRules` 对整份结果脱敏。真实环境需要将 Tenant 权限契约升级为
明确的 capability、操作和字段路径规则，不能重新把 LLM 当作客户侧最终授权器。

首期主要支持只读跨系统链路。跨数据库和 API 不使用分布式事务。后续写操作必须逐能力设计幂等键、事实来源、部分成功状态、补偿动作和人工处置入口。

## 8. 配置和密钥

配置严格按运行进程归属：

- 云端配置：`cloud/control-plane/src/main/resources/application.yml`。
- 客户配置：`customer/app/src/main/resources/application.yml`。
- `customer/runtime` 不携带 `application.yml`。

以下内容不得写入仓库或打入 JAR：

- 数据库密码；
- API Token；
- 员工 LLM Key；
- 云端签名私钥；
- 客户验签公钥的生产值；
- 客户证书私钥。

每个客户的 AgentServer RSA 公钥和 SHA-256 指纹属于客户部署配置，不得硬编码进公共 Runtime。
Runtime 启动时同时校验公钥格式与指纹；缺失、损坏或错配时拒绝启动。指纹用于发现发布错配，配置文件本身仍必须由受信部署流程保护。

生产环境使用外置配置、环境变量、Kubernetes Secret、Vault 或客户密码管理系统。日志不得输出密钥、Authorization、请求体或完整查询结果。

员工 LLM Key 由 Tenant 保存。客户 Runtime 请求云端时只传经过本地验证的 `tenantId + employeeId`；Control Plane
通过 Tenant 内部接口读取 `newapiUrl/apiKey`，必要时调用员工 `newapiUrl/v1/models` 解析默认模型，再使用该请求级配置调用 LLM。
员工配置在云端仅按 `tenantId + employeeId` 进行有容量上限的短 TTL 内存缓存；硬过期刷新失败时规划失败，不使用旧 Key。

当前 Tenant `/api/v1/agent/inner/employee-llm-config` 使用 `@AllowAnonymous`，安全性依赖受控服务网络。
接入生产环境时必须补充 mTLS 或服务身份 Token，并校验 `tenantId` 与 `tenantId` 的归属关系。

## 9. 新客户扩展方式

新客户默认只修改 `customer/app`：

1. 调整 Maven 坐标、版本和客户 Java 根包。
2. 在 `tool` 中定义客户自己的 `@McpTool`，并委托 `CommonInternalTool.execute`。
3. 增加客户 `capability`、`domain`、`repository`、`integration`。
4. 增加客户 SQL、Mapper XML 和专属 SDK。
5. 配置能力类清单并生成 Schema/Manifest。
6. 配置该客户 AgentServer RSA 公钥及 SHA-256 指纹。
7. 完成模拟测试和客户测试环境验证。
8. 构建一个客户 Fat JAR 并发布匹配 Catalog。

只有两个以上客户都需要且行为一致的代码，才考虑下沉 `customer/runtime`。客户字段、URL、签名规则和 SQL 不下沉公共 Runtime。

## 10. 构建与产物

根目录完整构建：

```bash
mvn clean test
mvn -DskipTests package
```

Maven 会生成四个模块 JAR：

| 模块 | JAR 类型 | 是否单独部署 |
|---|---|---|
| `shared/contract` | 普通依赖 JAR | 否 |
| `customer/runtime` | 普通依赖 JAR | 否 |
| `customer/app` | Spring Boot Fat JAR | 是，客户侧唯一 JAR |
| `cloud/control-plane` | Spring Boot Fat JAR | 是，云端唯一 JAR |

## 11. 分阶段开发计划与当前状态

| 阶段 | 交付内容 | 当前代码状态 | 完成证据 |
|---|---|---|---|
| Phase 1 契约 | 注解、计划 DTO、Schema、签名信封 | 已归并到 `shared/contract` | 整仓编译与测试 |
| Phase 2 本地执行 | Registry、计划解析、反射执行、依赖和 foreach | 已归并到 `customer/runtime/execution` 与 `capability` | Runtime 单元测试 |
| Phase 3 安全 | 计划验签、防重放、本地授权、预算、审计 | 已归并到 `security`、`authorization`、`audit` | 对应单元测试 |
| Phase 4 多资源 | MySQL、SQL Server、HTTP、Secret Provider | 已归并到 `customer/runtime/resource` | H2 和 HTTP 模拟测试 |
| Phase 5 Control Plane | Catalog、Runtime 注册、员工 LLM 配置、Schema + Prompt + LLM 规划、结构校验、签名 | 已归并到 `cloud/control-plane` | 员工隔离、硬过期、Prompt、计划、绑定测试与启动验证 |
| Phase 6 客户 Fat JAR | 客户代码、构建期 Schema/Manifest、单 JAR | 已归并到 `customer/app` | JAR 内容与启动验证 |
| Phase 7 部署运维 | Linux 安装、升级、回滚、健康检查 | 脚本位于根 `deploy` | `bash -n`；真实 systemd 待环境演练 |
| Phase 8 生产验收 | 真实数据库/API/权限/故障链路 | 必须接入客户环境 | 客户测试报告和审计证据 |

“当前代码状态已实现”只说明代码和本地自动化范围，不代表 Phase 8 的真实生产验收完成。

## 12. 本地验收清单

- 根 POM 只聚合四个模块。
- 云端不依赖 `customer/runtime`。
- 公共 Runtime 不包含客户配置或客户专属 SDK。
- 客户 Fat JAR 包含客户 Capability、Runtime、契约和 Manifest。
- 云端 Fat JAR 不包含客户 Capability、MySQL、SQL Server 或金蝶类。
- 客户 Fat JAR 不包含规划 Prompt、LLM Client、员工 LLM 配置服务或本地规划 fallback。
- 两个主 JAR 都可在随机端口启动。
- 全量测试和 `git diff --check` 通过。
- 部署脚本通过 Shell 语法检查。
- 文档中的路径、命令和实际产物一致。

## 13. 真实环境验收清单

- MySQL 和 SQL Server 网络、TLS、账号最小权限、字符集、时区和慢查询。
- 内部 API 证书、认证、字段语义、超时、幂等和限流。
- 员工身份映射、正常授权、越权拒绝和脱敏结果。
- Control Plane 到 Tenant 的服务间认证，以及 `tenantId` 与 `tenantId` 归属校验。
- 一条真实的 MySQL -> SQL Server -> API 跨系统只读计划。
- Control Plane 生产存储、多实例并发、备份与恢复。
- Ed25519/ES256 生产密钥托管、轮换和旧计划过期。
- systemd 安装、升级失败自动回滚和旧版本恢复。
- 外部系统部分失败时的审计、告警与人工处置。

## 14. 禁止事项

1. 云端直接连接客户数据库或内部 API。
2. 客户生产 Runtime 公开完整 `/schema` 供任意读取。
3. LLM 生成 SQL、Java 类名或可执行脚本。
4. 云端计划绕过本地签名、授权、版本和预算校验。
5. 把客户密码、Token 或签名私钥提交到仓库。
6. 用全局动态数据源上下文隐藏资源归属。
7. 用分布式事务包装跨库和 HTTP 链路。
8. 为每个连接器继续增加 Maven 模块。
9. 把普通依赖 JAR 当作客户需要分别部署的程序。
10. 仅凭 Maven 编译成功宣称真实客户环境已经验收。
