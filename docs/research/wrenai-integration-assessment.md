# WrenAI 集成评估

评估对象：[`Canner/WrenAI`](https://github.com/Canner/WrenAI) 主分支。  
核查日期：2026-09-11。本文只引用 WrenAI 官方仓库源码、官方仓库文档和仓库许可证文件；WrenAI 版本持续变化，集成前应锁定 commit/tag 并复核。

## 结论

WrenAI 可以作为本项目的**数据库语义建模、SQL 生成/规划和查询执行子系统**，尤其适合“一张表一个内部 MCP Tool”难以覆盖复杂条件、Join、聚合和指标定义的场景。但它不应直接替代 enterprise-mcp 的云端 Control Plane、签名执行计划或客户 Runtime：WrenAI 的核心输入是 SQL/MDL，核心输出是数据库查询结果；其官方 MCP 服务主要暴露 `run_sql`、`dry_run`、`dry_plan`、`query_cube` 等数据库工具，而不是跨能力的 `call/foreach/condition/notify` 执行图。

推荐形态是**客户侧或独立受控数据库规划服务**：客户数据库连接和 MDL 留在客户边界内，WrenAI 负责把受控的用户查询/计划转换为 SQL；enterprise-mcp 云端只签发客户已发布的 `capabilityId`，不把裸 SQL、数据库凭据或 Wren 内部项目文件交给云端。若需要把 Wren 生成的 SQL 纳入 enterprise-mcp 计划，必须在本地增加 SQL 解析、模型/字段 allowlist、行数/超时/数据域策略和审计校验。

## 官方架构与能力

WrenAI 官方架构文档将系统分为 Agent workflow、project context、planning engine 和 execution layer；SQL 规划链路使用 `sqlglot`、CTE rewriter 和 Rust `wren-core`，把面向 MDL 模型的 SQL 展开为目标数据库方言后交给连接器执行：[Architecture](https://github.com/Canner/WrenAI/blob/main/docs/core/reference/architecture.md)。

MDL（Modeling Definition Language）是其语义契约，支持 models、columns、relationships、calculated fields、views、cubes、metrics 和层级等业务语义；源文件可审查、版本控制，编译为 `target/mdl.json`：[What is MDL?](https://github.com/Canner/WrenAI/blob/main/docs/core/concepts/what_is_mdl.md)。仓库中的 MDL JSON Schema 也明确包含 relationships、views、cubes、measures、dimensions 和 hierarchies：[mdl.schema.json](https://github.com/Canner/WrenAI/blob/main/core/wren-mdl/mdl.schema.json)。

官方 MCP 文档显示，`wren serve mcp` 在进程内基于已编译 MDL 和活动连接 profile 提供：

- 查询：`run_sql`、`dry_run`、`dry_plan`、`query_cube`；
- Schema/语义：`get_mdl`、`list_models`、`describe_model`、`list_cubes` 等；
- 知识与记忆：`get_context`、`recall_queries`、`get_instructions` 等；
- stdio 默认传输，也可使用 Streamable HTTP。

官方文档同时说明：`--allow-write` 才启用 `store_query`，默认服务为只读；HTTP 模式默认绑定 `127.0.0.1`，当前版本没有 bearer-token 认证，应保持本地使用：[Serve an MCP server](https://github.com/Canner/WrenAI/blob/main/docs/core/guides/mcp.md)。

当前 Wren MCP 工具中的 `run_sql` 接收 SQL 字符串并默认最多返回 1000 行、硬上限 10000 行；`query_cube` 是结构化的度量/维度聚合查询。实现见官方 `mcp_server.py`：[mcp_server.py](https://github.com/Canner/WrenAI/blob/main/core/wren/src/wren/mcp_server.py)。这意味着 Wren 的灵活性主要来自“受 MDL 约束的 SQL”，而不是一个通用 JSON `QuerySpec` 条件树。

## 数据源与部署依赖

WrenAI 当前 OSS CLI/SDK 要求 Python `>=3.11`，依赖 `wren-core-py`（Rust/PyO3 绑定）、DuckDB、`sqlglot`、Pydantic、PyArrow 等；各数据源通过 optional extras 安装，官方列出 PostgreSQL、MySQL、BigQuery、Snowflake、ClickHouse、Trino、SQL Server、Databricks、Redshift、Oracle、Athena、Spark 等：[core/wren/pyproject.toml](https://github.com/Canner/WrenAI/blob/main/core/wren/pyproject.toml) 与 [Connect your database](https://github.com/Canner/WrenAI/blob/main/docs/core/guides/connect.md)。

连接 profile 存在用户环境（官方示例为 `~/.wren/profiles.yml`），项目中的 MDL/knowledge 与环境连接凭据分离；MCP 文档明确表示连接凭据在服务端解析并保持在 MCP 边界内，只把 SQL、结果和元数据跨边界传输。这与客户 Runtime 持有 resource profile、云端不持有密钥的边界相容，但需要把 Wren profile 纳入现有密钥托管、租户隔离和启动生命周期，而不能照搬用户目录配置。

Wren 的官方 MCP HTTP 服务当前无内置 bearer-token 认证；若作为客户 Runtime 内部组件，优先使用同进程/本机 stdio 或 loopback，并由 enterprise-mcp Runtime 在外层做 mTLS、签名计划、租户/员工授权和审计。若部署为远程服务，必须在反向代理或适配器层补认证、授权、限流和网络隔离。

## 与 enterprise-mcp 的适配关系

### 适合复用的部分

1. **MDL 作为客户数据库语义目录**：可以表达大区、门店、员工、订单模型、关系、指标（如销售额）和批准的计算口径，降低云端仅依赖裸表 Schema 的歧义。
2. **SQL 规划和数据库方言处理**：Wren 的 `sqlglot`/wren-core/connector 链路可承载复杂 Join、CTE、聚合、窗口函数和目标数据库方言，避免为每个字段组合预写 MCP 方法。
3. **dry-plan/dry-run 与结果限制**：可作为本地执行前的校验步骤，但必须与 enterprise-mcp 自己的计划验签、预算和数据策略叠加。
4. **MCP/SDK 接入**：Wren 可被本地 Agent 或适配器调用；其 MDL、memory、instructions 文件也可作为客户侧语义资产。

### 不应直接复用的部分

1. **裸 `run_sql(sql)` 作为云端能力**：会把客户表/字段/SQL 语义和高风险查询入口暴露到云端，不符合 enterprise-mcp 的 capability allowlist 和客户本地最终授权边界。
2. **Wren MCP 作为 enterprise-mcp 执行计划协议**：Wren 官方工具是数据库查询工作流，不包含签名 capability plan、资源绑定、员工身份、跨能力依赖、写入幂等、Outbox、通知或补偿语义。
3. **Wren 的 `--allow-write/store_query` 作为业务写入**：官方该开关用于保存 NL→SQL 知识，不等于行政系统写入或数据库业务事务能力。
4. **商业安全能力的默认假设**：官方 README 将 RLS/CLS、用户组访问控制、GenBI UI/API、增强审计等列为 Cloud/self-hosted Enterprise Plus 商业能力；OSS 集成不能假设这些能力已提供。

## 推荐集成方案

### 方案 A：客户 Runtime 内嵌/伴随 Wren（推荐 PoC）

```text
云端 Control Plane
  -> 签名的 customer capability plan（不含裸 SQL/凭据）
客户 Runtime
  -> 校验签名、租户、员工、资源和预算
  -> Wren adapter：加载客户 MDL，生成/校验 SQL
  -> 本地连接器执行 SQL
  -> 返回脱敏、限行、可审计结果
```

可把 Wren 作为独立 Python sidecar 或本地服务，再由客户 Fat JAR 通过 loopback/进程适配；不建议把 Python/Rust 运行时直接打进现有 Java Fat JAR，除非接受额外的打包、升级和供应链维护成本。

### 方案 B：客户发布业务查询能力

客户用 Wren MDL/SQL 定义复杂查询，再在 Java `@PrivateMcpTool` 中封装为稳定业务能力，例如 `employee.performance.query`。云端只看到该能力的输入/输出 Schema；Wren 负责能力内部的数据库查询。这是最容易与现有 `LocalCapability`、签名计划和审计模型对齐的生产形态。

### 方案 C：云端直接调用 Wren MCP（不推荐）

除非增加完整的本地代理、认证、SQL/MDL allowlist、结果策略和租户隔离，否则直接把 Wren MCP HTTP 暴露给云端会绕过客户 Runtime 最终授权边界，也无法解决跨系统写入/通知的可靠性问题。

## 许可证与合规

WrenAI 仓库根部 `LICENSE` 给出按路径的多许可证映射：`core/**`、`sdk/**`、`skills/**`、`examples/**` 和根文件为 Apache License 2.0；`docs/**` 为 CC BY 4.0；仓库预留未来 AGPL-3.0 模块并要求以包清单为准：[LICENSE](https://github.com/Canner/WrenAI/blob/main/LICENSE)。

因此：

- 以依赖、sidecar 或独立进程使用 OSS `core`/`sdk`，通常可按 Apache-2.0 做 NOTICE、版权和变更保留；
- 不要把 `docs/**` 的 CC BY 内容当作 Apache 代码复制进产品；
- 必须锁定具体版本并扫描 transitive dependencies 的许可证；
- 需要持续检查仓库未来是否引入 AGPL 模块，以及商业 Cloud/Enterprise Plus 功能是否被误用；
- Wren、WrenAI 名称和 Logo 是 Canner 商标，许可证不授予商标权。

## 风险与验证边界

官方资料证明了 Wren 的源码能力和接口形态，但没有证明它已经满足本项目的生产运行条件。集成前仍需验证：

- Java Fat JAR 与 Python/Rust sidecar 的部署、升级和故障恢复；
- 每租户 MDL、连接 profile、缓存和内存索引隔离；
- Wren 生成 SQL 的本地 allowlist、字段/行级策略、超时和大结果处理；
- 数据库事务、写入幂等、审计和与行政 API/钉钉通知的 Saga/Outbox；
- 客户实际数据库方言、权限、网络和连接池；
- Wren MCP HTTP 无 bearer auth 时的网络暴露风险；
- CJK 业务术语、指标定义、员工隐私字段和“所有”语义下的分页完整性。

## 最终判断

WrenAI **适合做客户数据库的语义层和 SQL 查询规划器**，能显著增强复杂条件、跨表关系、聚合和报表查询；它**不适合直接作为 enterprise-mcp 的通用云端执行计划引擎**。推荐先做一个客户侧 PoC：选三张业务表和订单表，建立 MDL relationship/cube，调用 `dry_plan`/`dry_run` 验证查询，再由 Java `@PrivateMcpTool` 以业务能力方式封装结果。PoC 通过后，再决定 Wren 是客户 Fat JAR 外部 sidecar，还是受控本地服务；云端仍维持 capability Catalog、签名计划和最终 Runtime 授权。

